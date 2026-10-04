/**
 * 语音分发规则导入/导出纯逻辑：信封格式序列化、双格式解析（信封/裸数组）、按 id 智能合并。
 *
 * 文件格式（信封）：
 * {"type":"voice_dispatch_rules","version":1,"exportedAt":...,"rules":[...VoiceDispatchRule 原样...]}
 *
 * 规则原样导出（含触发统计与 timedTaskId）；定时任务执行按 payload+时间+重复标志 identity 重建，
 * 跨设备 timedTaskId 悬空无害。解析端兼容裸数组（rules.json 原文格式），便于手工导入。
 *
 * 归属模块：host/voice
 */
package com.brycewg.asrkb.host.voice

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal object VoiceDispatchRuleTransfer {

    private const val ENVELOPE_TYPE = "voice_dispatch_rules"
    private const val ENVELOPE_VERSION = 1

    /** 与 VoiceDispatchRuleStore 相同的 JSON 配置，保证字段兼容一致 */
    private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val RULES_SERIALIZER = ListSerializer(VoiceDispatchRule.serializer())

    /** 智能合并结果：[merged] 为落盘列表（current 顺序保持，新增按导入顺序追加尾部） */
    data class MergeResult(
        val merged: List<VoiceDispatchRule>,
        val addedCount: Int,
        val updatedCount: Int
    )

    @Serializable
    private data class Envelope(
        val type: String,
        val version: Int,
        val exportedAt: Long,
        val rules: List<VoiceDispatchRule>
    )

    /** 序列化为信封 JSON（规则原样，不清洗任何字段）。 */
    fun exportRulesJson(rules: List<VoiceDispatchRule>): String {
        val envelope = Envelope(
            type = ENVELOPE_TYPE,
            version = ENVELOPE_VERSION,
            exportedAt = System.currentTimeMillis(),
            rules = rules
        )
        return JSON.encodeToString(Envelope.serializer(), envelope)
    }

    /**
     * 解析导入文件：先按信封，失败回退裸数组（rules.json 原文格式）。
     * 两者皆失败或信封 type 不符时抛 [IllegalArgumentException]，调用方据此提示格式错误。
     */
    fun parseRulesJson(text: String): List<VoiceDispatchRule> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("empty rule file")
        }
        val envelope = runCatching {
            JSON.decodeFromString(Envelope.serializer(), trimmed)
        }.getOrNull()
        if (envelope != null) {
            if (envelope.type != ENVELOPE_TYPE) {
                throw IllegalArgumentException("unexpected envelope type: ${envelope.type}")
            }
            return envelope.rules
        }
        return try {
            JSON.decodeFromString(RULES_SERIALIZER, trimmed)
        } catch (t: Throwable) {
            throw IllegalArgumentException("unrecognized rule file format", t)
        }
    }

    /**
     * 按 id 智能合并：id 相同 = 同一条，以导入版本覆盖；id 不同 = 追加新增。
     * 本地已有规则一律保留（导入即权威仅作用于冲突 id）。
     */
    fun mergeRules(current: List<VoiceDispatchRule>, imported: List<VoiceDispatchRule>): MergeResult {
        if (imported.isEmpty()) return MergeResult(current, 0, 0)
        val importedById = LinkedHashMap<String, VoiceDispatchRule>()
        for (rule in imported) importedById[rule.id] = rule
        val currentIds = current.mapTo(HashSet()) { it.id }
        val updatedCount = importedById.keys.count { it in currentIds }
        val addedCount = importedById.size - updatedCount
        val overridden = current.map { importedById[it.id] ?: it }
        val appended = importedById.entries
            .asSequence()
            .filter { it.key !in currentIds }
            .map { it.value }
            .toList()
        return MergeResult(overridden + appended, addedCount, updatedCount)
    }
}
