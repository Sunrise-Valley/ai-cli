package agent.cli

import agent.planner.DimScores
import fansi.*
import zio.*

object Printer:

  def planning(msg: String): UIO[Unit]  = print(Color.Cyan(s"[plan] $msg"))
  def executing(msg: String): UIO[Unit] = print(Color.Yellow(s"[exec] $msg"))
  def verified(msg: String): UIO[Unit]  = print(Color.Green(s"[ok]   $msg"))
  def warning(msg: String): UIO[Unit]   = print(Color.LightYellow(s"[warn] $msg"))
  def error(msg: String): UIO[Unit]     = print(Color.Red(s"[err]  $msg"))
  def result(msg: String): UIO[Unit]    = print(Bold.On(Color.White(msg)))
  def plain(msg: String): UIO[Unit]     = ZIO.succeed(println(msg))

  // 意图分析：显示规范化目标和自动补全的假设
  def intent(normalized: String, assumptions: List[String]): UIO[Unit] =
    ZIO.succeed {
      println(Color.LightMagenta(s"[intent] → $normalized").render)
      assumptions.foreach(a => println(Color.LightMagenta(s"[intent]   · $a").render))
    }

  // 闲聊/无效输入的直接回复
  def reply(msg: String): UIO[Unit] =
    ZIO.succeed(println(Bold.On(Color.White(msg)).render))

  // 流式 token：不换行直接打印，与终端 flush 保持同步
  def streamToken(token: String): UIO[Unit] =
    ZIO.succeed {
      print(token)
      java.lang.System.out.flush()
    }

  def streamEnd(): UIO[Unit] =
    ZIO.succeed(println())

  def scores(s: DimScores): UIO[Unit] =
    val fmt = (d: Double) => f"$d%.2f"
    val bar = (d: Double) =>
      val n = (d * 10).toInt
      "█" * n + "░" * (10 - n)
    ZIO.succeed(println(
      Color.DarkGray(
        s"       result=${fmt(s.result.score)} ${bar(s.result.score)}  " +
        s"process=${fmt(s.process.score)} ${bar(s.process.score)}\n" +
        s"       quality=${fmt(s.quality.score)} ${bar(s.quality.score)}  " +
        s"comply=${fmt(s.compliance.score)} ${bar(s.compliance.score)}  " +
        s"composite=${fmt(s.composite)}"
      ).render
    ))

  private def print(str: Str): UIO[Unit] =
    ZIO.succeed(println(str.render))
