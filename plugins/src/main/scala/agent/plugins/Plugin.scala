package agent.plugins

import agent.core.{ToolSchema, ToolParam}
import sttp.client3.SttpBackend
import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio.*
import zio.json.*

// ── Plugin 基类 ───────────────────────────────────────────────────────────

trait Plugin:
  def schema: ToolSchema
  def execute(args: Map[String, String]): Task[String]

// ── Plugin 注册表 ─────────────────────────────────────────────────────────

object PluginRegistry:
  private val _plugins = java.util.concurrent.CopyOnWriteArrayList[Plugin]()

  def register(p: Plugin): Unit =
    if !all.exists(_.schema.name == p.schema.name) then _plugins.add(p)

  def all: List[Plugin] = _plugins.toArray.toList.asInstanceOf[List[Plugin]]

  def schemas: List[ToolSchema] = all.map(_.schema)

  def dispatch(name: String, args: Map[String, String]): Task[String] =
    all.find(_.schema.name == name) match
      case Some(p) => p.execute(args)
      case None    => ZIO.fail(new IllegalArgumentException(s"Unknown plugin: $name"))

  // 用 ZIO Scoped 管理共享 HTTP backend 的生命周期
  def initBuiltinsScoped(config: PluginConfig): ZIO[Scope, Throwable, Unit] =
    ZIO.acquireRelease(HttpClientZioBackend())(_.close().ignore).map { backend =>
      register(WebSearchPlugin(config, backend))
      register(WebFetchPlugin(backend))
    }

case class PluginConfig(
  webSearchApiKey: String = "",
  webSearchEngine: String = "duckduckgo",
  webSearchBaseUrl: String = "",
)

object PluginConfig:
  val fromEnv: ZLayer[Any, Nothing, PluginConfig] = ZLayer.fromZIO(
    (for
      key    <- zio.System.envOrElse("WEB_SEARCH_API_KEY", "")
      engine <- zio.System.envOrElse("WEB_SEARCH_ENGINE", "duckduckgo")
      url    <- zio.System.envOrElse("WEB_SEARCH_BASE_URL", "")
    yield PluginConfig(key, engine, url)).orDie
  )
