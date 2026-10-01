/**
 * 语音分发规则模型（v1：执行桩仅打印日志；v2 接入真实执行面）。
 *
 * 归属模块：host/voice
 */
package com.brycewg.asrkb.host.voice

import kotlinx.serialization.Serializable

@Serializable
enum class VoiceMatchType {
    /** 包含关键词：patterns 中任一关键词出现在识别文本中即命中 */
    KEYWORD_INCLUDE,

    /** 完全匹配：归一化后的识别文本与 patterns 中任一项完全一致 */
    KEYWORD_EXACT,

    /** 正则表达式：patterns[0] 作为正则整串匹配（find 语义，支持捕获组） */
    REGEX
}

@Serializable
enum class VoiceDispatchType {
    /** 分发到 AutoJs6 单独脚本（payload=脚本路径） */
    SCRIPT,

    /** 简单脚本命令/自动化片段（payload=JS 片段） */
    AUTOMATION,

    /** 调安卓本地代码（payload=内置命令名） */
    NATIVE,

    /** 常用安卓 API（payload=API 名） */
    ANDROID_API,

    /** 脚本循环定时执行（payload=定时任务标识） */
    SCHEDULE
}

@Serializable
data class VoiceDispatchRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,

    /** 优先级 0~100，数字越大越优先；同优先级按创建顺序 */
    val priority: Int = 0,

    // ---- 触发条件（对每句识别文本直接匹配，无任何额外触发词） ----
    val matchType: VoiceMatchType,
    /** 关键词列表（任一命中）或单条正则 */
    val patterns: List<String>,

    // ---- 分发动作（五方向落点） ----
    val dispatchType: VoiceDispatchType,
    /** 脚本路径 / JS 命令片段 / 内置命令名 / API 名 / 定时任务标识 */
    val payload: String,

    /** 附加参数模板，支持占位符 {text}=整句识别文本、{1}=正则捕获组1 */
    val argsTemplate: String? = null,

    // ---- 高级（v1 存储并生效于匹配冷却；执行面 v2 启用） ----

    /** 同一规则两次触发的最小间隔（毫秒）；0 = 不限制（冷却为显式配置项，默认关闭） */
    val cooldownMs: Long = 0,
    val confirmBeforeRun: Boolean = false,

    // ---- 统计 ----
    val createdAt: Long,
    val lastTriggeredAt: Long = 0,
    val triggerCount: Int = 0
) {
    companion object {
        fun newId(): String = java.util.UUID.randomUUID().toString()
    }
}
