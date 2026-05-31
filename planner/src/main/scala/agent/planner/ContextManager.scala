package agent.planner

import agent.core.{AgentConfig, Message, Role}

// ── 任务树感知的上下文分层管理 ─────────────────────────────────────────────
//
// 每次 LLM 调用的 context 由三层组成：
//
//  ① 固定核（core）   ≈ 200 token
//     System 消息（记忆召回注入）+ 当前总目标
//     每次必带，不裁剪
//
//  ② 任务链摘要（chain）≈ 500 token
//     已完成的兄弟/父任务结果，每条压缩为 250 字以内
//     超出 ctxMaxChainMsgs 时丢弃最旧的
//
//  ③ 活跃窗口（active）≈ 2000 token
//     当前叶任务的完整对话（User + Tool 调用回合）
//     超出 ctxMaxActiveMsgs 时做中段收缩：
//       保留 System + 首条 User（目标）+ 最近 ctxActiveTrimTo 条
//       在截断处插入 "context trimmed" 提示

object ContextManager:

  // ── 层①② 裁剪：传入 callLLMWithTools 的 context 参数 ──────────────────
  //
  // context 内容 = [System 记忆消息]* + [已完成子任务 Assistant 消息]*
  //
  def trimContext(context: List[Message], cfg: AgentConfig): List[Message] =
    val (sysMsgs, otherMsgs) = context.partition(_.role == Role.System)

    // 任务链摘要：每条 Assistant 消息的 content 截断到 ctxResultPreview 字符
    val chainMsgs = otherMsgs
      .takeRight(cfg.ctxMaxChainMsgs)
      .map(compressChainMsg(_, cfg.ctxResultPreview))

    sysMsgs ++ chainMsgs

  // ── 层③ 裁剪：loop() 内部的 messages 列表 ──────────────────────────────
  //
  // messages 结构：[context(已裁剪)] + [User goal] + [Assistant+Tool 回合]*
  //
  def trimActiveWindow(messages: List[Message], cfg: AgentConfig): List[Message] =
    if messages.length <= cfg.ctxMaxActiveMsgs then messages
    else
      val sysMsgs  = messages.filter(_.role == Role.System)
      val nonSys   = messages.filterNot(_.role == Role.System)
      val goalMsg  = nonSys.headOption.toList
      val restMsgs = if nonSys.length > 1 then nonSys.tail else Nil

      val keepCount    = (cfg.ctxActiveTrimTo - sysMsgs.length - goalMsg.length).max(4)
      val recentMsgs   = restMsgs.takeRight(keepCount)
      val droppedCount = restMsgs.length - recentMsgs.length

      val trimNotice = if droppedCount > 0 then
        List(Message(Role.System, Some(
          s"[context trimmed: $droppedCount older messages dropped to stay within context limit]"
        )))
      else Nil

      val candidate = sysMsgs ++ goalMsg ++ trimNotice ++ recentMsgs
      // 裁剪后可能产生孤立的 Tool 消息（找不到配套的 tool_calls Assistant），需清除
      ensureToolCallConsistency(candidate)

  // 清除孤立的 Tool 消息：Tool.toolResult.toolCallId 必须在窗口内有配套的 tool_calls
  private def ensureToolCallConsistency(messages: List[Message]): List[Message] =
    val validIds = messages
      .flatMap(_.toolCalls.map(_.id))
      .toSet
    messages.filter { m =>
      m.role != Role.Tool ||
      m.toolResult.exists(tr => validIds.contains(tr.toolCallId))
    }

  // ── 工具函数 ──────────────────────────────────────────────────────────────

  // 压缩任务链消息的 content（result 截断到 maxChars 字符）
  private def compressChainMsg(msg: Message, maxChars: Int): Message =
    msg.content match
      case None    => msg
      case Some(c) =>
        if c.length <= maxChars then msg
        else msg.copy(content = Some(c.take(maxChars) + "…"))
