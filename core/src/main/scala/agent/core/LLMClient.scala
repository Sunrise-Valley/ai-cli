package agent.core

import sttp.capabilities.zio.ZioStreams
import sttp.client3.*
import sttp.client3.ziojson.*
import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio.*
import zio.json.*
import zio.stream.*

// ── OpenAI 兼容接口请求/响应 ──────────────────────────────────────────────

private case class OAIMessage(
  role: String,
  content: Option[String]               = None,
  tool_calls: Option[List[OAIToolCall]] = None,
  tool_call_id: Option[String]          = None,
  reasoning_content: Option[String]     = None,  // DeepSeek thinking mode
) derives JsonCodec

private case class OAIToolCall(
  id: String,
  `type`: String = "function",
  function: OAIFunction,
) derives JsonCodec

private case class OAIFunction(name: String, arguments: String) derives JsonCodec

private case class OAITool(
  `type`: String = "function",
  function: OAIToolDef,
) derives JsonCodec

private case class OAIToolDef(
  name: String,
  description: String,
  parameters: zio.json.ast.Json,
) derives JsonCodec

private case class ChatRequest(
  model: String,
  messages: List[OAIMessage],
  tools: Option[List[OAITool]] = None,
  tool_choice: Option[String]  = None,
  temperature: Double          = 0.2,
  max_tokens: Option[Int]      = None,
  stream: Option[Boolean]      = None,
) derives JsonEncoder

private case class Choice(
  index: Int,
  message: OAIMessage,
  finish_reason: String,
) derives JsonDecoder

private case class ChatResponse(
  id: String,
  choices: List[Choice],
) derives JsonDecoder

// ── SSE 流式解析用数据结构 ─────────────────────────────────────────────────

private case class SSEFunction(
  name: Option[String]      = None,
  arguments: Option[String] = None,
) derives JsonDecoder

private case class SSEToolCall(
  index: Int                      = 0,
  id: Option[String]              = None,
  `type`: Option[String]          = None,
  function: Option[SSEFunction]   = None,
) derives JsonDecoder

private case class SSEDelta(
  content: Option[String]              = None,
  tool_calls: Option[List[SSEToolCall]] = None,
  reasoning_content: Option[String]    = None,
) derives JsonDecoder

private case class SSEChoice(
  delta: SSEDelta,
  finish_reason: Option[String] = None,
) derives JsonDecoder

private case class SSEChunk(
  choices: List[SSEChoice] = Nil,
) derives JsonDecoder

// ── LLMClient service ─────────────────────────────────────────────────────

trait LLMClient:
  def chat(messages: List[Message], tools: List[ToolSchema] = Nil): Task[Message]
  def chatRaw(messages: List[Message], systemPrompt: String): Task[String]
  // 流式版本：边生成边通过 onToken 回调推送 token，返回完整 Message
  def chatStreaming(
    messages: List[Message],
    tools: List[ToolSchema]     = Nil,
    onToken: String => UIO[Unit] = _ => ZIO.unit,
  ): Task[Message]

object LLMClient:
  val layer: ZLayer[LLMConfig, Throwable, LLMClient] =
    ZLayer.scoped(
      for
        cfg     <- ZIO.service[LLMConfig]
        backend <- HttpClientZioBackend.scoped()
      yield OpenAIClient(cfg, backend)
    )

  def chat(messages: List[Message], tools: List[ToolSchema] = Nil): ZIO[LLMClient, Throwable, Message] =
    ZIO.serviceWithZIO[LLMClient](_.chat(messages, tools))

  def chatRaw(messages: List[Message], systemPrompt: String): ZIO[LLMClient, Throwable, String] =
    ZIO.serviceWithZIO[LLMClient](_.chatRaw(messages, systemPrompt))

// ── 配置 ──────────────────────────────────────────────────────────────────

case class LLMConfig(
  baseUrl: String,
  apiKey: String,
  model: String,
  maxTokens: Option[Int] = None,
)

