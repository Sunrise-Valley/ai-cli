package agent.plugins

import agent.core.{ToolSchema, ToolParam}
import sttp.client3.*
import sttp.client3.ziojson.*
import zio.*
import zio.json.*

// ── Serper API 响应 ───────────────────────────────────────────────────────

private case class SerperResponse(
  organic: Option[List[SerperItem]],
) derives JsonDecoder

private case class SerperItem(
  title: String,
  link: String,
  snippet: Option[String],
) derives JsonDecoder

// ── SearXNG 响应 ──────────────────────────────────────────────────────────

private case class SearxResult(results: List[SearxItem]) derives JsonDecoder
private case class SearxItem(title: String, url: String, content: Option[String]) derives JsonDecoder

// ── WebSearch Plugin ──────────────────────────────────────────────────────

private val urlEncode = java.net.URLEncoder.encode(_: String, "UTF-8")

class WebSearchPlugin(cfg: PluginConfig, backend: SttpBackend[Task, Any]) extends Plugin:

  val schema = ToolSchema(
    name        = "web_search",
    description = "Search the web for current information. Returns titles, URLs and snippets.",
    parameters  = Map(
      "query"       -> ToolParam("string",  "Search query", required = true),
      "num_results" -> ToolParam("integer", "Max results to return (default 5)"),
    ),
    required = List("query"),
  )

  def execute(args: Map[String, String]): Task[String] =
    args.get("query") match
      case None        => ZIO.fail(new IllegalArgumentException("query required"))
      case Some(query) =>
        val n = args.get("num_results").flatMap(_.toIntOption).getOrElse(5)
        cfg.webSearchEngine match
          case "serper"  => searchSerper(query, n)
          case "searxng" => searchSearXNG(query, n)
          case _         => searchBing(query, n)   // 默认：Bing RSS，无需 key

  // ── Bing RSS 搜索（默认，无需 key，国内可用）──────────────────────────
  // 使用 Bing 的 RSS 输出接口，返回干净的 XML，无需 HTML 解析

  private def searchBing(query: String, numRes: Int): Task[String] =
    val request = basicRequest
      .get(uri"https://www.bing.com/search?q=${urlEncode(query)}&format=rss&count=$numRes&setlang=en&mkt=en-US&ensearch=1")
      .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
      .header("Accept-Language", "en-US,en;q=0.9")
      .response(asString)

    backend.send(request).flatMap { resp =>
      ZIO.fromEither(resp.body.left.map(e => new RuntimeException(s"Bing error: $e")))
        .map(xml => parseBingRss(xml, numRes, query))
    }

  private def parseBingRss(xml: String, numRes: Int, query: String): String =
    // 提取 <item> 块
    val itemPat   = """(?s)<item>(.*?)</item>""".r
    val titlePat  = """<title>(.*?)</title>""".r
    val linkPat   = """<link>(.*?)</link>""".r
    val descPat   = """<description>(.*?)</description>""".r

    val items = itemPat.findAllMatchIn(xml).take(numRes).map { m =>
      val block   = m.group(1)
      val title   = titlePat.findFirstMatchIn(block).map(_.group(1)).getOrElse("").trim
      val link    = linkPat.findFirstMatchIn(block).map(_.group(1)).getOrElse("").trim
      val desc    = descPat.findFirstMatchIn(block).map(_.group(1))
        .getOrElse("").replaceAll("<[^>]+>", "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").trim
      s"$title\n$link\n$desc"
    }.toList

    if items.isEmpty then s"No results found for: $query"
    else items.mkString("\n\n")

  // ── Serper（需要付费 key）────────────────────────────────────────────────

  private def searchSerper(query: String, numRes: Int): Task[String] =
    if cfg.webSearchApiKey.isEmpty then
      ZIO.fail(new RuntimeException("WEB_SEARCH_API_KEY required for Serper"))
    else
      val baseUrl = if cfg.webSearchBaseUrl.nonEmpty then cfg.webSearchBaseUrl else "https://google.serper.dev"
      val request = basicRequest
        .post(uri"$baseUrl/search")
        .header("X-API-KEY", cfg.webSearchApiKey)
        .header("Content-Type", "application/json")
        .body(s"""{"q":"$query","num":$numRes}""")
        .response(asJson[SerperResponse])

      backend.send(request).flatMap { resp =>
        ZIO.fromEither(resp.body)
          .mapError(e => new RuntimeException(s"Serper error: $e"))
          .map { r =>
            r.organic.getOrElse(Nil).take(numRes).map { item =>
              s"${item.title}\n${item.link}\n${item.snippet.getOrElse("")}"
            }.mkString("\n\n")
          }
      }

  // ── SearXNG（无需 key，可用公共实例）────────────────────────────────────

  private def searchSearXNG(query: String, numRes: Int): Task[String] =
    val baseUrl = if cfg.webSearchBaseUrl.nonEmpty then cfg.webSearchBaseUrl else "https://searx.be"
    val request = basicRequest
      .get(uri"$baseUrl/search?q=${urlEncode(query)}&format=json")
      .header("User-Agent", "ai-agent/0.1")
      .response(asString)

    backend.send(request).flatMap { resp =>
      ZIO.fromEither(resp.body.left.map(e => new RuntimeException(s"SearXNG HTTP error: $e")))
        .flatMap { body =>
          ZIO.fromEither(body.fromJson[SearxResult])
            .mapError(e => new RuntimeException(s"SearXNG parse error: $e"))
            .map { r =>
              r.results.take(numRes).map { item =>
                s"${item.title}\n${item.url}\n${item.content.getOrElse("")}"
              }.mkString("\n\n")
            }
        }
    }
