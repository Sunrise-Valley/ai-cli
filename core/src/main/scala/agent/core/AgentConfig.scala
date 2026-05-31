package agent.core

import zio.*
import zio.json.*
import java.io.{FileWriter, BufferedReader, FileReader}
import java.nio.file.{Files, Path, Paths}

// ── Agent 全局配置 ────────────────────────────────────────────────────────
// 读取顺序：~/.ai-agent/config.json > 环境变量 > 默认值

case class AgentConfig(
  // LLM
  model: String              = "gpt-4o",
  baseUrl: String            = "https://api.openai.com/v1",
  apiKey: String             = "",
  maxTokens: Option[Int]     = None,

  // 执行参数
  maxRetries: Int            = 3,
  maxToolCalls: Int          = 20,
  degradeThreshold: Double   = -0.10,  // composite 下降阈值触发熔断

  // 记忆
  memoryDb: String           = s"${java.lang.System.getProperty("user.home")}/.ai-agent/memory.db",
  memoryRecallLimit: Int     = 5,      // plan 时召回的相关记忆条数

  // 上下文管理
  ctxMaxChainMsgs: Int       = 8,    // 任务链摘要最多保留几条（热层）
  ctxMaxActiveMsgs: Int      = 20,   // 活跃窗口最多保留几条消息（热层上限）
  ctxActiveTrimTo: Int       = 12,   // 超出后收缩到该数量
  ctxResultPreview: Int      = 250,  // 任务链摘要中结果的最大字符数

  // 插件
  webSearchEngine: String    = "bing",
  webSearchApiKey: String    = "",
  webSearchBaseUrl: String   = "",
) derives JsonCodec

object AgentConfig:

  val configPath: Path =
    Paths.get(java.lang.System.getProperty("user.home"), ".ai-agent", "config.json")

  // 加载顺序：文件 > 环境变量 > 默认值
  val load: ZIO[Any, Nothing, AgentConfig] =
    for
      fileConfig  <- loadFromFile
      envConfig   <- loadFromEnv
    yield merge(fileConfig, envConfig)

  val layer: ZLayer[Any, Nothing, AgentConfig] =
    ZLayer.fromZIO(load)

  private def loadFromFile: ZIO[Any, Nothing, Option[AgentConfig]] =
    ZIO.attemptBlocking {
      if configPath.toFile.exists() then
        val br = new BufferedReader(new FileReader(configPath.toFile))
        val raw = try br.lines().toArray.mkString("\n") finally br.close()
        raw.fromJson[AgentConfig].toOption
      else None
    }.orElse(ZIO.succeed(None))

  private def loadFromEnv: ZIO[Any, Nothing, AgentConfig] =
    (for
      model    <- zio.System.envOrElse("LLM_MODEL",        "gpt-4o")
      baseUrl  <- zio.System.envOrElse("LLM_BASE_URL",     "https://api.openai.com/v1")
      apiKey   <- zio.System.envOrElse("LLM_API_KEY",      "")
      memDb    <- zio.System.envOrElse("AGENT_MEMORY_DB",  s"${java.lang.System.getProperty("user.home")}/.ai-agent/memory.db")
      wsEngine <- zio.System.envOrElse("WEB_SEARCH_ENGINE",  "bing")
      wsKey    <- zio.System.envOrElse("WEB_SEARCH_API_KEY", "")
      wsUrl    <- zio.System.envOrElse("WEB_SEARCH_BASE_URL","")
      maxTok   <- zio.System.env("LLM_MAX_TOKENS").map(_.flatMap(_.toIntOption))
    yield AgentConfig(
      model        = model,
      baseUrl      = baseUrl,
      apiKey       = apiKey,
      maxTokens    = maxTok,
      memoryDb     = memDb,
      webSearchEngine  = wsEngine,
      webSearchApiKey  = wsKey,
      webSearchBaseUrl = wsUrl,
    )).orDie

  // 文件配置优先，文件中没设置的字段用环境变量值
  private def merge(fileOpt: Option[AgentConfig], env: AgentConfig): AgentConfig =
    fileOpt match
      case None    => env
      case Some(f) => f.copy(
        // 只有文件里显式设了 apiKey 才用，否则保留环境变量（安全考虑）
        apiKey       = if f.apiKey.nonEmpty    then f.apiKey       else env.apiKey,
        webSearchApiKey = if f.webSearchApiKey.nonEmpty then f.webSearchApiKey else env.webSearchApiKey,
      )

  // 写默认配置模板（首次运行时），同时写一个说明文件
  def writeTemplate: ZIO[Any, Nothing, Unit] =
    ZIO.attemptBlocking {
      val dir = configPath.toFile.getParentFile.nn
      dir.mkdirs()

      if !configPath.toFile.exists() then
        // apiKey 故意留空，优先从环境变量 LLM_API_KEY 读取
        val fw1 = new FileWriter(configPath.toFile); try fw1.write(AgentConfig().toJsonPretty) finally fw1.close()

      // 写一份说明文件，解释配置优先级
      val readmePath = configPath.resolveSibling("CONFIG.md")
      if !readmePath.toFile.exists() then
        val fw2 = new FileWriter(readmePath.toFile)
        try fw2.write(
          s"""# AI Agent 配置说明
             |
             |## 配置文件优先级
             |1. `~/.ai-agent/config.json`（本文件同目录）
             |2. 环境变量（`.env` 文件或系统环境变量）
             |3. 内置默认值
             |
             |## 快速开始
             |1. 在 `config.json` 中设置 `model`、`baseUrl`
             |2. **API Key 建议通过环境变量设置**（安全起见不建议写入文件）：
             |   ```bash
             |   export LLM_API_KEY=your-key-here
             |   ```
             |   或复制项目根目录的 `.env.example` 为 `.env` 并填写
             |
             |## 主要配置项说明
             || 字段 | 说明 | 默认值 |
             ||------|------|--------|
             || model | LLM 模型名称 | gpt-4o |
             || baseUrl | OpenAI 兼容接口地址 | https://api.openai.com/v1 |
             || apiKey | API Key（建议用环境变量） | 空 |
             || maxRetries | 任务失败最大重试次数 | 3 |
             || maxToolCalls | 单次执行最大工具调用深度 | 20 |
             || degradeThreshold | 评分退化熔断阈值（负数） | -0.10 |
             || memoryRecallLimit | 规划时召回的历史记忆条数 | 5 |
             || webSearchEngine | 搜索引擎（duckduckgo/serper/searxng） | duckduckgo |
             |""".stripMargin
        ) finally fw2.close()
    }.ignore
