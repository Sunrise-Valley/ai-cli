package agent.planner

import agent.core.{LLMClient, Message, Role}
import zio.*
import zio.json.*

// ── 意图类型 ──────────────────────────────────────────────────────────────

enum IntentType derives JsonCodec:
  case Actionable     // 明确可执行（含错别字但意图清晰）
  case Ambiguous      // 意图可理解但缺少关键细节，自动补全
  case Conversational // 闲聊/问候/感谢，不需要 agent 执行
  case Junk           // 完全无法理解的输入

case class IntentResult(
  intentType: IntentType,
  normalizedGoal: String    = "",   // 纠错后的规范化目标
  assumptions: List[String] = Nil,  // Ambiguous 时补全的假设
  reply: Option[String]     = None, // Conversational/Junk 时的直接回复
) derives JsonCodec

// ── 服务接口 ──────────────────────────────────────────────────────────────

trait IntentAnalyzer:
  def analyze(input: String): Task[IntentResult]

object IntentAnalyzer:
  val layer: ZLayer[LLMClient, Nothing, IntentAnalyzer] =
    ZLayer.fromFunction(LLMIntentAnalyzer(_))

  def analyze(input: String): ZIO[IntentAnalyzer, Throwable, IntentResult] =
    ZIO.serviceWithZIO[IntentAnalyzer](_.analyze(input))

// ── LLM 实现 ─────────────────────────────────────────────────────────────

private class LLMIntentAnalyzer(llm: LLMClient) extends IntentAnalyzer:

  private val systemPrompt =
    """You are an intent analyzer for an AI agent CLI. Analyze the user's input and output JSON only.

Classify the input as exactly one of:
- "Actionable"     : A task or goal the agent can execute. Even with typos, if intent is clear → Actionable.
- "Ambiguous"      : Intent is understandable but missing key details (what? which? where?).
                     Fill in reasonable defaults — do NOT ask the user.
- "Conversational" : Greeting, thanks, question about the agent itself, or chit-chat.
                     No agent execution needed.
- "Junk"           : Completely unintelligible (random chars, gibberish, unrelated symbols).

Rules:
1. Fix typos and normalize the goal in `normalizedGoal` for Actionable and Ambiguous.
2. For Ambiguous: fill missing details with sensible defaults, list each assumption in `assumptions`.
3. For Conversational/Junk: write a brief helpful `reply` in the SAME LANGUAGE as the user's input.
4. Never ask for clarification — always make a decision and proceed.
5. `normalizedGoal` must be in the same language as the input.
6. Be LENIENT: prefer Actionable over Junk whenever possible.

Output strict JSON (no markdown fences, no extra text):
{
  "intentType": "Actionable|Ambiguous|Conversational|Junk",
  "normalizedGoal": "<corrected complete goal, empty string for Conversational/Junk>",
  "assumptions": ["<each assumption for Ambiguous, empty array otherwise>"],
  "reply": "<direct reply for Conversational/Junk, null otherwise>"
}""".stripMargin

  override def analyze(input: String): Task[IntentResult] =
    val msg = Message(Role.User, Some(s"User input: $input"))
    llm.chatRaw(List(msg), systemPrompt)
      .map(stripFence)
      .flatMap { json =>
        ZIO.fromEither(json.fromJson[IntentResult])
          .mapError(e => RuntimeException(s"Intent parse failed: $e\nRaw: $json"))
      }
      // 任何失败都降级为 Actionable，保证意图分析永不阻断主流程
      .orElse(ZIO.succeed(IntentResult(IntentType.Actionable, input)))

  private def stripFence(s: String): String =
    s.trim
     .stripPrefix("```json").stripPrefix("```")
     .stripSuffix("```")
     .trim
