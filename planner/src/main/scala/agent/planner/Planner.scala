package agent.planner

import agent.core.{AgentTask, LLMClient, Message, MemoryNode, Plan, Role, TaskId}
import agent.memory.MemoryStore
import zio.*
import zio.json.*
import java.util.UUID

private def stripJsonFence(s: String): String =
  s.trim
   .stripPrefix("```json").stripPrefix("```")
   .stripSuffix("```")
   .trim

// ── Planner service ───────────────────────────────────────────────────────

trait Planner:
  def plan(goal: String, context: List[Message]): Task[Plan]
  def replan(task: AgentTask, reason: String, context: List[Message]): Task[AgentTask]

object Planner:
  val layer: ZLayer[LLMClient & MemoryStore, Nothing, Planner] =
    ZLayer.fromFunction(LLMPlanner(_, _))

  def plan(goal: String, context: List[Message]): ZIO[Planner, Throwable, Plan] =
    ZIO.serviceWithZIO[Planner](_.plan(goal, context))

  def replan(task: AgentTask, reason: String, context: List[Message]): ZIO[Planner, Throwable, AgentTask] =
    ZIO.serviceWithZIO[Planner](_.replan(task, reason, context))

// ── LLM 驱动的 Planner ────────────────────────────────────────────────────

private class LLMPlanner(llm: LLMClient, memory: MemoryStore) extends Planner:

  private val planSystemPrompt =
    """You are a planning agent. Given a goal, output a JSON plan with multiple candidate paths.
      |
      |Rules:
      |- Generate 3-5 candidate paths with different approaches
      |- Each path has confidence (0.0-1.0) reflecting feasibility
      |- Break the best path into a task tree; tasks can have sub-tasks (children)
      |- Be concrete and actionable
      |
      |AgentTask fields (ONLY include these — other fields have defaults):
      |  id: {"value": "<string>"}   — unique short id like "t1", "t2.1"
      |  goal: "<string>"             — what this task should accomplish
      |  children: [...]              — sub-tasks (empty array for leaf tasks)
      |
      |Output JSON matching this schema exactly:
      |{
      |  "goal": "...",
      |  "paths": [
      |    {"id": "p1", "description": "approach description", "steps": ["step1", "step2"], "confidence": 0.9}
      |  ],
      |  "chosen": "p1",
      |  "taskTree": {
      |    "id": {"value": "root"}, "goal": "...", "children": [
      |      {"id": {"value": "t1"}, "goal": "...", "children": []},
      |      {"id": {"value": "t2"}, "goal": "...", "children": [
      |        {"id": {"value": "t2.1"}, "goal": "...", "children": []}
      |      ]}
      |    ]
      |  }
      |}
      |
      |Auto-select the path with highest confidence as "chosen".
      |Output ONLY the JSON, no explanation, no markdown fences.""".stripMargin

  private val replanSystemPrompt =
    """You are a replanning agent. A task has failed or is blocked.
      |Given the failed task and reason, output a revised task tree as JSON.
      |
      |Output JSON:
      |{
      |  "id": {"value": "..."},
      |  "goal": "...",
      |  "children": [...]
      |}
      |
      |Output ONLY the JSON, no markdown fences.""".stripMargin

  override def plan(goal: String, context: List[Message]): Task[Plan] =
    for
      memCtx  <- memory.search(goal, limit = 5).map(nodes =>
                   if nodes.isEmpty then ""
                   else "\n\nRelevant memory:\n" + nodes.map(n => s"[${n.key}] ${n.summary}").mkString("\n")
                 ).orElse(ZIO.succeed(""))
      userMsg  = Message(Role.User, Some(s"Goal: $goal$memCtx"))
      response <- llm.chatRaw(context :+ userMsg, planSystemPrompt)
      plan     <- ZIO.fromEither(stripJsonFence(response).fromJson[Plan])
                    .mapError(e => new RuntimeException(s"Plan parse failed: $e\nRaw: $response"))
      bestPath  = plan.paths.maxByOption(_.confidence).map(_.id).getOrElse(plan.chosen)
      finalPlan = plan.copy(chosen = bestPath, taskTree = assignIds(plan.taskTree))
    yield finalPlan

  override def replan(task: AgentTask, reason: String, context: List[Message]): Task[AgentTask] =
    val userMsg = Message(Role.User, Some(
      s"Failed task: ${task.goal}\nReason: $reason\nAttempts: ${task.attempts}"
    ))
    for
      response <- llm.chatRaw(context :+ userMsg, replanSystemPrompt)
      newTask  <- ZIO.fromEither(stripJsonFence(response).fromJson[AgentTask])
                    .mapError(e => new RuntimeException(s"Replan parse failed: $e\nRaw: $response"))
      result    = assignIds(newTask).copy(parentId = task.parentId, attempts = task.attempts + 1)
    yield result

  private def assignIds(task: AgentTask): AgentTask =
    val id = if task.id.value.nonEmpty then task.id else TaskId(UUID.randomUUID().toString.take(8))
    task.copy(
      id       = id,
      children = task.children.map(assignIds),
    )
