package agent.tools

import agent.core.{ToolSchema, ToolParam}
import zio.*
import zio.json.*
import java.io.{BufferedReader, FileReader, FileWriter, File}

// ── 工具基类 ──────────────────────────────────────────────────────────────

trait Tool:
  def schema: ToolSchema
  def execute(args: Map[String, String]): Task[String]

object Tool:
  def all: List[Tool] = List(
    ReadFileTool,
    WriteFileTool,
    EditFileTool,
    ShellTool,
    ListDirTool,
  )

  def schemas: List[ToolSchema] = all.map(_.schema)

  def dispatch(name: String, args: Map[String, String]): Task[String] =
    all.find(_.schema.name == name) match
      case Some(t) => t.execute(args)
      case None    => ZIO.fail(new IllegalArgumentException(s"Unknown tool: $name"))

// ── 公共工具函数 ──────────────────────────────────────────────────────────

private def requiredArg(args: Map[String, String], key: String): Task[String] =
  ZIO.fromOption(args.get(key)).orElseFail(new IllegalArgumentException(s"$key required"))

private def withReader[A](path: String)(f: BufferedReader => A): Task[A] =
  ZIO.acquireReleaseWith(ZIO.attempt(BufferedReader(FileReader(path))))(r => ZIO.succeed(r.close()))(r => ZIO.attempt(f(r)))

private def withWriter[A](path: String, append: Boolean = false)(f: FileWriter => A): Task[A] =
  ZIO.acquireReleaseWith(ZIO.attempt(FileWriter(path, append)))(w => ZIO.succeed(w.close()))(w => ZIO.attempt(f(w)))

// ── file_read ──────────────────────────────────────────────────────────────

object ReadFileTool extends Tool:
  val schema = ToolSchema(
    name = "file_read",
    description = "Read the content of a file at an absolute path",
    parameters = Map(
      "path"   -> ToolParam("string",  "Absolute file path", required = true),
      "offset" -> ToolParam("integer", "Start line, 1-indexed (default: 1)"),
      "limit"  -> ToolParam("integer", "Max lines to read (default: all)"),
    ),
    required = List("path"),
  )

  def execute(args: Map[String, String]): Task[String] =
    for
      path   <- requiredArg(args, "path")
      offset  = args.get("offset").flatMap(_.toIntOption).map(_ - 1).filter(_ >= 0).getOrElse(0)
      limit   = args.get("limit").flatMap(_.toIntOption).filter(_ > 0).getOrElse(Int.MaxValue)
      content <- ZIO.attemptBlocking {
        // 流式读取，不把整个文件加载进内存
        val reader = BufferedReader(FileReader(path))
        try
          val result = scala.collection.mutable.ListBuffer.empty[String]
          var lineNum = 0
          var line: String | Null = reader.readLine()
          while line != null && result.size < limit do
            if lineNum >= offset then
              result += s"${lineNum + 1}\t$line"
            lineNum += 1
            line = if result.size < limit then reader.readLine() else null
          result.mkString("\n")
        finally reader.close()
      }
    yield content

// ── file_write ─────────────────────────────────────────────────────────────

object WriteFileTool extends Tool:
  val schema = ToolSchema(
    name = "file_write",
    description = "Write (overwrite) a file with given content",
    parameters = Map(
      "path"    -> ToolParam("string", "Absolute file path", required = true),
      "content" -> ToolParam("string", "Content to write", required = true),
    ),
    required = List("path", "content"),
  )

  def execute(args: Map[String, String]): Task[String] =
    for
      path    <- requiredArg(args, "path")
      content <- requiredArg(args, "content")
      _       <- ZIO.attemptBlocking {
        val f = File(path)
        Option(f.getParentFile).foreach(_.mkdirs())
        val w = FileWriter(f)
        try w.write(content) finally w.close()
      }
    yield s"Written $path (${content.length} bytes)"

// ── file_edit ──────────────────────────────────────────────────────────────

