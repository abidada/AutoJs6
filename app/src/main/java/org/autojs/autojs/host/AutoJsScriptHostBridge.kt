/**
 * AutoJs6 宿主侧脚本桥接实现：语音分发执行面（bibi）与脚本选择器的数据/执行来源。
 *
 * 归属模块：host
 */
package org.autojs.autojs.host

import android.content.Intent
import com.brycewg.asrkb.host.BibiHostScriptBridge
import com.brycewg.asrkb.host.BibiLoopConfig
import com.brycewg.asrkb.host.BibiScriptInfo
import com.brycewg.asrkb.host.BibiTimedTaskInfo
import org.autojs.autojs.AutoJs
import org.autojs.autojs.app.GlobalAppContext
import org.autojs.autojs.execution.ExecutionConfig
import org.autojs.autojs.external.ScriptIntents
import org.autojs.autojs.model.script.ScriptFile
import org.autojs.autojs.model.script.Scripts
import org.autojs.autojs.timing.TimedTask
import org.autojs.autojs.timing.TimedTaskManager
import org.autojs.autojs.ui.common.ScriptLoopDialog
import org.autojs.autojs.ui.timing.TimedTaskSettingActivity
import org.autojs.autojs.util.WorkingDirectoryUtils
import java.io.File

object AutoJsScriptHostBridge : BibiHostScriptBridge {

    override fun listScripts(): List<BibiScriptInfo> {
        val rootDir = File(WorkingDirectoryUtils.path)
        if (!rootDir.isDirectory) return emptyList()
        val result = mutableListOf<BibiScriptInfo>()
        rootDir.walkTopDown().forEach { file ->
            // FILE_FILTER 同时放行目录，这里只收脚本文件
            if (file.isFile && Scripts.FILE_FILTER.accept(file)) {
                result.add(
                    file.toScriptInfo(rootDir)
                )
            }
        }
        return result.sortedWith(compareBy({ it.relativeDir }, { it.name }))
    }

    override fun workingDirectory(): String = WorkingDirectoryUtils.path

    override fun listDirectory(dirPath: String): List<BibiScriptInfo> = try {
        val rootDir = File(WorkingDirectoryUtils.path)
        val dir = File(dirPath)
        if (!dir.isDirectory || !dir.canonicalPath.startsWith(rootDir.canonicalPath)) {
            emptyList()
        } else {
            dir.listFiles()
                ?.map { it.toScriptInfo(rootDir) }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .orEmpty()
        }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun File.toScriptInfo(rootDir: File): BibiScriptInfo {
        val isDir = isDirectory
        val relativeParent = try {
            parentFile?.relativeToOrSelf(rootDir)?.invariantSeparatorsPath ?: ""
        } catch (_: Throwable) {
            ""
        }
        return BibiScriptInfo(
            name = name,
            path = absolutePath,
            relativeDir = relativeParent,
            lastModified = lastModified(),
            isDirectory = isDir,
            size = if (isDir) 0L else length(),
            isScript = !isDir && Scripts.FILE_FILTER.accept(this)
        )
    }

    override fun runScript(path: String): Boolean = try {
        Scripts.run(GlobalAppContext.get(), ScriptFile(path)) != null
    } catch (t: Throwable) {
        false
    }

    override fun exists(path: String): Boolean = try {
        File(path).isFile
    } catch (t: Throwable) {
        false
    }

    override fun runRepeatedly(
        scriptPath: String,
        loopTimes: Int,
        delayMs: Long,
        intervalMs: Long
    ): Boolean = try {
        Scripts.runRepeatedly(ScriptFile(scriptPath), loopTimes, delayMs, intervalMs)
        true
    } catch (t: Throwable) {
        false
    }

    override fun isScriptRunning(scriptPath: String): Boolean = try {
        val target = File(scriptPath).absolutePath
        AutoJs.instance.scriptEngineService.getEngines().any { engine ->
            val source = engine.getTag(org.autojs.autojs.engine.ScriptEngine.TAG_SOURCE)
                as? org.autojs.autojs.script.JavaScriptFileSource
            source?.file?.absolutePath == target
        }
    } catch (t: Throwable) {
        false
    }

    override fun showLoopConfigDialog(
        activity: android.app.Activity,
        scriptPath: String,
        prefill: BibiLoopConfig?,
        onConfirm: (loopTimes: Int, delayMs: Long, intervalMs: Long) -> Unit
    ): Boolean = try {
        val prefillArray = prefill?.let {
            longArrayOf(it.loopTimes.toLong(), it.loopDelayMs, it.loopIntervalMs)
        }
        ScriptLoopDialog(activity, ScriptFile(scriptPath), prefillArray) { times, delayMs, intervalMs ->
            onConfirm(times, delayMs, intervalMs)
        }.show()
        true
    } catch (t: Throwable) {
        false
    }

    // ==================== 定时任务（TimedTaskManager 同链路） ====================

    private fun toTimedTaskInfo(task: TimedTask): BibiTimedTaskInfo = BibiTimedTaskInfo(
        taskId = task.id,
        scriptPath = task.scriptPath,
        millis = task.millis,
        timeFlag = task.timeFlag,
        delayMs = task.delay
    )

    override fun timedTaskCreateIntent(scriptPath: String): android.content.Intent? = try {
        Intent(GlobalAppContext.get(), TimedTaskSettingActivity::class.java)
            .putExtra(ScriptIntents.EXTRA_KEY_PATH, scriptPath)
    } catch (t: Throwable) {
        null
    }

    override fun timedTaskEditIntent(taskId: Long): android.content.Intent? = try {
        Intent(GlobalAppContext.get(), TimedTaskSettingActivity::class.java)
            .putExtra(TimedTaskSettingActivity.EXTRA_TASK_ID, taskId)
    } catch (t: Throwable) {
        null
    }

    override fun getTimedTaskInfo(taskId: Long): BibiTimedTaskInfo? = try {
        TimedTaskManager.getTimedTask(taskId)?.let { toTimedTaskInfo(it) }
    } catch (t: Throwable) {
        null
    }

    override fun newestTimedTaskForPath(scriptPath: String): BibiTimedTaskInfo? = try {
        TimedTaskManager.allTasksAsList
            .filter { it.scriptPath == scriptPath }
            .maxByOrNull { it.id }
            ?.let { toTimedTaskInfo(it) }
    } catch (t: Throwable) {
        null
    }

    override fun findTimedTaskByIdentity(
        scriptPath: String,
        millis: Long,
        timeFlag: Long
    ): BibiTimedTaskInfo? = try {
        TimedTaskManager.allTasksAsList
            .firstOrNull { it.scriptPath == scriptPath && it.millis == millis && it.timeFlag == timeFlag }
            ?.let { toTimedTaskInfo(it) }
    } catch (t: Throwable) {
        null
    }

    override fun createTimedTask(
        scriptPath: String,
        millis: Long,
        timeFlag: Long,
        delayMs: Long
    ): Long = try {
        val task = TimedTask(
            millis,
            timeFlag,
            scriptPath,
            ExecutionConfig(workingDirectory = File(scriptPath).parent ?: "")
        )
        task.setDelay(delayMs)
        TimedTaskManager.addTaskSync(task)
        task.id
    } catch (t: Throwable) {
        -1L
    }

    override fun removeTimedTask(taskId: Long): Boolean = try {
        val task = TimedTaskManager.getTimedTask(taskId) ?: return false
        TimedTaskManager.removeTaskSync(task)
    } catch (t: Throwable) {
        false
    }
}
