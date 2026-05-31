package agent.plugins

import agent.core.{ToolSchema, ToolParam}
import sttp.client3.*
import zio.*

class WebFetchPlugin(backend: SttpBackend[Task, Any]) extends Plugin:

  val schema = ToolSchema(
    name = "web_fetch",
    description = "Fetch the content of a URL and return it as plain text. Strips HTML tags.",
    parameters = Map(
      "url"       -> ToolParam("string",  "URL to fetch", required = true),
      "max_chars" -> ToolParam("integer", "Max characters to return (default 8000)"),
    ),
    required = List("url"),
  )

  def execute(args: Map[String, String]): Task[String] =
    args.get("url") match
      case None      => ZIO.fail(new IllegalArgumentException("url required"))
      case Some(url) =>
        val maxChars = args.get("max_chars").flatMap(_.toIntOption).getOrElse(8000)
        fetch(url, maxChars)

  private def fetch(url: String, maxChars: Int): Task[String] =
    val request = basicRequest
      .get(uri"$url")
      .header("User-Agent", "Mozilla/5.0 (compatible; ai-agent/0.1)")
      .header("Accept", "text/html,text/plain,*/*")
      .response(asString)

    backend.send(request).flatMap { resp =>
      resp.body match
        case Left(err)   => ZIO.fail(new RuntimeException(s"HTTP error fetching $url: $err"))
        case Right(body) =>
          val text      = stripHtml(body).take(maxChars)
          val truncated = if body.length > maxChars then s"$text\n\n[... truncated, ${body.length} total chars]"
                          else text
          ZIO.succeed(truncated)
    }

  private def stripHtml(html: String): String =
    val noScript = html
      .replaceAll("(?si)<script[^>]*>.*?</script>", " ")
      .replaceAll("(?si)<style[^>]*>.*?</style>", " ")
    val withNewlines = noScript
      .replaceAll("(?i)<br\\s*/?>", "\n")
      .replaceAll("(?i)</(p|div|h[1-6]|li|tr|blockquote)>", "\n")
    val noTags = withNewlines.replaceAll("<[^>]+>", "")
    val decoded = noTags
      .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
      .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
    decoded.replaceAll("(?m)\\n{3,}", "\n\n").trim
