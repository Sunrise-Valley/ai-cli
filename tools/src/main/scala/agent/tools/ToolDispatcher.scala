package agent.tools

import agent.core.ToolSchema
import zio.*

// ── 统一工具调度（内置 Tool + 外部 Plugin）────────────────────────────────
// Plugin 通过 extraTools 动态注入，避免 tools 模块依赖 plugins 模块

object ToolDispatcher:

  def schemas(extraTools: List[(ToolSchema, Map[String, String] => IO[Throwable, String])] = Nil): List[ToolSchema] =
    Tool.schemas ++ extraTools.map(_._1)

  def dispatch(
    name: String,
    args: Map[String, String],
    extraTools: List[(ToolSchema, Map[String, String] => IO[Throwable, String])] = Nil,
  ): IO[Throwable, String] =
    Tool.all.find(_.schema.name == name) match
      case Some(t) => t.execute(args)
      case None    =>
        extraTools.find(_._1.name == name) match
          case Some((_, fn)) => fn(args)
          case None          => ZIO.fail(new IllegalArgumentException(s"Unknown tool: $name"))
