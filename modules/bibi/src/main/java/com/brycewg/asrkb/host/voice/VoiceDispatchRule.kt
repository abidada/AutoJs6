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
    /** 分发到 AutoJs6 单独脚本（payload=脚本路径；执行方式=立即/循环/定时） */
    SCRIPT,

    /** 简单脚本命令/自动化片段（payload=JS 片段） */
    AUTOMATION,

    /** 调安卓本地代码（payload=内置命令名） */
    NATIVE,

    /** 常用安卓 API（payload=API 名） */
    ANDROID_API
}

/**
 * 执行脚本类型的执行方式（定稿方案：立即/循环/定时均归属 SCRIPT 分发类型）。
 */
@Serializable
enum class ScriptExecMode {
    /** 立即执行一次（与文件列表运行按钮同链路） */
    IMMEDIATE,

    /** 循环运行（与文件列表「循环运行」弹窗同链路，参数存于规则） */
    LOOP,

    /** 定时任务（与文件列表「定时任务」同链路；B4 阶段接入执行） */
    TIMED
}

/**
 * 定时任务参数快照（scriptExecMode=TIMED）：任务可被用户在任务页删除，
 * 规则保存快照以便语音再次命中时按原参数重建（与任务页配置同链路）。
 */
@Serializable
data class TimedTaskSnapshot(
    /** 触发时间（重复任务=当天时点的毫秒；一次性任务=具体时间戳），语义同 TimedTask.millis */
    val millis: Long,
    /** 重复标志：0=一次性；0x7F=每天；按位=星期（语义同 TimedTask.timeFlag） */
    val timeFlag: Long,
    val delayMs: Long = 0
)

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

    /** 执行方式（仅 dispatchType=SCRIPT 时生效） */
    val scriptExecMode: ScriptExecMode = ScriptExecMode.IMMEDIATE,

    /** 循环运行参数（scriptExecMode=LOOP 时生效；语义与循环运行弹窗一致） */
    val loopTimes: Int = 1,
    val loopDelayMs: Long = 0,
    val loopIntervalMs: Long = 0,

    /** 已绑定的定时任务 id（scriptExecMode=TIMED；任务被删后语音命中可重建并更新此 id） */
    val timedTaskId: Long? = null,

    /** 定时任务参数快照（scriptExecMode=TIMED；任务不存在时可按此重建） */
    val timedTaskSnapshot: TimedTaskSnapshot? = null,

    /** 附加参数模板，支持占位符 {text}=整句识别文本、{1}=正则捕获组1 */
    val argsTemplate: String? = null,

    // ---- 高级（v1 存储并生效于匹配冷却；执行面 v2 启用） ----

    /** 同一规则两次触发的最小间隔（毫秒）；0 = 不限制（冷却为显式配置项，默认关闭） */
    val cooldownMs: Long = 0,
    val confirmBeforeRun: Boolean = false,

    // ---- TTS 播报（命中确认文案）：null=用全局默认模板；"none"=该规则静音；其他=自定义模板 ----
    // 模板占位符：{rule}=规则名、{text}=整句识别文本、{1}~{3}=正则捕获组
    val ttsFeedback: String? = null,

    // ---- 统计 ----
    val createdAt: Long,
    val lastTriggeredAt: Long = 0,
    val triggerCount: Int = 0
) {
    companion object {
        fun newId(): String = java.util.UUID.randomUUID().toString()
    }
}
