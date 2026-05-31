package agent.planner

import agent.core.{AgentConfig, AgentTask, LLMClient, MemoryNode, Message, Role, ToolResult, ToolSchema, TaskStatus}
import agent.memory.MemoryStore
import agent.tools.{Tool, ToolDispatcher}
import zio.*
import zio.json.*
import zio.json.ast.Json
import java.util.UUID


type ExtraToolDef = (ToolSchema, Map[String, String] => Task[String])

// ── 状态输出接口 ──────────────────────────────────────────────────────────

trait StatusPrinter:
  def planning(msg: String): UIO[Unit]
  def executing(msg: String): UIO[Unit]
  def verified(msg: String): UIO[Unit]
  def warning(msg: String): UIO[Unit]
  def scores(s: DimScores): UIO[Unit]
  def intent(normalized: String, assumptions: List[String]): UIO[Unit]
  def reply(msg: String): UIO[Unit]
  def streamToken(token: String): UIO[Unit]  // 流式 token 输出（不换行）
  def streamEnd(): UIO[Unit]                  // 流式结束后换行

object StatusPrinter:
  val plain: StatusPrinter = new StatusPrinter:
    def planning(msg: String)  = ZIO.succeed(println(s"[plan] $msg"))
    def executing(msg: String) = ZIO.succeed(println(s"[exec] $msg"))
    def verified(msg: String)  = ZIO.succeed(println(s"[ok]   $msg"))
    def warning(msg: String)   = ZIO.succeed(println(s"[warn] $msg"))
    def scores(s: DimScores)   = ZIO.succeed(println(
      f"[score] result=${s.result.score}%.2f process=${s.process.score}%.2f " +
      f"quality=${s.quality.score}%.2f compliance=${s.compliance.score}%.2f " +
      f"composite=${s.composite}%.2f"
    ))
    def intent(normalized: String, assumptions: List[String]) =
      ZIO.succeed {
        println(s"[intent] Normalized: $normalized")
        assumptions.foreach(a => println(s"[intent]   · $a"))
      }
    def reply(msg: String)         = ZIO.succeed(println(s"[reply] $msg"))
    def streamToken(token: String) = ZIO.succeed(print(token))
    def streamEnd()                = ZIO.succeed(println())

// ── AgentExecutor ─────────────────────────────────────────────────────────

trait AgentExecutor:
  def run(goal: String): Task[String]
  def resume(sessionId: String): Task[String]

object AgentExecutor:
  def layer: ZLayer[LLMClient & Planner & Verifier & MemoryStore & SessionStore & ExecutionLogger & AgentConfig & IntentAnalyzer & Reflector, Nothing, AgentExecutor] =
    ZLayer.fromFunction(new DefaultAgentExecutor(_, _, _, _, _, _, _, StatusPrinter.plain, Nil, _, _))

  def layerWith(
    printer: StatusPrinter,
    extraTools: List[ExtraToolDef] = Nil,
  ): ZLayer[LLMClient & Planner & Verifier & MemoryStore & SessionStore & ExecutionLogger & AgentConfig & IntentAnalyzer & Reflector, Nothing, AgentExecutor] =
    ZLayer.fromFunction(new DefaultAgentExecutor(_, _, _, _, _, _, _, printer, extraTools, _, _))

  def run(goal: String): ZIO[AgentExecutor, Throwable, String] =
    ZIO.serviceWithZIO[AgentExecutor](_.run(goal))

  def resume(sessionId: String): ZIO[AgentExecutor, Throwable, String] =
    ZIO.serviceWithZIO[AgentExecutor](_.resume(sessionId))

// ── 执行引擎实现 ──────────────────────────────────────────────────────────

