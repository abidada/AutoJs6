/**
 * 语音分发规则导入/导出纯逻辑单测：信封 round-trip、裸数组兼容、非法输入拒绝、智能合并语义。
 *
 * 归属模块：host/voice
 */
package com.brycewg.asrkb.host.voice

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDispatchRuleTransferTest {

    private val bareArrayJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun rule(
        id: String,
        name: String = "rule-$id",
        priority: Int = 0,
        triggerCount: Int = 0
    ): VoiceDispatchRule = VoiceDispatchRule(
        id = id,
        name = name,
        enabled = true,
        priority = priority,
        matchType = VoiceMatchType.KEYWORD_INCLUDE,
        patterns = listOf("打开闹钟"),
        dispatchType = VoiceDispatchType.SCRIPT,
        payload = "/storage/emulated/0/脚本/$name.js",
        scriptExecMode = ScriptExecMode.TIMED,
        timedTaskId = 42L,
        timedTaskSnapshot = TimedTaskSnapshot(millis = 1000L, timeFlag = 0x7F),
        createdAt = 100L,
        lastTriggeredAt = 200L,
        triggerCount = triggerCount
    )

    @Test
    fun exportThenParseRoundTripKeepsAllFields() {
        val rules = listOf(rule("a", triggerCount = 6), rule("b", priority = 5))
        val json = VoiceDispatchRuleTransfer.exportRulesJson(rules)
        // 原样导出：统计与设备字段全部保留
        assertEquals(rules, VoiceDispatchRuleTransfer.parseRulesJson(json))
    }

    @Test
    fun exportEnvelopeContainsMetadataFields() {
        val json = VoiceDispatchRuleTransfer.exportRulesJson(listOf(rule("a")))
        assertTrue(json.contains("\"type\":\"voice_dispatch_rules\""))
        assertTrue(json.contains("\"version\":1"))
        assertTrue(json.contains("\"exportedAt\":"))
    }

    @Test
    fun parseAcceptsBareArrayFormat() {
        val rules = listOf(rule("a"), rule("b", priority = 3))
        // 与 rules.json 原文一致的裸数组（store 同配置序列化）
        val json = bareArrayJson.encodeToString(
            ListSerializer(VoiceDispatchRule.serializer()),
            rules
        )
        assertEquals(rules, VoiceDispatchRuleTransfer.parseRulesJson(json))
    }

    @Test
    fun parseEmptyEnvelopeReturnsEmptyList() {
        val json = VoiceDispatchRuleTransfer.exportRulesJson(emptyList())
        assertTrue(VoiceDispatchRuleTransfer.parseRulesJson(json).isEmpty())
    }

    @Test
    fun parseRejectsGarbage() {
        assertThrows(IllegalArgumentException::class.java) {
            VoiceDispatchRuleTransfer.parseRulesJson("not json at all")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceDispatchRuleTransfer.parseRulesJson("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceDispatchRuleTransfer.parseRulesJson("""{"foo":1}""")
        }
    }

    @Test
    fun parseRejectsEnvelopeWithWrongType() {
        val json = """{"type":"something_else","version":1,"exportedAt":1,"rules":[]}"""
        assertThrows(IllegalArgumentException::class.java) {
            VoiceDispatchRuleTransfer.parseRulesJson(json)
        }
    }

    @Test
    fun mergeAddsNewRulesWithCount() {
        val current = listOf(rule("a"))
        val imported = listOf(rule("b"), rule("c"))
        val result = VoiceDispatchRuleTransfer.mergeRules(current, imported)
        assertEquals(2, result.addedCount)
        assertEquals(0, result.updatedCount)
        assertEquals(listOf("a", "b", "c"), result.merged.map { it.id })
    }

    @Test
    fun mergeOverridesConflictingIdKeepingCurrentOrder() {
        val current = listOf(rule("a", priority = 1), rule("b", priority = 2))
        // id 冲突：以导入版本整体覆盖（导入即权威）
        val imported = listOf(rule("b", name = "updated"))
        val result = VoiceDispatchRuleTransfer.mergeRules(current, imported)
        assertEquals(0, result.addedCount)
        assertEquals(1, result.updatedCount)
        assertEquals(listOf("a", "b"), result.merged.map { it.id })
        assertEquals("updated", result.merged[1].name)
        assertEquals(0, result.merged[1].priority)
    }

    @Test
    fun mergeMixedAddAndUpdate() {
        val current = listOf(rule("a"), rule("b"))
        val imported = listOf(rule("b", name = "b2"), rule("c"), rule("d"))
        val result = VoiceDispatchRuleTransfer.mergeRules(current, imported)
        assertEquals(2, result.addedCount)
        assertEquals(1, result.updatedCount)
        assertEquals(listOf("a", "b", "c", "d"), result.merged.map { it.id })
        assertEquals("b2", result.merged[1].name)
    }

    @Test
    fun mergeEmptyImportedReturnsCurrent() {
        val current = listOf(rule("a"))
        val result = VoiceDispatchRuleTransfer.mergeRules(current, emptyList())
        assertEquals(current, result.merged)
        assertEquals(0, result.addedCount)
        assertEquals(0, result.updatedCount)
    }
}
