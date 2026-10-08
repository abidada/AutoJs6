package com.ai.assistance.operit.core.tools.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult

/**
 * Implemented by StandardUITools to feed the UI-automation subagent's action loop.
 *
 * Operit port: this interface was upstream a member of PhoneAgent.kt; the PhoneAgent loop
 * itself (virtual-screen automation via the trimmed stack) is not ported, so the interface
 * was extracted verbatim to keep StandardUITools compiling against upstream diffs.
 */
interface ToolImplementations {
    suspend fun tap(tool: AITool): ToolResult
    suspend fun longPress(tool: AITool): ToolResult
    suspend fun setInputText(tool: AITool): ToolResult
    suspend fun swipe(tool: AITool): ToolResult
    suspend fun pressKey(tool: AITool): ToolResult
    suspend fun captureScreenshot(tool: AITool): Pair<String?, Pair<Int, Int>?>
    suspend fun captureScreenshotBitmap(tool: AITool): Pair<Bitmap?, Pair<Int, Int>?> {
        val (filePath, dimensions) = captureScreenshot(tool)
        if (filePath == null) {
            return Pair(null, dimensions)
        }

        val bitmap = BitmapFactory.decodeFile(filePath) ?: return Pair(null, dimensions)
        val resolvedDimensions = dimensions ?: Pair(bitmap.width, bitmap.height)
        return Pair(bitmap, resolvedDimensions)
    }
}
