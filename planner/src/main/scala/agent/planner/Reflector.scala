package agent.planner

import agent.core.{LLMClient, Message, Role}
import agent.memory.MemoryStore
import zio.*
import zio.json.*

// ── 反省结果 ──────────────────────────────────────────────────────────────

case class ReflectionResult(
  weakAreas:      List[String],   // 薄弱环节
  strengths:      List[String],   // 做得好的地方
  suggestions:    List[String],   // 下次改进建议
  overallQuality: Double,         // 0-1 综合质量
  summary:        String,         // 一段式总结
) derives JsonCodec

// ── Reflector service ─────────────────────────────────────────────────────

trait Reflector:
  def reflect(
    goal:        String,
    sessionId:   String,
    tasks:       Map[String, TaskRecord],
    finalResult: String,
  ): Task[ReflectionResult]

object Reflector:
  val layer: ZLayer[LLMClient & MemoryStore, Nothing, Reflector] =
    ZLayer.fromFunction(LLMReflector(_, _))

  def reflect(
    goal: String, sessionId: String,
    tasks: Map[String, TaskRecord], finalResult: String,
  ): ZIO[Reflector, Throwable, ReflectionResult] =
    ZIO.serviceWithZIO[Reflector](_.reflect(goal, sessionId, tasks, finalResult))

// ── LLM 驱动的反省实现 ────────────────────────────────────────────────────

private class LLMReflector(llm: LLMClient, memory: MemoryStore) extends Reflector:

  private val systemPrompt =
    """You are a self-reflection agent. Analyze a completed agent session and evaluate its performance.
      |
      |Given: session goal, all subtask results with 4-dim scores, and the final output.
      |
      |Produce a structured reflection:
      |1. weakAreas:   what went poorly or needs improvement (list of short strings)
      |2. strengths:   what was done well (list of short strings)
      |3. suggestions: concrete actionable improvements for next time (list of short strings)
      |4. overallQuality: 0.0-1.0 composite quality of the whole session
      |5. summary: 2-3 sentence paragraph summarising session quality and key takeaways
      |
      |Output JSON only (no markdown fences):
      |{
      |  "weakAreas": [...],
      |  "strengths": [...],
      |  "suggestions": [...],
      |  "overallQuality": 0.0,
      |  "summary": "..."
      |}""".stripMargin

  override def reflect(
    goal:        String,
    sessionId:   String,
    tasks:       Map[String, TaskRecord],
    finalResult: String,
  ): Task[ReflectionResult] =

    val taskLines = tasks.values.map { t =>
      val scoreStr = t.scores.map { s =>
        f"result=${s.result.score}%.2f process=${s.process.score}%.2f " +
        f"quality=${s.quality.score}%.2f composite=${s.composite}%.2f"
      }.getOrElse("no-scores")
      s"[${t.taskId}] ${t.goal.take(100)} | status=${t.status} attempts=${t.attempts} [$scoreStr]"
    }.mkString("\n")

    val userMsg = Message(Role.User, Some(
      s"""Session: $sessionId
         |Goal: $goal
         |
         |Subtask breakdown:
         |$taskLines
         |
         |Final result (truncated to 800 chars):
         |${finalResult.take(800)}""".stripMargin
    ))

    (for
      raw    <- llm.chatRaw(List(userMsg), systemPrompt)
      result <- ZIO.fromEither(stripJsonFence(raw).fromJson[ReflectionResult])
                  .mapError(e => RuntimeException(s"Reflection parse failed: $e\nRaw: $raw"))
      _      <- persist(goal, sessionId, result)
    yield result)
    .orElse(ZIO.succeed(fallback(tasks)))

  private def persist(goal: String, sessionId: String, r: ReflectionResult): Task[Unit] =
    val content =
      s"""Goal: $goal
         |Session: $sessionId
         |Quality: ${r.overallQuality}
         |
         |${r.summary}
         |
         |Weak areas: ${r.weakAreas.mkString(" | ")}
         |Strengths:  ${r.strengths.mkString(" | ")}
         |Next time:  ${r.suggestions.mkString(" | ")}""".stripMargin

    MemoryIndexer.indexRaw(
      key     = "reflection",
      summary = s"[$sessionId] ${goal.take(80)}",
      content = content,
      memory  = memory,
    )

  private def fallback(tasks: Map[String, TaskRecord]): ReflectionResult =
    val scores = tasks.values.flatMap(_.scores).toList
    val avg    = if scores.isEmpty then 0.5 else scores.map(_.composite).sum / scores.length
    ReflectionResult(Nil, Nil, Nil, avg, "Reflection unavailable (LLM call failed).")