class DefaultAgentExecutor(
  llm: LLMClient,
  planner: Planner,
  verifier: Verifier,
  memory: MemoryStore,
  sessions: SessionStore,
  logger: ExecutionLogger,
  cfg: AgentConfig,
  printer: StatusPrinter,
  extraTools: List[ExtraToolDef],
  analyzer: IntentAnalyzer,
  reflector: Reflector,
) extends AgentExecutor:

  private val maxRetries       = cfg.maxRetries
  private val maxToolCalls     = cfg.maxToolCalls
  private val degradeThreshold = cfg.degradeThreshold

  override def run(goal: String): Task[String] =
    for
      intent <- analyzer.analyze(goal)
      result <- dispatchIntent(intent, goal)
    yield result

  // 根据意图类型分发：闲聊/无效直接回复，其余进入完整执行流程
  private def dispatchIntent(intent: IntentResult, rawGoal: String): Task[String] =
    intent.intentType match
      case IntentType.Conversational | IntentType.Junk =>
        val msg = intent.reply.getOrElse("我是一个 AI agent，可以帮你执行任务。请描述你想做什么。")
        printer.reply(msg).as(msg)

      case IntentType.Ambiguous =>
        val effectiveGoal = if intent.normalizedGoal.nonEmpty then intent.normalizedGoal else rawGoal
        printer.intent(effectiveGoal, intent.assumptions) *>
        executeGoal(effectiveGoal)

      case IntentType.Actionable =>
        val effectiveGoal = if intent.normalizedGoal.nonEmpty then intent.normalizedGoal else rawGoal
        // 只有在确实做了规范化时才提示，避免无意义的噪声
        val showNorm = effectiveGoal != rawGoal && effectiveGoal.nonEmpty
        (if showNorm then printer.intent(effectiveGoal, Nil) else ZIO.unit) *>
        executeGoal(effectiveGoal)

  private def executeGoal(goal: String): Task[String] =
    val sessionId = UUID.randomUUID().toString.take(12)
    val state = SessionState(sessionId, goal)
    for
      _       <- sessions.save(state)
      _       <- logger.log(LogEvent(sessionId = sessionId, eventType = LogEventType.SessionStart,
                   data = Map("goal" -> goal)))
      _       <- printer.planning(s"Session: $sessionId  Goal: $goal")
      memCtx  <- recallMemoryContext(goal)
      plan    <- planner.plan(goal, memCtx)
      chosen   = plan.paths.find(_.id == plan.chosen).map(_.description).getOrElse(plan.chosen)
      _       <- printer.planning(s"Chosen path: $chosen")
      _       <- printer.planning(s"Task tree:\n${describeTree(plan.taskTree, 0)}")
      result  <- executeTask(plan.taskTree, Nil, sessionId)
      _       <- MemoryIndexer.index(goal, result, None, llm, memory)
      _       <- runReflection(goal, sessionId, result)
    yield result

  private def runReflection(goal: String, sessionId: String, finalResult: String): UIO[Unit] =
    (for
      state <- sessions.load(sessionId)
      tasks  = state.map(_.tasks).getOrElse(Map.empty)
      r     <- reflector.reflect(goal, sessionId, tasks, finalResult)
      _     <- printer.planning(s"[reflect] quality=${f"${r.overallQuality}%.2f"}  ${r.summary.take(120)}")
      _     <- ZIO.when(r.weakAreas.nonEmpty)(printer.warning(s"[reflect] weak: ${r.weakAreas.mkString(" | ")}"))
      _     <- ZIO.when(r.suggestions.nonEmpty)(printer.planning(s"[reflect] next: ${r.suggestions.mkString(" | ")}"))
    yield ()).ignore

  private def recallMemoryContext(goal: String): Task[List[Message]] =
    memory.search(goal, limit = cfg.memoryRecallLimit)
      .map { nodes =>
        if nodes.isEmpty then Nil
        else
          val recall = nodes.map(n => s"[${n.key}] ${n.summary}: ${n.content.take(300)}").mkString("\n\n")
          List(Message(Role.System, Some(
            s"Relevant experience from past sessions:\n$recall\n\nUse this to inform your planning."
          )))
      }
      .orElse(ZIO.succeed(Nil))

  override def resume(sessionId: String): Task[String] =
    sessions.load(sessionId).flatMap {
      case None    => ZIO.fail(RuntimeException(s"Session not found: $sessionId"))
      case Some(s) =>
        printer.planning(s"Resuming session $sessionId: ${s.goal}") *>
        planner.plan(s.goal, Nil).flatMap { plan =>
          executeTask(plan.taskTree, Nil, sessionId, Some(s))
        }
    }

  private def executeTask(
    task: AgentTask,
    context: List[Message],
    sessionId: String,
    resumeState: Option[SessionState] = None,
  ): Task[String] =
    // 断点续跑：检查文件中 task 是否已 Done
    val cached = resumeState.flatMap(_.tasks.get(task.id.value))
    cached match
      case Some(rec) if rec.status == TaskStatus.Done =>
        printer.executing(s"[${task.id.value}] SKIP (cached): ${task.goal}") *>
        ZIO.succeed(rec.result.getOrElse(""))

      case _ =>
        for
          _      <- printer.executing(s"[${task.id.value}] ${task.goal}")
          _      <- logger.log(LogEvent(sessionId = sessionId, taskId = Some(task.id.value),
                      eventType = LogEventType.TaskStart, data = Map("goal" -> task.goal)))
          result <- if task.children.isEmpty then executeLeaf(task, context, sessionId)
                    else executeComposite(task, context, sessionId, resumeState)
        yield result

  private def executeLeaf(task: AgentTask, context: List[Message], sessionId: String): Task[String] =
    def attempt(attemptsLeft: Int, ctx: List[Message], bestScore: Double, bestResult: String): Task[String] =
      for
        execResult <- callLLMWithTools(task.goal, ctx, sessionId, task.id.value)
        verify     <- verifier.verify(task, execResult, ctx)
        _          <- printer.scores(verify.scores)
        result     <- handleVerifyResult(
                        task, ctx, sessionId, execResult, verify,
                        attemptsLeft, bestScore, bestResult
                      )
      yield result

    attempt(maxRetries, context, -1.0, "")

  private def handleVerifyResult(
    task: AgentTask,
    ctx: List[Message],
    sessionId: String,
    execResult: String,
    verify: VerifyResult,
    attemptsLeft: Int,
    bestScore: Double,
    bestResult: String,
  ): Task[String] =
    val composite = verify.scores.composite
    val newBestScore  = composite.max(bestScore)
    val newBestResult = if composite >= bestScore then execResult else bestResult

    if verify.passed then
      sessions.taskDone(sessionId, task.id.value, execResult, Some(verify.scores)) *>
      sessions.setBest(sessionId, execResult) *>
      logger.log(LogEvent(sessionId = sessionId, taskId = Some(task.id.value),
        eventType = LogEventType.TaskDone,
        data = Map("composite" -> f"${verify.scores.composite}%.2f"))) *>
      MemoryIndexer.index(task.goal, execResult, Some(verify.scores), llm, memory) *>
      printer.verified(task.goal).as(execResult)

    else if !verify.scores.compliance.passed then
      logger.log(LogEvent(sessionId = sessionId, taskId = Some(task.id.value),
        eventType = LogEventType.ComplianceVeto,
        data = Map("reason" -> verify.scores.compliance.reason.take(200)))) *>
      sessions.taskFailed(sessionId, task.id.value, maxRetries - attemptsLeft) *>
      ZIO.fail(RuntimeException(s"[COMPLIANCE VETO] ${task.goal}: ${verify.scores.compliance.reason}"))

    else if attemptsLeft > 0 then
      val degraded = bestScore > 0 && (composite - bestScore) < degradeThreshold
      if degraded then
        logger.log(LogEvent(sessionId = sessionId, taskId = Some(task.id.value),
          eventType = LogEventType.Degradation,
          data = Map("current" -> f"$composite%.2f", "best" -> f"$bestScore%.2f"))) *>
        printer.warning(
          f"Degradation detected (composite $composite%.2f vs best $bestScore%.2f). Rolling back to best result."
        ) *>
        sessions.taskDone(sessionId, task.id.value, newBestResult, None) *>
        ZIO.succeed(newBestResult)
      else
        logger.log(LogEvent(sessionId = sessionId, taskId = Some(task.id.value),
          eventType = LogEventType.Replan,
          data = Map("reason" -> verify.reason.take(200)))) *>
        printer.warning(s"Verification failed: ${verify.reason}. Replanning (${attemptsLeft} left)...") *>
        planner.replan(task, verify.reason, ctx).flatMap { newTask =>
          val newCtx = ctx :+ Message(Role.Assistant, Some(s"Previous attempt result: $execResult"))
          attempt2(newTask, newCtx, sessionId, attemptsLeft - 1, newBestScore, newBestResult)
        }

    else
      logger.log(LogEvent(sessionId = sessionId, taskId = Some(task.id.value),
        eventType = LogEventType.TaskFailed,
        data = Map("reason" -> "max retries exhausted"))) *>
      printer.warning(f"Max retries exhausted for '${task.goal}'. Using best result (score=$newBestScore%.2f).") *>
      sessions.taskDone(sessionId, task.id.value, newBestResult, None) *>
      ZIO.succeed(newBestResult)

  // 递归辅助：replan 后继续 attempt（使用新 task）
  private def attempt2(
    task: AgentTask,
    ctx: List[Message],
    sessionId: String,
    attemptsLeft: Int,
    bestScore: Double,
    bestResult: String,
  ): Task[String] =
    for
      execResult <- callLLMWithTools(task.goal, ctx, sessionId, task.id.value)
      verify     <- verifier.verify(task, execResult, ctx)
      _          <- printer.scores(verify.scores)
      result     <- handleVerifyResult(task, ctx, sessionId, execResult, verify,
                      attemptsLeft, bestScore, bestResult)
    yield result

  private def executeComposite(
    task: AgentTask,
    context: List[Message],
    sessionId: String,
    resumeState: Option[SessionState],
  ): Task[String] =
    // 多子任务并行执行：每个子任务各自拿父级 context，互不等待
    // 独立任务（搜索+写文件、多步分析）速度提升明显；
    // 强依赖场景 Planner 应将依赖任务嵌套为子树而非平铺兄弟节点
    ZIO.collectAllPar(
      task.children.map(child => executeTask(child, context, sessionId, resumeState))
    ).map(_.mkString("\n---\n"))

  private def callLLMWithTools(
    goal: String,
    context: List[Message],
    sessionId: String = "",
    taskId: String = "",
  ): Task[String] =
    val allSchemas = ToolDispatcher.schemas(extraTools)
    val userMsg    = Message(Role.User, Some(goal))
    // 进入 loop 前先对传入的 context 做层①② 裁剪
    val trimmedCtx = ContextManager.trimContext(context, cfg)

    def loop(messages: List[Message], depth: Int): Task[String] =
      if depth >= maxToolCalls then
        ZIO.fail(RuntimeException(s"Tool call depth exceeded $maxToolCalls — possible loop detected"))
      else
        // 层③ 活跃窗口裁剪：防止单任务多轮工具调用撑爆上下文
        val safeMessages = ContextManager.trimActiveWindow(messages, cfg)
        llm.chatStreaming(safeMessages, allSchemas, tok => printer.streamToken(tok)).flatMap { response =>
          if response.toolCalls.nonEmpty then
            ZIO.foreach(response.toolCalls) { tc =>
              val args   = parseToolArgs(tc.arguments)
              val argStr = tc.arguments.take(200)
              ToolDispatcher.dispatch(tc.name, args, extraTools)
                .tap(out => logger.log(LogEvent(sessionId = sessionId, taskId = Some(taskId),
                  eventType = LogEventType.ToolCall,
                  data = Map("tool" -> tc.name, "args" -> argStr, "result" -> out.take(300)))))
                .map(out => Message(Role.Tool, Some(out), toolResult = Some(ToolResult(tc.id, out))))
                .catchAll(e => ZIO.succeed(
                  Message(Role.Tool, Some(s"Error: ${e.getMessage}"),
                          toolResult = Some(ToolResult(tc.id, s"Error: ${e.getMessage}")))
                ))
            }.flatMap(toolResults => loop(messages :+ response :++ toolResults, depth + 1))
          else
            val result = response.content.getOrElse("")
            // 流式输出结束，补一个换行
            printer.streamEnd() *>
            logger.log(LogEvent(sessionId = sessionId, taskId = Some(taskId),
              eventType = LogEventType.LLMCall,
              data = Map("goal" -> goal.take(200), "response" -> result.take(400)))) *>
            ZIO.succeed(result)
        }

    loop(trimmedCtx :+ userMsg, 0)

  private def parseToolArgs(json: String): Map[String, String] =
    json.fromJson[Json] match
      case Right(Json.Obj(fields)) =>
        fields.map { case (k, v) =>
          k -> (v match
            case Json.Str(s)  => s
            case Json.Num(n)  => n.toString
            case Json.Bool(b) => b.toString
            case Json.Null    => ""
            case other        => other.toJson)
        }.toMap
      case _ => Map.empty

  private def describeTree(task: AgentTask, depth: Int): String =
    val indent = "  " * depth
    val line   = s"$indent- ${task.goal}"
    if task.children.isEmpty then line
    else line + "\n" + task.children.map(describeTree(_, depth + 1)).mkString("\n")
