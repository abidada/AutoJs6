/**
 * 脚本宿主桥接（v2 执行面）：bibi 侧声明接口与槽位，AutoJs6 宿主实现并注册。
 *
 * 与 [PermissionRouter] 同一套 host 注入模式：bibi 作为 library 不能引用宿主的
 * 脚本引擎类，执行与脚本列表一律经此桥接转发；宿主侧实现保证与文件列表
 * 「运行按钮」完全同链路（见 AutoJsScriptHostBridge）。
 *
 * 归属模块：host
 */
package com.brycewg.asrkb.host

/** 目录/脚本条目（脚本选择器浏览器模式用）。 */
data class BibiScriptInfo(
    /** 文件名（含扩展名，如 "11.js"；文件夹为目录名） */
    val name: String,
    /** 绝对路径（规则 payload 直接可用） */
    val path: String,
    /** 相对工作目录的目录（"" 表示工作目录根），展示用 */
    val relativeDir: String,
    val lastModified: Long,
    /** true=文件夹（浏览器模式可进入），false=文件 */
    val isDirectory: Boolean = false,
    /** 文件大小（字节；文件夹为 0） */
    val size: Long = 0,
    /** 是否为可执行脚本（.js/.auto；文件夹恒为 false） */
    val isScript: Boolean = false
)

/** 循环运行参数（与文件列表「循环运行」弹窗语义一致）。 */
data class BibiLoopConfig(
    val loopTimes: Int,
    val loopDelayMs: Long,
    val loopIntervalMs: Long
)

/** 定时任务信息（编辑页展示与执行器判定用，语义同 AutoJs6 TimedTask）。 */
data class BibiTimedTaskInfo(
    val taskId: Long,
    val scriptPath: String,
    val millis: Long,
    val timeFlag: Long,
    val delayMs: Long
)

interface BibiHostScriptBridge {

    /**
     * 递归列出当前工作目录下的可执行脚本（.js/.auto）。
     * 调用方自行安排线程（扫描含 IO）。
     */
    fun listScripts(): List<BibiScriptInfo>

    /** 当前工作目录绝对路径（浏览器选择器根目录）。 */
    fun workingDirectory(): String

    /**
     * 列出目录的直接子项（文件夹 + 全部文件），不递归。
     * [dirPath] 必须位于工作目录内；文件夹在前、按名称排序。
     */
    fun listDirectory(dirPath: String): List<BibiScriptInfo>

    /**
     * 执行脚本，与文件列表「运行按钮」同链路（工作目录=脚本父目录）。
     * @return false = 脚本不存在或引擎启动失败
     */
    fun runScript(path: String): Boolean

    /** 脚本文件是否存在（保存校验用）。 */
    fun exists(path: String): Boolean

    /**
     * 循环执行脚本，与「循环运行」弹窗确认按钮同链路（Scripts.runRepeatedly）。
     * @return false = 脚本不存在或启动失败
     */
    fun runRepeatedly(scriptPath: String, loopTimes: Int, delayMs: Long, intervalMs: Long): Boolean

    /** 该脚本是否已有正在运行的引擎实例（重复命中拒绝判定用）。 */
    fun isScriptRunning(scriptPath: String): Boolean

    /**
     * 弹出与文件列表「循环运行」相同的参数弹窗；确认后经 [onConfirm] 回传参数。
     * 设置了 onConfirm 时弹窗只采集不执行（真实执行由调用方另行转发）。
     * @return false = 无法弹出（如 activity 无效）
     */
    fun showLoopConfigDialog(
        activity: android.app.Activity,
        scriptPath: String,
        prefill: BibiLoopConfig?,
        onConfirm: (loopTimes: Int, delayMs: Long, intervalMs: Long) -> Unit
    ): Boolean

    /** 打开与文件列表「定时任务」相同的创建页（TimedTaskSettingActivity，创建模式）。 */
    fun timedTaskCreateIntent(scriptPath: String): android.content.Intent?

    /** 打开定时任务编辑页（按任务 id）。 */
    fun timedTaskEditIntent(taskId: Long): android.content.Intent?

    fun getTimedTaskInfo(taskId: Long): BibiTimedTaskInfo?

    /** 该脚本路径下最新创建的定时任务（编辑页返回后绑定用）。 */
    fun newestTimedTaskForPath(scriptPath: String): BibiTimedTaskInfo?

    /** 按（脚本路径+触发时间+重复标志）三元组查找任务（重复命中拒绝判定用）。 */
    fun findTimedTaskByIdentity(scriptPath: String, millis: Long, timeFlag: Long): BibiTimedTaskInfo?

    /**
     * 创建并调度定时任务（TimedTaskManager.addTaskSync，Alarm 调度、重启恢复）。
     * @return 新任务 id；-1 = 失败
     */
    fun createTimedTask(scriptPath: String, millis: Long, timeFlag: Long, delayMs: Long): Long

    fun removeTimedTask(taskId: Long): Boolean
}

object ScriptHost {

    /** 宿主在应用初始化时注册；未注册时执行面跳过并打日志（不影响识别链路）。 */
    @Volatile
    var bridge: BibiHostScriptBridge? = null
}
