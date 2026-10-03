/**
 * 语音分发引擎（v1）：规则表加载/归一化/优先级匹配/冷却/执行桩。
 *
 * 触发模型（方案 §7A.4 定稿）：无触发词，分发总开关开启时每句识别最终文本直接进规则匹配；
 * 命中 → 打印 [VoiceDispatch] 四元组日志（v1 执行桩），未命中 → fallback 日志；
 * 两种结果都反馈到监听面板，随后由交互层自动续听。
 *
 * 线程模型：匹配在调用方协程/线程执行，不阻塞识别；整体 try-catch 保证分发器崩溃不影响悬浮球。
 *
 * 归属模块：host
 */
package com.brycewg.asrkb.host

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.host.voice.VoiceDispatchExecutor
import com.brycewg.asrkb.host.voice.VoiceDispatchRule
import com.brycewg.asrkb.host.voice.VoiceDispatchRuleStore
import com.brycewg.asrkb.host.voice.VoiceMatchType

internal class VoiceCommandDispatcher private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "VoiceDispatch"

        @Volatile
        private var instance: VoiceCommandDispatcher? = null

        fun getInstance(context: Context): VoiceCommandDispatcher =
            instance ?: synchronized(this) {
                instance ?: VoiceCommandDispatcher(context.applicationContext).also { instance = it }
            }

        private val PUNCTUATION = charArrayOf(
            '，', '。', '！', '？', '；', '：', '、', '“', '”', '‘', '’',
            '（', '）', '《', '》', '…', '—', '·', ',', '.', '!', '?', ';', ':',
            '\"', '\'', '(', ')', '<', '>'
        )

        /**
         * 归一化：去首尾空白、全角→半角、小写、去常见中英标点。
         */
        fun normalize(text: String): String {
            val sb = StringBuilder(text.length)
            for (ch in text) {
                val c = when (ch) {
                    in '！'..'～' -> ch - 0xFEE0  // 全角 ASCII 区转半角
                    '　' -> ' '
                    else -> ch
                }
                if (c in PUNCTUATION) continue
                sb.append(c)
            }
            return sb.toString().trim().lowercase()
        }
    }

    private val store = VoiceDispatchRuleStore(appContext)

    init {
        // 语音重建定时任务后把新任务 id 写回规则（绑定自愈）
        VoiceDispatchExecutor.bindTimedTask = { ruleId, taskId ->
            try {
                val rules = store.load()
                val idx = rules.indexOfFirst { it.id == ruleId }
                if (idx >= 0) {
                    store.save(rules.toMutableList().also { it[idx] = it[idx].copy(timedTaskId = taskId) })
                    invalidateCache()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "bind timed task write-back failed", t)
            }
        }
    }

    /** 规则缓存（写后失效；读取方并发安全）。 */
    @Volatile
    private var cachedRules: List<VoiceDispatchRule> = store.load()

    /**
     * 面板反馈槽（由交互层注入；调用发生在调用方线程）。
     * onHit/onMiss 均携带整句识别文本，供面板与 TTS 同源文案使用。
     */
    @Volatile
    var onHit: ((rule: VoiceDispatchRule, recognizedText: String, captured: List<String>) -> Unit)? = null

    @Volatile
    var onMiss: ((recognizedText: String) -> Unit)? = null

    /** 执行结果反馈槽（v2 执行面）：message 为空表示成功且静默。 */
    var onExecutionResult: ((rule: VoiceDispatchRule, ok: Boolean, message: String?) -> Unit)?
        get() = VoiceDispatchExecutor.onResult
        set(value) {
            VoiceDispatchExecutor.onResult = value
        }

    /** 规则增删改后调用，使缓存失效重载。 */
    fun invalidateCache() {
        cachedRules = store.load()
    }

    fun getStore(): VoiceDispatchRuleStore = store

    /**
     * 纯匹配（不落统计、不打印）：供「测试匹配」卡片与编辑页即时校验复用。
     * @return 命中的规则与捕获组
     */
    fun match(text: String): Pair<VoiceDispatchRule, List<String>>? {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return null
        val rules = cachedRules
            .asSequence()
            .filter { it.enabled }
            .sortedWith(compareByDescending<VoiceDispatchRule> { it.priority }.thenBy { it.createdAt })
        for (rule in rules) {
            val captured = tryMatch(rule, normalized)
            if (captured != null) return rule to captured
        }
        return null
    }

    private fun tryMatch(rule: VoiceDispatchRule, normalized: String): List<String>? = when (rule.matchType) {
        VoiceMatchType.KEYWORD_INCLUDE -> {
            val pats = rule.patterns.map { normalize(it) }.filter { it.isNotEmpty() }
            if (pats.any { normalized.contains(it) }) emptyList() else null
        }

        VoiceMatchType.KEYWORD_EXACT -> {
            val pats = rule.patterns.map { normalize(it) }.filter { it.isNotEmpty() }
            if (pats.any { normalized == it }) emptyList() else null
        }

        VoiceMatchType.REGEX -> {
            val pattern = rule.patterns.firstOrNull()?.trim().orEmpty()
            if (pattern.isEmpty()) {
                null
            } else {
                val regex = try {
                    Regex(pattern)
                } catch (t: Throwable) {
                    // 编译失败的正则不参与匹配（编辑页会标红）
                    null
                }
                regex?.find(normalized)?.groupValues?.drop(1)?.let { groups -> groups }
            }
        }
    }

    /**
     * 每句识别最终文本入口：冷却→匹配→执行桩/反馈。
     * @return 命中的规则名；未命中或冷却期返回 null
     */
    fun maybeDispatch(text: String): String? {
        return try {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val hit = match(trimmed) ?: run {
                Log.i(TAG, "fallback: no rule matched for \"$trimmed\"")
                onMiss?.invoke(trimmed)
                return null
            }
            val (rule, captured) = hit
            val now = System.currentTimeMillis()
            if (rule.cooldownMs > 0 && now - rule.lastTriggeredAt < rule.cooldownMs) {
                Log.d(TAG, "cooldown: rule=${rule.name} suppressed")
                return null
            }

            // 四元组日志保留（排查入口），真实执行由 v2 执行面按类型转发
            val args = renderArgs(rule.argsTemplate, trimmed, captured)
            Log.i(
                TAG,
                "type=${rule.dispatchType} rule=${rule.name} payload=${rule.payload} args=$args"
            )

            store.recordTrigger(rule.id, now)
            invalidateCache()
            onHit?.invoke(rule, trimmed, captured)
            VoiceDispatchExecutor.execute(appContext, rule, args)
            rule.name
        } catch (t: Throwable) {
            // 分发器异常不影响识别与悬浮球（§7.3 错误隔离结论）
            Log.e(TAG, "dispatch failed", t)
            null
        }
    }

    private fun renderArgs(template: String?, text: String, captured: List<String>): String {
        val tpl = template ?: return text
        return tpl
            .replace("{text}", text)
            .replace("{1}", captured.getOrNull(0) ?: "")
            .replace("{2}", captured.getOrNull(1) ?: "")
            .replace("{3}", captured.getOrNull(2) ?: "")
    }
}
