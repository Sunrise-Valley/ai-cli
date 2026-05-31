package agent.planner

import agent.core.{AgentTask, TaskId, TaskStatus}
import zio.*
import zio.json.*
import java.io.{File, FileWriter, BufferedReader, FileReader}
import java.nio.file.{Files, Path, Paths}

// ── 单个 task 的持久化记录 ────────────────────────────────────────────────

case class TaskRecord(
  taskId: String,
  goal: String,
  status: TaskStatus,
  result: Option[String]    = None,
  scores: Option[DimScores] = None,
  attempts: Int             = 0,
  updatedAt: Long           = java.lang.System.currentTimeMillis(),
) derives JsonCodec

// ── session 级别的状态文件 ─────────────────────────────────────────────────

case class SessionState(
  sessionId: String,
  goal: String,
  startedAt: Long                   = java.lang.System.currentTimeMillis(),
  tasks: Map[String, TaskRecord]    = Map.empty,
  bestResult: Option[String]        = None,
) derives JsonCodec

// ── SessionStore service ──────────────────────────────────────────────────

trait SessionStore:
  def load(sessionId: String): Task[Option[SessionState]]
  def save(state: SessionState): Task[Unit]
  def taskDone(sessionId: String, taskId: String, result: String, scores: Option[DimScores]): Task[Unit]
  def taskFailed(sessionId: String, taskId: String, attempts: Int): Task[Unit]
  def setBest(sessionId: String, result: String): Task[Unit]

object SessionStore:
  val layer: ZLayer[Any, Nothing, SessionStore] =
    ZLayer.succeed(FileSessionStore())

  def load(sessionId: String): ZIO[SessionStore, Throwable, Option[SessionState]] =
    ZIO.serviceWithZIO[SessionStore](_.load(sessionId))

  def save(state: SessionState): ZIO[SessionStore, Throwable, Unit] =
    ZIO.serviceWithZIO[SessionStore](_.save(state))

  def taskDone(sessionId: String, taskId: String, result: String, scores: Option[DimScores]): ZIO[SessionStore, Throwable, Unit] =
    ZIO.serviceWithZIO[SessionStore](_.taskDone(sessionId, taskId, result, scores))

  def setBest(sessionId: String, result: String): ZIO[SessionStore, Throwable, Unit] =
    ZIO.serviceWithZIO[SessionStore](_.setBest(sessionId, result))

// ── 文件实现：~/.ai-agent/sessions/<sessionId>.json ───────────────────────

private class FileSessionStore extends SessionStore:

  private val baseDir = Paths.get(java.lang.System.getProperty("user.home"), ".ai-agent", "sessions")

  private def sessionFile(sessionId: String): Path =
    baseDir.resolve(s"$sessionId.json")

  private def ensureDir(): Unit =
    baseDir.toFile.mkdirs()

  def load(sessionId: String): Task[Option[SessionState]] =
    ZIO.attemptBlocking {
      val f = sessionFile(sessionId).toFile
      if !f.exists() then None
      else
        val raw = { val br = new BufferedReader(new FileReader(f)); try br.lines().toArray.mkString("\n") finally br.close() }
        raw.fromJson[SessionState] match
          case Right(s) => Some(s)
          case Left(e)  =>
            // 文件损坏时打印警告但不崩溃，视为无缓存
            println(s"[warn] Session file corrupted ($sessionId): $e")
            None
    }

  def save(state: SessionState): Task[Unit] =
    ZIO.attemptBlocking {
      ensureDir()
      val fw = new FileWriter(sessionFile(state.sessionId).toFile); try fw.write(state.toJson) finally fw.close()
    }.unit

  def taskDone(sessionId: String, taskId: String, result: String, scores: Option[DimScores]): Task[Unit] =
    update(sessionId) { s =>
      val rec = s.tasks.getOrElse(taskId, TaskRecord(taskId, "", TaskStatus.Pending))
        .copy(status = TaskStatus.Done, result = Some(result), scores = scores,
              updatedAt = java.lang.System.currentTimeMillis())
      s.copy(tasks = s.tasks + (taskId -> rec))
    }

  def taskFailed(sessionId: String, taskId: String, attempts: Int): Task[Unit] =
    update(sessionId) { s =>
      val rec = s.tasks.getOrElse(taskId, TaskRecord(taskId, "", TaskStatus.Pending))
        .copy(status = TaskStatus.Failed, attempts = attempts,
              updatedAt = java.lang.System.currentTimeMillis())
      s.copy(tasks = s.tasks + (taskId -> rec))
    }

  def setBest(sessionId: String, result: String): Task[Unit] =
    update(sessionId)(_.copy(bestResult = Some(result)))

  private def update(sessionId: String)(f: SessionState => SessionState): Task[Unit] =
    load(sessionId).flatMap {
      case Some(s) => save(f(s))
      case None    =>
        // session 文件不存在时创建一个最小记录，确保后续更新不静默丢失
        ZIO.attemptBlocking {
          ensureDir()
          val minimal = SessionState(sessionId, goal = "unknown")
          val fw2 = new FileWriter(sessionFile(sessionId).toFile); try fw2.write(f(minimal).toJson) finally fw2.close()
        }.unit.orElse(ZIO.unit)
    }
