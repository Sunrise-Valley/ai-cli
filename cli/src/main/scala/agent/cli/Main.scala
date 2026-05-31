package agent.cli

import agent.core.{LLMClient, LLMConfig, AgentConfig}
import agent.memory.{MemoryStore, MemoryConfig}
import agent.planner.{Planner, Verifier, AgentExecutor, SessionStore, ExecutionLogger, StatusPrinter, DimScores, IntentAnalyzer, Reflector, Scheduler}
import agent.plugins.{PluginConfig, PluginRegistry}
import zio.*

object Main extends ZIOAppDefault:

  override def run: ZIO[ZIOAppArgs, Any, Any] =
    AgentConfig.writeTemplate *>
    ZIOAppArgs.getArgs.flatMap { args =>
      val argList   = args.toList
      val resumeIdx = argList.indexOf("--resume")
      val noTui     = argList.contains("--no-tui")
      val cleanArgs = argList.filterNot(_ == "--no-tui")

      val program: ZIO[AgentExecutor & Scheduler, Throwable, Unit] =
        if resumeIdx >= 0 then
          cleanArgs.lift(resumeIdx + 1) match
            case Some(sessionId) => resumeSession(sessionId)
            case None            => Printer.error("--resume requires a session ID")
        else
          val goalArgs = cleanArgs.filterNot(_ == "--resume")
          goalArgs match
            case goal :: _ => runOnce(goal)
            case Nil       => if noTui then runBasicRepl() else Tui.run()

      program.provide(appLayer)
    }

  def runOnce(goal: String): ZIO[AgentExecutor, Throwable, Unit] =
    AgentExecutor.run(goal).flatMap { result =>
      Printer.plain("") *> Printer.result(s"=== Result ===\n$result")
    }

  private def resumeSession(sessionId: String): ZIO[AgentExecutor, Throwable, Unit] =
    AgentExecutor.resume(sessionId).flatMap { result =>
      Printer.plain("") *> Printer.result(s"=== Resumed Result ===\n$result")
    }

  private def runBasicRepl(): ZIO[AgentExecutor, Throwable, Unit] =
    Printer.plain("AI Agent (basic mode, type 'exit' to quit)") *>
    ZIO.iterate(())(_ => true) { _ =>
      for
        _     <- ZIO.succeed(print("\n> "))
        input <- ZIO.attemptBlocking(scala.io.StdIn.readLine().nn.trim)
        _     <- (
                   if input.toLowerCase == "exit" || input.toLowerCase == "quit" then
                     Printer.plain("Bye.") *> ZIO.interrupt
                   else if input.isEmpty then ZIO.unit
                   else runOnce(input).catchAll(e => Printer.error(e.getMessage))
                 )
      yield ()
    }.unit.catchAll {
      case _: InterruptedException => ZIO.unit
      case e                       => Printer.error(e.getMessage)
    }

  private val fansiPrinter: StatusPrinter = new StatusPrinter:
    def planning(msg: String)  = Printer.planning(msg)
    def executing(msg: String) = Printer.executing(msg)
    def verified(msg: String)  = Printer.verified(msg)
    def warning(msg: String)   = Printer.warning(msg)
    def scores(s: DimScores)   = Printer.scores(s)
    def intent(normalized: String, assumptions: List[String]) =
      Printer.intent(normalized, assumptions)
    def reply(msg: String)         = Printer.reply(msg)
    def streamToken(token: String) = Printer.streamToken(token)
    def streamEnd()                = Printer.streamEnd()

  private val appLayer: ZLayer[Any, Throwable, AgentExecutor & Scheduler] =
    val cfgLayer = AgentConfig.layer

    val llmLayer = cfgLayer >>> ZLayer.fromZIO(
      ZIO.serviceWith[AgentConfig](c => LLMConfig(c.baseUrl, c.apiKey, c.model, c.maxTokens))
    ) >>> LLMClient.layer

    val memoryLayer = cfgLayer >>> ZLayer.fromZIO(
      ZIO.serviceWith[AgentConfig](c => MemoryConfig(c.memoryDb))
    ) >>> MemoryStore.layer

    val pluginCfgLayer = cfgLayer >>> ZLayer.fromZIO(
      ZIO.serviceWith[AgentConfig](c => PluginConfig(c.webSearchApiKey, c.webSearchEngine, c.webSearchBaseUrl))
    )

    val plannerLayer   = llmLayer ++ memoryLayer >>> Planner.layer
    val verifierLayer  = llmLayer >>> Verifier.layer
    val sessionLayer   = SessionStore.layer
    val loggerLayer    = ExecutionLogger.layer
    val intentLayer    = llmLayer >>> IntentAnalyzer.layer
    val reflectorLayer = llmLayer ++ memoryLayer >>> Reflector.layer
    val schedulerLayer = Scheduler.layer

    val executorLayer =
      (llmLayer ++ plannerLayer ++ verifierLayer ++ memoryLayer ++
       sessionLayer ++ loggerLayer ++ cfgLayer ++ pluginCfgLayer ++ intentLayer ++ reflectorLayer) >>>
      ZLayer.scoped(
        for
          llm       <- ZIO.service[LLMClient]
          planner   <- ZIO.service[Planner]
          verifier  <- ZIO.service[Verifier]
          mem       <- ZIO.service[MemoryStore]
          sessions  <- ZIO.service[SessionStore]
          logger    <- ZIO.service[ExecutionLogger]
          agentCfg  <- ZIO.service[AgentConfig]
          pluginCfg <- ZIO.service[PluginConfig]
          analyzer  <- ZIO.service[IntentAnalyzer]
          reflector <- ZIO.service[Reflector]
          _         <- PluginRegistry.initBuiltinsScoped(pluginCfg)
          extras     = PluginRegistry.all.map(p => (p.schema, (args: Map[String, String]) => p.execute(args)))
        yield new agent.planner.DefaultAgentExecutor(
          llm, planner, verifier, mem, sessions, logger, agentCfg, fansiPrinter, extras, analyzer, reflector)
      )

    executorLayer ++ schedulerLayer
