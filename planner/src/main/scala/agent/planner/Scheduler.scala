package agent.planner

import zio.*
import zio.json.*
import java.io.{FileWriter, BufferedReader, FileReader}
import java.nio.file.Paths
import java.time.{LocalDateTime, ZoneId}
import java.util.UUID

// ── 调度条目 ──────────────────────────────────────────────────────────────

case class ScheduleEntry(
  id:         String,
  cronExpr:   String,
  goal:       String,
  enabled:    Boolean        = true,
  createdAt:  Long           = java.lang.System.currentTimeMillis(),
  lastRunAt:  Option[Long]   = None,
  lastResult: Option[String] = None,
) derives JsonCodec

// ── Scheduler service ─────────────────────────────────────────────────────

trait Scheduler:
  def add(cronExpr: String, goal: String): Task[ScheduleEntry]
  def remove(id: String): Task[Boolean]
  def list(): Task[List[ScheduleEntry]]
  def startBackground(executor: AgentExecutor, printer: StatusPrinter): UIO[Unit]

object Scheduler:
  val layer: ZLayer[Any, Nothing, Scheduler] =
    ZLayer.succeed(FileScheduler())

  def add(cronExpr: String, goal: String): ZIO[Scheduler, Throwable, ScheduleEntry] =
    ZIO.serviceWithZIO[Scheduler](_.add(cronExpr, goal))

  def remove(id: String): ZIO[Scheduler, Throwable, Boolean] =
    ZIO.serviceWithZIO[Scheduler](_.remove(id))

  def list(): ZIO[Scheduler, Throwable, List[ScheduleEntry]] =
    ZIO.serviceWithZIO[Scheduler](_.list())

// ── 文件持久化实现 ────────────────────────────────────────────────────────

private class FileScheduler extends Scheduler:

  private val scheduleFile =
    Paths.get(java.lang.System.getProperty("user.home"), ".ai-agent", "schedules.json")

  private def ensureDir(): Unit =
    scheduleFile.getParent.toFile.mkdirs()

  private def loadAll(): Task[List[ScheduleEntry]] =
    ZIO.attemptBlocking {
      val f = scheduleFile.toFile
      if !f.exists() then Nil
      else
        val raw = { val br = new BufferedReader(new FileReader(f)); try br.lines().toArray.mkString("\n") finally br.close() }
        raw.fromJson[List[ScheduleEntry]].getOrElse(Nil)
    }

  private def saveAll(entries: List[ScheduleEntry]): Task[Unit] =
    ZIO.attemptBlocking {
      ensureDir()
      val fw = new FileWriter(scheduleFile.toFile)
      try fw.write(entries.toJson)
      finally fw.close()
    }.unit

  override def add(cronExpr: String, goal: String): Task[ScheduleEntry] =
    if !CronMatcher.isValid(cronExpr) then
      ZIO.fail(RuntimeException(s"Invalid cron expression: '$cronExpr'  (expected 5 fields: min hour dom month dow)"))
    else
      loadAll().flatMap { entries =>
        val entry = ScheduleEntry(UUID.randomUUID().toString.take(8), cronExpr, goal)
        saveAll(entries :+ entry).as(entry)
      }

  override def remove(id: String): Task[Boolean] =
    loadAll().flatMap { entries =>
      val (removed, kept) = entries.partition(_.id == id)
      if removed.isEmpty then ZIO.succeed(false)
      else saveAll(kept).as(true)
    }

  override def list(): Task[List[ScheduleEntry]] = loadAll()

  override def startBackground(
    executor: AgentExecutor,
    printer:  StatusPrinter,
  ): UIO[Unit] =
    oneTick(executor, printer)
      .catchAll(_ => ZIO.unit)
      .forever
      .forkDaemon
      .unit

  // 单次 tick：sleep 到下一分钟整点，然后检查并 fork 到期任务
  private def oneTick(executor: AgentExecutor, printer: StatusPrinter): Task[Unit] =
    for
      _   <- sleepToNextMinute()
      now <- ZIO.succeed(LocalDateTime.now())
      all <- loadAll().catchAll(_ => ZIO.succeed(Nil))
      due  = all.filter(e => e.enabled && CronMatcher.matches(e.cronExpr, now))
      _   <- ZIO.foreachDiscard(due) { entry =>
               (for
                 _ <- printer.planning(s"[scheduler:${entry.id}] ${entry.goal}")
                 r <- executor.run(entry.goal)
                 _ <- updateEntry(entry.id, now, r.take(500))
               yield ())
               .catchAll { e =>
                 printer.warning(s"[scheduler:${entry.id}] failed: ${e.getMessage}") *>
                 updateEntry(entry.id, now, s"ERROR: ${e.getMessage}")
               }
               .forkDaemon   // 每个调度任务各自 fork，互不阻塞
               .unit
             }
    yield ()

  private def updateEntry(id: String, ranAt: LocalDateTime, result: String): Task[Unit] =
    loadAll().flatMap { entries =>
      val epochMs = ranAt.atZone(ZoneId.systemDefault()).toInstant.toEpochMilli
      val updated = entries.map(e =>
        if e.id == id then e.copy(lastRunAt = Some(epochMs), lastResult = Some(result)) else e
      )
      saveAll(updated)
    }.catchAll(_ => ZIO.unit)

  private def sleepToNextMinute(): UIO[Unit] =
    ZIO.succeed(LocalDateTime.now()).flatMap { now =>
      ZIO.sleep((60 - now.getSecond).seconds)
    }

// ── Cron 表达式解析（5字段：min hour dom month dow）────────────────────────
// 支持：* | */N | N | N,M,... | N-M

object CronMatcher:

  def isValid(expr: String): Boolean =
    expr.trim.split("\\s+").length == 5

  def matches(expr: String, dt: LocalDateTime): Boolean =
    val fields = expr.trim.split("\\s+")
    if fields.length != 5 then return false
    matchField(fields(0), dt.getMinute,          0, 59) &&
    matchField(fields(1), dt.getHour,            0, 23) &&
    matchField(fields(2), dt.getDayOfMonth,      1, 31) &&
    matchField(fields(3), dt.getMonthValue,      1, 12) &&
    matchField(fields(4), dt.getDayOfWeek.getValue % 7, 0, 6)  // 0=Sun

  private def matchField(field: String, value: Int, min: Int, max: Int): Boolean =
    field.trim match
      case "*" => true
      case f if f.startsWith("*/") =>
        f.drop(2).toIntOption.exists(step => step > 0 && (value - min) % step == 0)
      case f if f.contains(",") =>
        f.split(",").flatMap(_.trim.toIntOption).contains(value)
      case f if f.contains("-") =>
        val parts = f.split("-")
        (parts.lift(0).flatMap(_.trim.toIntOption),
         parts.lift(1).flatMap(_.trim.toIntOption)) match
          case (Some(lo), Some(hi)) => value >= lo && value <= hi
          case _                    => false
      case f => f.trim.toIntOption.contains(value)
