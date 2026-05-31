package agent.planner

import agent.core.{LLMClient, MemoryNode, Message, Role}
import agent.memory.MemoryStore
import zio.*
import zio.json.*

// ── 把 goal/result 写入树形记忆 ──────────────────────────────────────────
//
// 树形 key 示例：
//   "code"  →  "code/scala"  →  "code/scala/zio"
//   "file"  →  "file/read"
//   "web"   →  "web/search"
//
// 策略：
// 1. 用 LLM 从 goal 推断出最多 3 层的语义 key（如 "code/scala/concurrency"）
// 2. 沿路径逐层写入，父节点存摘要，叶节点存完整结果
// 3. 失败不中断主流程（always orElse ZIO.unit）

object MemoryIndexer:

  private val keyPrompt =
    """Given a task goal, output a hierarchical key path (max 3 levels) that categorizes this task.
      |
      |Rules:
      |- Use lowercase, English, slash-separated: "level1/level2/level3"
      |- Level 1: broad domain (code, file, web, data, system, text, math, other)
      |- Level 2: specific area (e.g. code/scala, file/read, web/search, data/json)
      |- Level 3 (optional): narrow topic (e.g. code/scala/concurrency, web/search/news)
      |- Be concise and consistent; prefer existing categories over new ones
      |- Output ONLY the key path, nothing else. Example: "code/scala/zio"
      |""".stripMargin

  def index(
    goal: String,
    result: String,
    scores: Option[DimScores],
    llm: LLMClient,
    memory: MemoryStore,
  ): Task[Unit] =
    (for
      key     <- inferKey(goal, llm)
      _       <- writeHierarchy(key, goal, result, scores, memory)
    yield ()).orElse(ZIO.unit)  // 记忆写入失败不影响主流程

  private def inferKey(goal: String, llm: LLMClient): Task[String] =
    val msg = Message(Role.User, Some(s"Task goal: $goal"))
    llm.chatRaw(List(msg), keyPrompt)
      .map(_.trim.toLowerCase.replaceAll("[^a-z0-9/]", "").take(60))
      .map(k => if k.isEmpty then "other" else k)

  private def writeHierarchy(
    key: String,
    goal: String,
    result: String,
    scores: Option[DimScores],
    memory: MemoryStore,
  ): Task[Unit] =
    val segments = key.split("/").scanLeft("")((acc, s) =>
      if acc.isEmpty then s else s"$acc/$s"
    ).drop(1).toList

    // 先查是否已有各层节点（避免重复创建父节点）
    ZIO.foldLeft(segments)(Option.empty[Long]) { (parentId, segKey) =>
      val isLeaf = segKey == segments.last
      val summary = if isLeaf then goal.take(120) else segKey.split("/").last
      val content = if isLeaf then buildContent(goal, result, scores) else s"Category: $segKey"

      // 先查同 key 的最新节点，存在则跳过父节点写入（叶节点总是新增）
      if isLeaf then
        memory.put(MemoryNode(
          parentId  = parentId,
          key       = segKey,
          summary   = summary,
          content   = content,
        )).map(id => Some(id))
      else
        // 父节点：用精确 key 查询，存在就复用，避免模糊匹配误判
        memory.path(segKey).flatMap { nodes =>
          nodes.find(_.key == segKey) match
            case Some(node) => ZIO.succeed(Some(node.id))
            case None       =>
              memory.put(MemoryNode(
                parentId = parentId,
                key      = segKey,
                summary  = summary,
                content  = content,
              )).map(id => Some(id))
        }
    }.unit

  // 直接以指定 key 写入，不经过 LLM key 推断（反省记录等场景使用）
  def indexRaw(key: String, summary: String, content: String, memory: MemoryStore): Task[Unit] =
    writeHierarchyRaw(key, summary, content, memory).orElse(ZIO.unit)

  private def writeHierarchyRaw(key: String, summary: String, content: String, memory: MemoryStore): Task[Unit] =
    val segments = key.split("/").scanLeft("")((acc, s) =>
      if acc.isEmpty then s else s"$acc/$s"
    ).drop(1).toList

    ZIO.foldLeft(segments)(Option.empty[Long]) { (parentId, segKey) =>
      val isLeaf    = segKey == segments.last
      val segSummary = if isLeaf then summary else segKey.split("/").last
      val segContent = if isLeaf then content  else s"Category: $segKey"
      if isLeaf then
        memory.put(MemoryNode(parentId = parentId, key = segKey, summary = segSummary, content = segContent)).map(id => Some(id))
      else
        memory.path(segKey).flatMap { nodes =>
          nodes.find(_.key == segKey) match
            case Some(node) => ZIO.succeed(Some(node.id))
            case None       => memory.put(MemoryNode(parentId = parentId, key = segKey, summary = segSummary, content = segContent)).map(id => Some(id))
        }
    }.unit

  private def buildContent(goal: String, result: String, scores: Option[DimScores]): String =
    val scoreStr = scores.map { s =>
      f"\nScores: result=${s.result.score}%.2f process=${s.process.score}%.2f " +
      f"quality=${s.quality.score}%.2f composite=${s.composite}%.2f"
    }.getOrElse("")
    s"Goal: $goal$scoreStr\n\nResult:\n${result.take(1500)}"
