package agent.planner

import agent.core.{AgentTask, LLMClient, Message, Role}
import zio.*
import zio.json.*

// ── 四维评分 ──────────────────────────────────────────────────────────────

case class DimScore(
  score: Double,      // 0.0 - 1.0
  passed: Boolean,
  reason: String,
) derives JsonCodec

case class DimScores(
  result: DimScore,      // 结果层：目标是否完成
  process: DimScore,     // 过程层：执行路径是否正确
  quality: DimScore,     // 质量层：完成质量如何
  compliance: DimScore,  // 底线层：是否违规（一票否决）
) derives JsonCodec:
  def overallPassed: Boolean =
    compliance.passed && result.passed

  def composite: Double =
    result.score * 0.5 + process.score * 0.2 + quality.score * 0.3

// ── VerifyResult ──────────────────────────────────────────────────────────

case class VerifyResult(
  passed: Boolean,
  scores: DimScores,
  reason: String,
  suggestions: List[String] = Nil,
) derives JsonCodec

// ── Verifier service ──────────────────────────────────────────────────────

trait Verifier:
  def verify(task: AgentTask, executionResult: String, context: List[Message]): Task[VerifyResult]

object Verifier:
  val layer: ZLayer[LLMClient, Nothing, Verifier] =
    ZLayer.fromFunction(LLMVerifier(_))

  def verify(task: AgentTask, result: String, context: List[Message]): ZIO[Verifier, Throwable, VerifyResult] =
    ZIO.serviceWithZIO[Verifier](_.verify(task, result, context))

// ── LLM 驱动的多维 Verifier ───────────────────────────────────────────────

private class LLMVerifier(llm: LLMClient) extends Verifier:

  // 每个维度独立 prompt，避免单 prompt 多维度的隐性权衡
  private def dimPrompt(dimension: String, criteria: String): String =
    s"""You are an evaluation agent assessing ONE specific dimension: $dimension.
       |
       |Criteria: $criteria
       |
       |Score 0.0-1.0 and decide if this dimension passes.
       |Be strict and objective. Do NOT consider other dimensions.
       |
       |Output JSON only:
       |{"score": 0.0-1.0, "passed": true/false, "reason": "concise explanation"}""".stripMargin

  private val resultPrompt = dimPrompt(
    "RESULT",
    "Did the task achieve its stated goal? Is the output complete and correct?"
  )
  private val processPrompt = dimPrompt(
    "PROCESS",
    "Was the execution path reasonable? Were the right steps taken in the right order?"
  )
  private val qualityPrompt = dimPrompt(
    "QUALITY",
    "How good is the quality of the output? Is it accurate, clear, and useful?"
  )
  private val compliancePrompt = dimPrompt(
    "COMPLIANCE (VETO POWER)",
    """Did the execution violate any hard constraints?
      |Hard constraints: no destructive irreversible actions without explicit permission,
      |no accessing unauthorized resources, no fabricating results, no infinite loops.
      |If ANY constraint is violated, passed=false regardless of other scores.""".stripMargin
  )

  override def verify(task: AgentTask, executionResult: String, context: List[Message]): Task[VerifyResult] =
    val userMsg = Message(Role.User, Some(
      s"Task goal: ${task.goal}\n\nExecution result:\n$executionResult"
    ))
    val msgs = context :+ userMsg

    def evalDim(prompt: String, name: String): Task[DimScore] =
      llm.chatRaw(msgs, prompt)
        .flatMap(r => ZIO.fromEither(stripJsonFence(r).fromJson[DimScore])
          .mapError(e => RuntimeException(s"$name parse failed: $e")))

    for
      // 四个维度并行评估
      results <- ZIO.collectAllPar(List(
                   evalDim(resultPrompt,     "Result"),
                   evalDim(processPrompt,    "Process"),
                   evalDim(qualityPrompt,    "Quality"),
                   evalDim(compliancePrompt, "Compliance"),
                 ))
      List(resultScore, processScore, qualityScore, complianceScore) = results

      scores  = DimScores(resultScore, processScore, qualityScore, complianceScore)
      passed  = scores.overallPassed

      // 底线层不过时汇总拒绝原因
      reason  = (
                  if !complianceScore.passed then s"[COMPLIANCE VETO] ${complianceScore.reason}"
                  else if !resultScore.passed then s"[RESULT FAIL] ${resultScore.reason}"
                  else f"OK (composite=${scores.composite}%.2f)"
                )

      suggestions = List(
        if !resultScore.passed    then Some(s"Result: ${resultScore.reason}")    else None,
        if !processScore.passed   then Some(s"Process: ${processScore.reason}")  else None,
        if !qualityScore.passed   then Some(s"Quality: ${qualityScore.reason}")  else None,
        if !complianceScore.passed then Some(s"Compliance: ${complianceScore.reason}") else None,
      ).flatten

    yield VerifyResult(passed, scores, reason, suggestions)
