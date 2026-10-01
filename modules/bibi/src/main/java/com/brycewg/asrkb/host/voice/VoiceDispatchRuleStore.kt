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

        /** 旧版本写死的冷却默认值（非用户所配），一次性迁移归零 */
        private const val LEGACY_DEFAULT_COOLDOWN_MS = 1500L
    }

    private val file: File = File(context.applicationContext.filesDir, "voice_dispatch/rules.json")
    private val migrationMarker: File =
        File(context.applicationContext.filesDir, "voice_dispatch/.cooldown_migrated")

    fun load(): List<VoiceDispatchRule> {
        if (!file.exists()) return emptyList()
        val rules = try {
            JSON.decodeFromString(SERIALIZER, file.readText())
        } catch (t: Throwable) {
            // 规则文件损坏时视为空表，不影响识别链路
            Log.e(TAG, "Failed to parse voice dispatch rules; treating as empty", t)
            emptyList()
        }
        return migrateLegacyCooldown(rules)
    }

    /**
     * 一次性迁移：存量规则的冷却值 1500ms（旧版隐式默认，编辑页此前不可配置）统一归零。
     * 标记文件防止重复执行；迁移失败时下次重跑（对已归零规则幂等）。
     */
    private fun migrateLegacyCooldown(rules: List<VoiceDispatchRule>): List<VoiceDispatchRule> {
        if (migrationMarker.exists()) return rules
        val migrated = rules.map { rule ->
            if (rule.cooldownMs == LEGACY_DEFAULT_COOLDOWN_MS) {
                rule.copy(cooldownMs = 0L)
            } else {
                rule
            }
        }
        return try {
            if (migrated != rules) save(migrated)
            migrationMarker.parentFile?.mkdirs()
            migrationMarker.writeText("1")
            Log.i(TAG, "Legacy cooldown migration done (${migrated.size} rules)")
            migrated
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to write legacy cooldown migration marker", t)
            migrated
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
