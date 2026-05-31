package agent.cli

import agent.planner.{AgentExecutor, Scheduler, ScheduleEntry, StatusPrinter, DimScores}
import fansi.*
import org.jline.reader.{LineReader, LineReaderBuilder, UserInterruptException, EndOfFileException}
import org.jline.reader.impl.history.DefaultHistory
import org.jline.terminal.{Terminal, TerminalBuilder}
import zio.*
import zio.Unsafe
import java.io.{BufferedReader, FileReader}
import java.nio.file.{Files, Path, Paths}

object Tui:

  private val banner =
    Color.Cyan(Bold.On(Str(
      """
        | █████╗  ██╗      █████╗  ██████╗ ███████╗███╗   ██╗████████╗
        |██╔══██╗ ██║     ██╔══██╗██╔════╝ ██╔════╝████╗  ██║╚══██╔══╝
        |███████║ ██║     ███████║██║  ███╗█████╗  ██╔██╗ ██║   ██║
        |██╔══██║ ██║     ██╔══██║██║   ██║██╔══╝  ██║╚██╗██║   ██║
        |██║  ██║ ███████╗██║  ██║╚██████╔╝███████╗██║ ╚████║   ██║
        |╚═╝  ╚═╝ ╚══════╝╚═╝  ╚═╝ ╚═════╝ ╚══════╝╚═╝  ╚═══╝   ╚═╝
        |""".stripMargin
    ))).render

  private val helpText =
    s"""${Color.DarkGray("─" * 60)}
       |${Bold.On("Commands:")}
       |  ${Color.Yellow("/help")}                        Show this help
       |  ${Color.Yellow("/history")}                     Show this session's goal history
       |  ${Color.Yellow("/sessions")}                    List recent sessions (resume with --resume <id>)
       |  ${Color.Yellow("/schedule <cron> <goal>")}      Add a scheduled task
       |  ${Color.Yellow("/schedules")}                   List all scheduled tasks
       |  ${Color.Yellow("/unschedule <id>")}             Remove a scheduled task by ID
       |  ${Color.Yellow("/clear")}                       Clear screen
       |  ${Color.Yellow("/exit")}                        Quit  (also Ctrl+D)
       |${Color.DarkGray("─" * 60)}
       |${Bold.On("Cron format:")} min hour dom month dow
       |  ${Color.DarkGray("*/30 * * * *")}  every 30 min   ${Color.DarkGray("0 9 * * 1")}  every Monday 9am
       |  ${Color.DarkGray("0 */2 * * *")}   every 2 hours  ${Color.DarkGray("0 8 * * *")}  daily 8am
       |Type your goal and press Enter. The agent plans and executes autonomously.
       |${Color.DarkGray("To resume a crashed session: ./run.sh --resume <sessionId>")}
       |""".stripMargin

  private def prompt = Color.Cyan(Bold.On("> ")).render

  def run(): ZIO[AgentExecutor & Scheduler, Throwable, Unit] =
    for
      executor  <- ZIO.service[AgentExecutor]
      scheduler <- ZIO.service[Scheduler]
      _         <- scheduler.startBackground(executor, consolePrinter)
      _         <- ZIO.acquireReleaseWith(buildTerminal)(t => ZIO.succeed(t.close())) { terminal =>
                     ZIO.acquireReleaseWith(buildReader(terminal))(r => ZIO.succeed(r.getHistory.save())) { reader =>
                       ZIO.succeed(println(banner)) *>
                       ZIO.succeed(println(helpText)) *>
                       sessionLoop(reader)
                     }
                   }
    yield ()

  private val consolePrinter: StatusPrinter = new StatusPrinter:
    def planning(msg: String)  = Printer.planning(msg)
    def executing(msg: String) = Printer.executing(msg)
    def verified(msg: String)  = Printer.verified(msg)
    def warning(msg: String)   = Printer.warning(msg)
    def scores(s: DimScores)   = Printer.scores(s)
    def intent(n: String, a: List[String]) = Printer.intent(n, a)
    def reply(msg: String)         = Printer.reply(msg)
    def streamToken(tok: String)   = Printer.streamToken(tok)
    def streamEnd()                = Printer.streamEnd()

  private def sessionLoop(reader: LineReader): ZIO[AgentExecutor & Scheduler, Throwable, Unit] =
    ZIO.iterate(List.empty[String])(_ => true) { history =>
      readLine(reader).flatMap {
        case None        => ZIO.interrupt.as(history)
        case Some(input) =>
          val trimmed = input.trim
          if trimmed.isEmpty then ZIO.succeed(history)
          else trimmed match
            case "/exit" | "/quit" =>
              ZIO.succeed(println(Color.DarkGray("Goodbye.").render)) *> ZIO.interrupt.as(history)
            case "/help"      => ZIO.succeed(println(helpText)).as(history)
            case "/clear"     => ZIO.succeed(print("[2J[H")).as(history)
            case "/history"   => showHistory(history).as(history)
            case "/sessions"  => showSessions().as(history)
            case "/schedules" => showSchedules().as(history)
            case cmd if cmd.startsWith("/schedule ") =>
              addSchedule(cmd.drop(10).trim).as(history)
            case cmd if cmd.startsWith("/unschedule ") =>
              removeSchedule(cmd.drop(12).trim).as(history)
            case goal => executeGoal(goal).map(_ => history :+ goal)
      }
    }.unit.catchAll {
      case _: InterruptedException => ZIO.unit
      case e                       => Printer.error(e.getMessage)
    }

  private def readLine(reader: LineReader): UIO[Option[String]] =
    ZIO.attempt(Option(reader.readLine(prompt)))
      .catchSome {
        case _: UserInterruptException => ZIO.succeed(Some(""))
        case _: EndOfFileException     => ZIO.succeed(None)
      }
      .orElse(ZIO.succeed(None))

  private def executeGoal(goal: String): ZIO[AgentExecutor, Throwable, String] =
    printSeparator("Executing") *>
    withInterruptSupport(AgentExecutor.run(goal))
      .flatMap {
        case Some(result) =>
          ZIO.succeed(println(
            s"\n${Color.DarkGray("─" * 60)}\n${Bold.On("Result:")}\n$result\n${Color.DarkGray("─" * 60)}"
          )) *>
          showResumeHint() *>
          ZIO.succeed(result)
        case None =>
          Printer.warning("Task interrupted by Ctrl+C.").as("")
      }
      .catchAll { e =>
        Printer.error(e.getMessage).as(s"Error: ${e.getMessage}")
      }

  // 执行期间注册临时 SIGINT 处理器，Ctrl+C 可中断当前任务而不退出整个程序
  private def withInterruptSupport[A](
    effect: ZIO[AgentExecutor, Throwable, A]
  ): ZIO[AgentExecutor, Throwable, Option[A]] =
    Promise.make[Nothing, Unit].flatMap { ctrlC =>
      for
        fiber <- effect.map(Some(_)).fork

        oldHandler <- ZIO.succeed {
          try
            sun.misc.Signal.handle(
              new sun.misc.Signal("INT"),
              _ => Unsafe.unsafe { implicit u =>
                Runtime.default.unsafe.run(ctrlC.succeed(()).ignore)
              }
            )
          catch case _: Throwable => sun.misc.SignalHandler.SIG_DFL
        }

        result <- fiber.join.race(ctrlC.await.flatMap(_ =>
                    fiber.interrupt *> ZIO.succeed(None)
                  ))

        _ <- ZIO.succeed {
          try sun.misc.Signal.handle(new sun.misc.Signal("INT"), oldHandler)
          catch case _: Throwable => ()
        }
      yield result
    }

  private def showResumeHint(): UIO[Unit] =
    ZIO.succeed(println(
      Color.DarkGray("Tip: if the session was incomplete, use /sessions to find the session ID and resume it.").render
    ))

  private def showHistory(history: List[String]): UIO[Unit] =
    ZIO.succeed {
      if history.isEmpty then println(Color.DarkGray("No goals in this session yet.").render)
      else
        println(Color.DarkGray("─" * 60).render)
        history.zipWithIndex.foreach { case (goal, i) =>
          println(s"  ${Color.DarkGray(s"${i + 1}.")} $goal")
        }
        println(Color.DarkGray("─" * 60).render)
    }

  private def showSessions(): UIO[Unit] =
    ZIO.attemptBlocking {
      val dir = Paths.get(java.lang.System.getProperty("user.home"), ".ai-agent", "sessions")
      if !dir.toFile.exists() then
        println(Color.DarkGray("No sessions found.").render)
      else
        val files = dir.toFile.listFiles()
        if files == null || files.isEmpty then
          println(Color.DarkGray("No sessions found.").render)
        else
          val recent = files.sortBy(_.lastModified()).reverse.take(10)
          println(Color.DarkGray("─" * 60).render)
          println(s"${Bold.On("Recent sessions")} ${Color.DarkGray("(most recent first)")}")
          recent.foreach { f =>
            val sessionId = f.getName.stripSuffix(".json")
            val modified  = java.time.Instant.ofEpochMilli(f.lastModified())
              .atZone(java.time.ZoneId.systemDefault())
              .toLocalDateTime.toString.take(16)
            val goal = scala.util.Try {
              import zio.json.*
              import agent.planner.SessionState
              { val br = new BufferedReader(new FileReader(f)); try br.lines().toArray.mkString("\n") finally br.close() }
                .fromJson[SessionState].toOption.map(_.goal).getOrElse("?")
            }.getOrElse("?")
            println(s"  ${Color.Yellow(sessionId)}  ${Color.DarkGray(modified)}  $goal")
          }
          println(Color.DarkGray("─" * 60).render)
          println(Color.DarkGray("Resume: ./run.sh --resume <sessionId>").render)
    }.ignore

  // ── 调度相关命令 ──────────────────────────────────────────────────────────

  private def showSchedules(): ZIO[Scheduler, Throwable, Unit] =
    Scheduler.list().flatMap { entries =>
      ZIO.succeed {
        println(Color.DarkGray("─" * 60).render)
        if entries.isEmpty then
          println(Color.DarkGray("No scheduled tasks.").render)
        else
          println(s"${Bold.On("Scheduled tasks")}  ${Color.DarkGray("(background, fires automatically)")}")
          entries.foreach { e =>
            val status   = if e.enabled then Color.Green("enabled") else Color.DarkGray("disabled")
            val lastRun  = e.lastRunAt.map { ms =>
              java.time.Instant.ofEpochMilli(ms)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime.toString.take(16)
            }.getOrElse("never")
            println(s"  ${Color.Yellow(e.id)}  ${Color.Cyan(e.cronExpr)}  [$status]  last=$lastRun")
            println(s"         ${e.goal.take(80)}")
          }
        println(Color.DarkGray("─" * 60).render)
        println(Color.DarkGray("Remove with /unschedule <id>").render)
      }
    }.catchAll(e => Printer.error(e.getMessage))

  // /schedule <cron_5_fields> <goal>
  // e.g. /schedule */30 * * * * fetch latest news
  private def addSchedule(args: String): ZIO[Scheduler, Throwable, Unit] =
    // cron は 5 フィールド（スペース区切り）、その後が goal
    val tokens = args.split("\\s+", 6)
    if tokens.length < 6 then
      ZIO.succeed(println(
        Color.Red(
          "Usage: /schedule <min> <hour> <dom> <month> <dow> <goal>\n" +
          "Example: /schedule */30 * * * * fetch latest news"
        ).render
      ))
    else
      val cronExpr = tokens.take(5).mkString(" ")
      val goal     = tokens(5).trim
      Scheduler.add(cronExpr, goal).flatMap { entry =>
        ZIO.succeed(println(
          s"${Color.Green("Scheduled")} ${Color.Yellow(entry.id)}  " +
          s"${Color.Cyan(entry.cronExpr)}  ${entry.goal}"
        ))
      }.catchAll(e => Printer.error(e.getMessage))

  private def removeSchedule(id: String): ZIO[Scheduler, Throwable, Unit] =
    if id.isEmpty then
      ZIO.succeed(println(Color.Red("Usage: /unschedule <id>").render))
    else
      Scheduler.remove(id).flatMap { removed =>
        ZIO.succeed(
          if removed then println(Color.Green(s"Removed schedule $id").render)
          else println(Color.Red(s"Schedule '$id' not found.").render)
        )
      }.catchAll(e => Printer.error(e.getMessage))

  // ── 工具方法 ──────────────────────────────────────────────────────────────

  private def printSeparator(label: String): UIO[Unit] =
    ZIO.succeed(println(s"\n${Color.DarkGray("─" * 60)}\n${Color.Cyan(label)}\n${Color.DarkGray("─" * 60)}"))

  private def buildTerminal: Task[Terminal] =
    ZIO.attemptBlocking(
      TerminalBuilder.builder().system(true).dumb(false).build().nn
    )

  private def buildReader(terminal: Terminal): Task[LineReader] =
    ZIO.attemptBlocking(
      LineReaderBuilder.builder()
        .terminal(terminal)
        .history(DefaultHistory())
        .variable(LineReader.HISTORY_FILE,
          s"${java.lang.System.getProperty("user.home")}/.ai-agent/history")
        .option(LineReader.Option.AUTO_FRESH_LINE, true)
        .option(LineReader.Option.HISTORY_IGNORE_DUPS, true)
        .option(LineReader.Option.HISTORY_REDUCE_BLANKS, true)
        .build().nn
    )