object EditFileTool extends Tool:
  val schema = ToolSchema(
    name = "file_edit",
    description = "Replace an exact string in a file with a new string",
    parameters = Map(
      "path"       -> ToolParam("string", "Absolute file path", required = true),
      "old_string" -> ToolParam("string", "Exact string to replace (must be unique)", required = true),
      "new_string" -> ToolParam("string", "Replacement string", required = true),
    ),
    required = List("path", "old_string", "new_string"),
  )

  def execute(args: Map[String, String]): Task[String] =
    for
      path   <- requiredArg(args, "path")
      oldStr <- requiredArg(args, "old_string")
      newStr <- requiredArg(args, "new_string")
      result <- ZIO.attemptBlocking {
        val br2 = new BufferedReader(new FileReader(path)); val lines = try br2.lines().toArray.mkString("\n") finally br2.close()
        if !lines.contains(oldStr) then
          throw IllegalArgumentException(s"old_string not found in $path")
        val updated = lines.replace(oldStr, newStr)
        val writer  = FileWriter(path)
        try writer.write(updated) finally writer.close()
        s"Edited $path"
      }
    yield result

// ── shell_exec ─────────────────────────────────────────────────────────────

object ShellTool extends Tool:
  val schema = ToolSchema(
    name = "shell_exec",
    description = "Execute a shell command and return combined stdout+stderr",
    parameters = Map(
      "command"    -> ToolParam("string",  "Shell command to run", required = true),
      "workdir"    -> ToolParam("string",  "Working directory (optional)"),
      "timeout_ms" -> ToolParam("integer", "Timeout in milliseconds (default: 30000, max: 300000)"),
    ),
    required = List("command"),
  )

  def execute(args: Map[String, String]): Task[String] =
    for
      cmd     <- requiredArg(args, "command")
      workdir  = args.get("workdir")
      timeout  = args.get("timeout_ms").flatMap(_.toLongOption)
                   .map(_.min(300000L).max(1000L))
                   .getOrElse(30000L)
      output  <- ZIO.attemptBlocking {
        val pb = ProcessBuilder("/bin/sh", "-c", cmd)
        workdir.foreach(d => pb.directory(File(d)))
        pb.redirectErrorStream(true)
        val proc = pb.start().nn
        val completed = proc.waitFor(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
        if !completed then
          proc.destroyForcibly()
          proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
          throw RuntimeException(s"Command timed out after ${timeout}ms: $cmd")
        val src      = scala.io.Source.fromInputStream(proc.getInputStream.nn)
        val out      = try src.mkString finally src.close()
        val exitCode = proc.exitValue()
        val raw = if exitCode != 0 then s"[exit $exitCode]\n$out" else out
        truncateOutput(raw)
      }
    yield output

  private val HEAD_CHARS = 3000
  private val TAIL_CHARS = 1000
  private val MAX_CHARS  = HEAD_CHARS + TAIL_CHARS

  private def truncateOutput(s: String): String =
    if s.length <= MAX_CHARS then s
    else
      val dropped = s.length - MAX_CHARS
      s.take(HEAD_CHARS) +
      s"\n\n[... $dropped chars truncated ...]\n\n" +
      s.takeRight(TAIL_CHARS)

// ── list_dir ───────────────────────────────────────────────────────────────

object ListDirTool extends Tool:
  val schema = ToolSchema(
    name = "list_dir",
    description = "List files and directories at a given path",
    parameters = Map(
      "path" -> ToolParam("string", "Absolute directory path", required = true),
    ),
    required = List("path"),
  )

  def execute(args: Map[String, String]): Task[String] =
    for
      path   <- requiredArg(args, "path")
      result <- ZIO.attemptBlocking {
        val f = File(path)
        if !f.exists()     then s"$path does not exist"
        else if !f.isDirectory then s"$path is not a directory"
        else
          Option(f.listFiles()) match
            case None        => s"$path: permission denied"
            case Some(files) =>
              files.map { e => if e.isDirectory then s"${e.getName}/" else e.getName }
                   .sorted.mkString("\n")
      }
    yield result
