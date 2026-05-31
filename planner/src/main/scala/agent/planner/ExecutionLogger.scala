package agent.planner

import agent.core.{Message, Role}
import zio.*
import zio.json.*
import java.io.{FileWriter, BufferedWriter}
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

// ── 日志事件类型 ──────────────────────────────────────────────────────────

enum LogEventType derives JsonCodec:
  case SessionStart, TaskStart, TaskDone, TaskFailed
  case LLMCall, ToolCall, VerifyResult, Replan, Degradation, ComplianceVeto

case class LogEvent(
  ts: Long                      = java.lang.System.currentTimeMillis(),
  sessionId: String,
  taskId: Option[String]        = None,
  eventType: LogEventType,
  data: Map[String, String]     = Map.empty,
) derives JsonCodec

// ── ExecutionLogger service ───────────────────────────────────────────────

trait ExecutionLogger:
  def log(event: LogEvent): UIO[Unit]

object ExecutionLogger:
  // 默认实现：写到 ~/.ai-agent/logs/<sessionId>.jsonl
  val layer: ZLayer[Any, Nothing, ExecutionLogger] =
    ZLayer.succeed(FileExecutionLogger())

  // 便捷方法
  def log(event: LogEvent): URIO[ExecutionLogger, Unit] =
    ZIO.serviceWithZIO[ExecutionLogger](_.log(event))

  def sessionStart(sessionId: String, goal: String): URIO[ExecutionLogger, Unit] =
    log(LogEvent(sessionId = sessionId, eventType = LogEventType.SessionStart,
      data = Map("goal" -> goal)))

  def taskStart(sessionId: String, taskId: String, goal: String): URIO[ExecutionLogger, Unit] =
    log(LogEvent(sessionId = sessionId, taskId = Some(taskId),
      eventType = LogEventType.TaskStart, data = Map("goal" -> goal)))

  def taskDone(sessionId: String, taskId: String, composite: Double): URIO[ExecutionLogger, Unit] =
    log(LogEvent(sessionId = sessionId, taskId = Some(taskId),
      eventType = LogEventType.TaskDone,
      data = Map("composite" -> f"$composite%.2f")))

  def taskFailed(sessionId: String, taskId: String, reason: String): URIO[ExecutionLogger, Unit] =
    log(LogEvent(sessionId = sessionId, taskId = Some(taskId),
      eventType = LogEventType.TaskFailed, data = Map("reason" -> reason.take(200))))

  def llmCall(sessionId: String, taskId: String, prompt: String, response: String): URIO[ExecutionLogger, Unit] =
    log(LogEvent(sessionId = sessionId, taskId = Some(taskId),
      eventType = LogEventType.LLMCall,
      data = Map("prompt" -> prompt.take(500), "response" -> response.take(500))))

  def toolCall(sessionId: String, taskId: String, name: String, args: String, result: String): URIO[ExecutionLogger, Unit] =
    log(LogEvent(sessionId = sessionId, taskId = Some(taskId),
      eventType = LogEventType.ToolCall,
      data = Map("tool" -> name, "args" -> args.take(200), "result" -> result.take(300))))

  def verifyResult(sessionId: String, taskId: String, vr: agent.planner.VerifyResult): URIO[ExecutionLogger, Unit] =
    log(LogEvent(sessionId = sessionId, taskId = Some(taskId),
      eventType = LogEventType.VerifyResult,
      data = Map(
        "passed"      -> vr.passed.toString,
        "reason"      -> vr.reason.take(200),
        "composite"   -> f"${vr.scores.composite}%.2f",
        "compliance"  -> vr.scores.compliance.passed.toString,
      )))

  def replan(sessionId: String, taskId: String, reason: String): URIO[ExecutionLogger, Unit] =
    log(LogEvent(sessionId = sessionId, taskId = Some(taskId),
      eventType = LogEventType.Replan, data = Map("reason" -> reason.take(200))))

  def degradation(sessionId: String, taskId: String, current: Double, best: Double): URIO[ExecutionLogger, Unit] =
    log(LogEvent(sessionId = sessionId, taskId = Some(taskId),
      eventType = LogEventType.Degradation,
      data = Map("current" -> f"$current%.2f", "best" -> f"$best%.2f")))

  def complianceVeto(sessionId: String, taskId: String, reason: String): URIO[ExecutionLogger, Unit] =
    log(LogEvent(sessionId = sessionId, taskId = Some(taskId),
      eventType = LogEventType.ComplianceVeto, data = Map("reason" -> reason.take(200))))

// ── 文件实现（JSONL 追加写）──────────────────────────────────────────────

private class FileExecutionLogger extends ExecutionLogger:

  private val baseDir = Paths.get(java.lang.System.getProperty("user.home"), ".ai-agent", "logs")

  def log(event: LogEvent): UIO[Unit] =
    ZIO.attemptBlocking {
      baseDir.toFile.mkdirs()
      val file = baseDir.resolve(s"${event.sessionId}.jsonl").toFile
      val bw = new BufferedWriter(new FileWriter(file, true))
      try { bw.write(event.toJson + "\n") } finally bw.close()
    }.ignore  // 日志失败绝不影响主流程