object LLMConfig:
  val fromEnv: ZLayer[Any, Nothing, LLMConfig] = ZLayer.fromZIO(
    (for
      url   <- System.envOrElse("LLM_BASE_URL", "https://api.openai.com/v1")
      key   <- System.envOrElse("LLM_API_KEY", "")
      model <- System.envOrElse("LLM_MODEL", "gpt-4o")
    yield LLMConfig(url, key, model)).orDie
  )

// ── OpenAI 兼容实现 ───────────────────────────────────────────────────────

private class OpenAIClient(cfg: LLMConfig, backend: SttpBackend[Task, ZioStreams]) extends LLMClient:

  private def isRetryable(e: Throwable): Boolean =
    val msg = Option(e.getMessage).getOrElse("")
    e.isInstanceOf[java.io.IOException]       ||
    e.isInstanceOf[java.net.ConnectException]  ||
    msg.contains("429") ||
    msg.contains("500") || msg.contains("502") ||
    msg.contains("503") || msg.contains("504")

  private val retrySchedule =
    Schedule.recurWhile[Throwable](isRetryable) &&
    Schedule.exponential(1.second)              &&
    Schedule.recurs(3)

  private def toOAI(m: Message): OAIMessage = m match
    case Message(Role.Tool, _, _, Some(tr), _) =>
      OAIMessage("tool", m.content, tool_call_id = Some(tr.toolCallId))
    case Message(Role.Assistant, content, tcs, _, rc) =>
      val calls = if tcs.isEmpty then None
                  else Some(tcs.map(tc => OAIToolCall(tc.id, function = OAIFunction(tc.name, tc.arguments))))
      OAIMessage("assistant", content, calls, reasoning_content = rc)
    case Message(role, content, _, _, _) =>
      OAIMessage(role.toString.toLowerCase, content)

  private def fromOAI(m: OAIMessage): Message =
    val tcs = m.tool_calls.getOrElse(Nil).map(tc => ToolCall(tc.id, tc.function.name, tc.function.arguments))
    Message(
      role             = m.role match
        case "assistant" => Role.Assistant
        case "user"      => Role.User
        case "system"    => Role.System
        case _           => Role.Tool,
      content          = m.content,
      toolCalls        = tcs,
      reasoningContent = m.reasoning_content,
    )

  override def chat(messages: List[Message], tools: List[ToolSchema]): Task[Message] =
    val req = ChatRequest(
      model      = cfg.model,
      messages   = messages.map(toOAI),
      tools      = if tools.isEmpty then None else Some(tools.map(schemaToOAI)),
      max_tokens = cfg.maxTokens,
    )
    val request = basicRequest
      .post(uri"${cfg.baseUrl}/chat/completions")
      .header("Authorization", s"Bearer ${cfg.apiKey}")
      .contentType("application/json")
      .body(req.toJson)
      .response(asJson[ChatResponse])

    val attempt = backend.send(request).flatMap { resp =>
      ZIO.fromEither(resp.body)
        .mapError(e => new RuntimeException(s"LLM error: $e"))
        .flatMap { r =>
          ZIO.fromOption(r.choices.headOption)
            .mapError(_ => new RuntimeException("LLM returned empty choices"))
            .map(c => fromOAI(c.message))
        }
    }

    attempt.retry(retrySchedule)

  override def chatRaw(messages: List[Message], systemPrompt: String): Task[String] =
    val sys = Message(Role.System, Some(systemPrompt))
    chat(sys :: messages).map(_.content.getOrElse(""))

  // ── 流式实现 ─────────────────────────────────────────────────────────────

  override def chatStreaming(
    messages: List[Message],
    tools: List[ToolSchema],
    onToken: String => UIO[Unit],
  ): Task[Message] =
    val req = ChatRequest(
      model      = cfg.model,
      messages   = messages.map(toOAI),
      tools      = if tools.isEmpty then None else Some(tools.map(schemaToOAI)),
      max_tokens = cfg.maxTokens,
      stream     = Some(true),
    )
    val request = basicRequest
      .post(uri"${cfg.baseUrl}/chat/completions")
      .header("Authorization", s"Bearer ${cfg.apiKey}")
      .contentType("application/json")
      .body(req.toJson)
      .response(asStreamUnsafe(ZioStreams))

    val attempt = backend.send(request).flatMap { resp =>
      resp.body match
        case Left(err)   => ZIO.fail(RuntimeException(s"LLM stream error: $err"))
        case Right(stream) => parseSSEStream(stream, tools.nonEmpty, onToken)
    }

    attempt.retry(retrySchedule)

  // SSE 流解析：边推 token 边积累完整 Message
  private def parseSSEStream(
    stream: ZStream[Any, Throwable, Byte],
    hasTools: Boolean,
    onToken: String => UIO[Unit],
  ): Task[Message] =
    case class TcAccum(id: String, name: String, args: StringBuilder)

    val contentBuf   = new StringBuilder
    val reasoningBuf = new StringBuilder  // DeepSeek reasoning_content 透传
    val tcMap        = scala.collection.mutable.Map.empty[Int, TcAccum]

    stream
      .via(ZPipeline.utf8Decode >>> ZPipeline.splitLines)
      .filter(_.startsWith("data: "))
      .map(_.stripPrefix("data: ").trim)
      .takeWhile(_ != "[DONE]")
      .filter(_.nonEmpty)
      .mapZIO { line =>
        ZIO.fromEither(line.fromJson[SSEChunk])
          .orElse(ZIO.succeed(SSEChunk(Nil)))
          .flatMap { chunk =>
            chunk.choices.headOption.map(_.delta) match
              case None => ZIO.unit
              case Some(delta) =>
                // reasoning_content：静默积累，不推送给用户
                delta.reasoning_content.filter(_.nonEmpty).foreach(reasoningBuf.append)

                // 文本 token：推送 + 积累
                val textEffect = delta.content match
                  case Some(tok) if tok.nonEmpty =>
                    contentBuf.append(tok)
                    onToken(tok)
                  case _ => ZIO.unit

                // 工具调用片段积累
                val tcEffect = delta.tool_calls.getOrElse(Nil).foldLeft(ZIO.unit) { (acc, tc) =>
                  acc *> ZIO.succeed {
                    val existing = tcMap.getOrElseUpdate(tc.index, TcAccum(
                      id   = tc.id.getOrElse(""),
                      name = tc.function.flatMap(_.name).getOrElse(""),
                      args = new StringBuilder,
                    ))
                    if tc.id.exists(_.nonEmpty) then tcMap(tc.index) = existing.copy(id = tc.id.get)
                    tc.function.flatMap(_.name).filter(_.nonEmpty).foreach { n =>
                      tcMap(tc.index) = tcMap(tc.index).copy(name = n)
                    }
                    tc.function.flatMap(_.arguments).foreach(a => tcMap(tc.index).args.append(a))
                  }
                }

                textEffect *> tcEffect
          }
      }
      .runDrain
      .as {
        val rc = Option(reasoningBuf.toString).filter(_.nonEmpty)
        if tcMap.nonEmpty then
          val toolCalls = tcMap.toList.sortBy(_._1).map { case (_, tc) =>
            ToolCall(tc.id, tc.name, tc.args.toString)
          }
          Message(Role.Assistant, content = None, toolCalls = toolCalls, reasoningContent = rc)
        else
          Message(Role.Assistant, content = Some(contentBuf.toString), reasoningContent = rc)
      }

  private def schemaToOAI(s: ToolSchema): OAITool =
    import zio.json.ast.Json
    val props = Json.Obj(s.parameters.map { case (k, p) =>
      k -> Json.Obj(Chunk(
        "type"        -> Json.Str(p.`type`),
        "description" -> Json.Str(p.description),
      ))
    }.to(Chunk))
    val schema = Json.Obj(Chunk(
      "type"       -> Json.Str("object"),
      "properties" -> props,
      "required"   -> Json.Arr(s.required.map(Json.Str(_)).to(Chunk)),
    ))
    OAITool(function = OAIToolDef(s.name, s.description, schema))
