/**
 * 语音分发规则存储：filesDir/voice_dispatch/rules.json（原子写，列表型数据不适合 SharedPreferences）。
 *
 * 归属模块：host/voice
 */
package com.brycewg.asrkb.host.voice

import android.content.Context
import android.util.Log
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

internal class VoiceDispatchRuleStore(context: Context) {

    companion object {
        private const val TAG = "VoiceDispatchStore"
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val SERIALIZER = ListSerializer(VoiceDispatchRule.serializer())
    }

    private val file: File = File(context.applicationContext.filesDir, "voice_dispatch/rules.json")

    fun load(): List<VoiceDispatchRule> {
        if (!file.exists()) return emptyList()
        return try {
            JSON.decodeFromString(SERIALIZER, file.readText())
        } catch (t: Throwable) {
            // 规则文件损坏时视为空表，不影响识别链路
            Log.e(TAG, "Failed to parse voice dispatch rules; treating as empty", t)
            emptyList()
        }
    }

    fun save(rules: List<VoiceDispatchRule>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSON.encodeToString(SERIALIZER, rules))
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                Log.e(TAG, "Failed to rename temp rules file")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to save voice dispatch rules", t)
        }
    }

    /** 记录一次触发（load-modify-save，v1 频率下可接受）。 */
    fun recordTrigger(ruleId: String, now: Long) {
        val rules = load()
        val index = rules.indexOfFirst { it.id == ruleId }
        if (index < 0) return
        val old = rules[index]
        val updated = rules.toMutableList()
        updated[index] = old.copy(
            lastTriggeredAt = now,
            triggerCount = old.triggerCount + 1
        )
        save(updated)
    }
}
