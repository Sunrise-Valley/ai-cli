package agent.core

import zio.json.*

// ── LLM 消息协议 ──────────────────────────────────────────────────────────

enum Role derives JsonCodec:
  case System, User, Assistant, Tool

case class ToolCall(id: String, name: String, arguments: String) derives JsonCodec
case class ToolResult(toolCallId: String, content: String)       derives JsonCodec

case class Message(
  role: Role,
  content: Option[String]        = None,
  toolCalls: List[ToolCall]      = Nil,
  toolResult: Option[ToolResult] = None,
  reasoningContent: Option[String] = None,  // DeepSeek thinking mode 透传
) derives JsonCodec

// ── 工具定义（OpenAI function calling 格式）──────────────────────────────

case class ToolParam(
  `type`: String,
  description: String,
  required: Boolean = false,
) derives JsonCodec

case class ToolSchema(
  name: String,
  description: String,
  parameters: Map[String, ToolParam],
  required: List[String] = Nil,
) derives JsonCodec

// ── 任务树 ────────────────────────────────────────────────────────────────

enum TaskStatus derives JsonCodec:
  case Pending, Running, Done, Failed, Skipped

case class TaskId(value: String) derives JsonCodec

// LLM 解码用的中间结构，所有可选字段均为 Option
private case class AgentTaskRaw(
  id: TaskId,
  goal: String,
  status: Option[TaskStatus]    = None,
  children: Option[List[AgentTaskRaw]] = None,
  parentId: Option[TaskId]      = None,
  result: Option[String]        = None,
  attempts: Option[Int]         = None,
) derives JsonCodec:
  def toTask: AgentTask = AgentTask(
    id        = id,
    goal      = goal,
    status    = status.getOrElse(TaskStatus.Pending),
    children  = children.getOrElse(Nil).map(_.toTask),
    parentId  = parentId,
    result    = result,
    attempts  = attempts.getOrElse(0),
  )

case class AgentTask(
  id: TaskId,
  goal: String,
  status: TaskStatus             = TaskStatus.Pending,
  children: List[AgentTask]      = Nil,
  parentId: Option[TaskId]       = None,
  result: Option[String]         = None,
  attempts: Int                  = 0,
)

object AgentTask:
  // 宽松 decoder：LLM 返回的 JSON 中 status/attempts 等可能缺失
  given JsonDecoder[AgentTask] =
    JsonDecoder[AgentTaskRaw].map(_.toTask)
  given JsonEncoder[AgentTask] =
    JsonEncoder.derived[AgentTask]

// ── Plan（多路径，agent 自动选最优）──────────────────────────────────────

case class PlanPath(
  id: String,
  description: String,
  steps: List[String],
  confidence: Double,   // 0-1，agent 评估可行性
) derives JsonCodec

case class Plan(
  goal: String,
  paths: List[PlanPath],  // top-N 候选路径
  chosen: String,         // 自动选 confidence 最高的 path id
  taskTree: AgentTask,
) derives JsonCodec

// ── 记忆节点（树形） ──────────────────────────────────────────────────────

case class MemoryNode(
  id: Long            = 0,
  parentId: Option[Long],
  key: String,          // e.g. "code", "code/java", "code/java/spring"
  summary: String,
  content: String,
  createdAt: Long = System.currentTimeMillis(),
) derives JsonCodec

// ── Agent 运行上下文 ──────────────────────────────────────────────────────

case class AgentContext(
  sessionId: String,
  goal: String,
  messages: List[Message]  = Nil,
  currentPlan: Option[Plan]  = None,
)
