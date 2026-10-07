package org.autojs.autojs.mcp

import android.content.Context
import android.util.Log
import org.autojs.autojs.mcp.tool.ToolDefinition
import org.autojs.autojs.mcp.tool.ToolRegistry
import org.autojs.autojs.mcp.tool.ToolSchemas
import org.autojs.autojs.mcp.tools.AppControlTool
import org.autojs.autojs.mcp.tools.CancelJobTool
import org.autojs.autojs.mcp.tools.DeleteScriptTool
import org.autojs.autojs.mcp.tools.DeviceInfoTool
import org.autojs.autojs.mcp.tools.FindElementTool
import org.autojs.autojs.mcp.tools.FindElementsTool
import org.autojs.autojs.mcp.tools.GetCurrentActivityTool
import org.autojs.autojs.mcp.tools.GetForegroundAppTool
import org.autojs.autojs.mcp.tools.GetRecentScreenshotTool
import org.autojs.autojs.mcp.tools.GetUiTreeTool
import org.autojs.autojs.mcp.tools.JobStatusTool
import org.autojs.autojs.mcp.tools.JobTracker
import org.autojs.autojs.mcp.tools.ListAppsTool
import org.autojs.autojs.mcp.tools.ListSamplesTool
import org.autojs.autojs.mcp.tools.ListScriptDirsTool
import org.autojs.autojs.mcp.tools.ListScriptsTool
import org.autojs.autojs.mcp.tools.LogsTool
import org.autojs.autojs.mcp.tools.McpToolContext
import org.autojs.autojs.mcp.tools.OcrTool
import org.autojs.autojs.mcp.tools.ReadSampleTool
import org.autojs.autojs.mcp.tools.ReadScriptTool
import org.autojs.autojs.mcp.tools.RenameScriptTool
import org.autojs.autojs.mcp.tools.RunScriptTool
import org.autojs.autojs.mcp.tools.RunScriptFileTool
import org.autojs.autojs.mcp.tools.SaveScriptTool
import org.autojs.autojs.mcp.tools.ScreenshotTool
import org.autojs.autojs.mcp.tools.SwipeTool
import org.autojs.autojs.mcp.tools.TapTool
import org.autojs.autojs.mcp.tools.UpdateScriptTool

/**
 * Facade to start/stop MCP server with default tools registered.
 */
class McpService(private val context: Context) {
    private val tracker = JobTracker()
    private val registry = ToolRegistry()
    private val runtimeProvider = McpRuntimeProvider()
    private val screenshotStore = ScreenshotStore()
    @Volatile
    private var currentConfig: McpConfig = McpConfig()
    private val toolContext = McpToolContext(
        appContext = context.applicationContext,
        runtimeProvider = runtimeProvider,
        screenshotStore = screenshotStore
    ) { currentConfig }
    private var server: McpServer? = null
    private val startStopLock = Any()

    init {
        registerDefaultTools()
    }

    fun start(config: McpConfig) {
        currentConfig = config
        if (!config.enabled) {
            stop()
            return
        }
        synchronized(startStopLock) {
            if (server == null) {
                server = McpServer(context.applicationContext, registry)
            }
            server?.start(config)
        }
        Log.i(TAG, "MCP service started")
    }

    fun stop() {
        synchronized(startStopLock) {
            server?.stop()
            runtimeProvider.close()
        }
        Log.i(TAG, "MCP service stopped")
    }

    private fun registerDefaultTools() {
        registry.apply {
            register(
                ToolDefinition(
                    name = "device_info",
                    title = "Device Info",
                    description = "Return device information and locale.",
                    inputSchema = ToolSchemas.emptyObject()
                ),
                DeviceInfoTool()
            )
            register(
                ToolDefinition(
                    name = "run_script",
                    title = "Run Script",
                    description = "Run an AutoJs6 (Rhino) script and return a job id. " +
                        "Scripts starting with \"ui\"; open a UI activity on the device.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "script" to ToolSchemas.stringSchema("JavaScript source to execute."),
                            "name" to ToolSchemas.stringSchema("Optional job name."),
                            "timeoutMillis" to ToolSchemas.intSchema("Optional timeout in milliseconds (not enforced yet).")
                        ),
                        required = listOf("script")
                    )
                ),
                RunScriptTool(context, tracker)
            )
            register(
                ToolDefinition(
                    name = "job_status",
                    title = "Job Status",
                    description = "Get status for a script job.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf("jobId" to ToolSchemas.intSchema("Job id.")),
                        required = listOf("jobId")
                    )
                ),
                JobStatusTool(tracker)
            )
            register(
                ToolDefinition(
                    name = "cancel_job",
                    title = "Cancel Job",
                    description = "Cancel one running MCP job and wait for its script engine to stop.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf("jobId" to ToolSchemas.intSchema("Job id.")),
                        required = listOf("jobId")
                    )
                ),
                CancelJobTool(tracker)
            )
            register(
                ToolDefinition(
                    name = "logs",
                    title = "Logs",
                    description = "Get recent job statuses.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf("recent" to ToolSchemas.intSchema("Max number of log entries.")),
                        required = emptyList()
                    )
                ),
                LogsTool(tracker)
            )
            register(
                ToolDefinition(
                    name = "list_apps",
                    title = "List Apps",
                    description = "List installed apps with optional filtering.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "query" to ToolSchemas.stringSchema("Filter by package name or label."),
                            "includeSystem" to ToolSchemas.booleanSchema("Include system apps."),
                            "limit" to ToolSchemas.intSchema("Max number of results.")
                        )
                    )
                ),
                ListAppsTool(context)
            )
            register(
                ToolDefinition(
                    name = "save_script",
                    title = "Save Script",
                    description = "Save a script file into the AutoJs6 working directory.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "script" to ToolSchemas.stringSchema("JavaScript source to save."),
                            "name" to ToolSchemas.stringSchema("Optional script file name."),
                            "overwrite" to ToolSchemas.booleanSchema("Overwrite if file exists.")
                        ),
                        required = listOf("script")
                    )
                ),
                SaveScriptTool(context)
            )
            register(
                ToolDefinition(
                    name = "list_samples",
                    title = "List Samples",
                    description = "List built-in sample scripts shipped with AutoJs6.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "query" to ToolSchemas.stringSchema("Filter by sample path or name."),
                            "limit" to ToolSchemas.intSchema("Max number of results.")
                        )
                    )
                ),
                ListSamplesTool(context)
            )
            register(
                ToolDefinition(
                    name = "read_sample",
                    title = "Read Sample",
                    description = "Read a built-in sample script by path.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "path" to ToolSchemas.stringSchema("Sample path under assets sample directory.")
                        ),
                        required = listOf("path")
                    )
                ),
                ReadSampleTool(context)
            )
            register(
                ToolDefinition(
                    name = "read_script",
                    title = "Read Script",
                    description = "Read a script file from the AutoJs6 working directory.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "path" to ToolSchemas.stringSchema("Script file path relative to the working directory.")
                        ),
                        required = listOf("path")
                    )
                ),
                ReadScriptTool()
            )
            register(
                ToolDefinition(
                    name = "update_script",
                    title = "Update Script",
                    description = "Overwrite an existing script file in the AutoJs6 working directory.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "path" to ToolSchemas.stringSchema("Script file path relative to the working directory."),
                            "script" to ToolSchemas.stringSchema("JavaScript source to save.")
                        ),
                        required = listOf("path", "script")
                    )
                ),
                UpdateScriptTool()
            )
            register(
                ToolDefinition(
                    name = "delete_script",
                    title = "Delete Script",
                    description = "Delete a script file from the AutoJs6 working directory.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "path" to ToolSchemas.stringSchema("Script file path relative to the working directory.")
                        ),
                        required = listOf("path")
                    )
                ),
                DeleteScriptTool()
            )
            register(
                ToolDefinition(
                    name = "rename_script",
                    title = "Rename Script",
                    description = "Rename a script file in the AutoJs6 working directory.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "path" to ToolSchemas.stringSchema("Script file path relative to the working directory."),
                            "newName" to ToolSchemas.stringSchema("New script file name.")
                        ),
                        required = listOf("path", "newName")
                    )
                ),
                RenameScriptTool()
            )
            register(
                ToolDefinition(
                    name = "run_script_file",
                    title = "Run Script File",
                    description = "Run a script file from the AutoJs6 working directory or an absolute path.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "path" to ToolSchemas.stringSchema("Script file path or name under the working directory."),
                            "name" to ToolSchemas.stringSchema("Optional job name.")
                        ),
                        required = listOf("path")
                    )
                ),
                RunScriptFileTool(context, tracker)
            )
            register(
                ToolDefinition(
                    name = "list_scripts",
                    title = "List Scripts",
                    description = "List scripts from the AutoJs6 working directory.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "query" to ToolSchemas.stringSchema("Filter by file name or path."),
                            "limit" to ToolSchemas.intSchema("Max number of results."),
                            "recursive" to ToolSchemas.booleanSchema("List subdirectories.")
                        )
                    )
                ),
                ListScriptsTool()
            )
            register(
                ToolDefinition(
                    name = "list_script_dirs",
                    title = "List Script Dirs",
                    description = "List directories under the AutoJs6 working directory.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "query" to ToolSchemas.stringSchema("Filter by directory name or path."),
                            "limit" to ToolSchemas.intSchema("Max number of results."),
                            "recursive" to ToolSchemas.booleanSchema("List subdirectories.")
                        )
                    )
                ),
                ListScriptDirsTool()
            )
            register(
                ToolDefinition(
                    name = "tap",
                    title = "Tap",
                    description = "Tap on screen at the given coordinates (requires accessibility service).",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "x" to ToolSchemas.intSchema("X coordinate."),
                            "y" to ToolSchemas.intSchema("Y coordinate.")
                        ),
                        required = listOf("x", "y")
                    )
                ),
                TapTool(toolContext)
            )
            register(
                ToolDefinition(
                    name = "swipe",
                    title = "Swipe",
                    description = "Swipe from one coordinate to another (requires accessibility service).",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "x1" to ToolSchemas.intSchema("Start X coordinate."),
                            "y1" to ToolSchemas.intSchema("Start Y coordinate."),
                            "x2" to ToolSchemas.intSchema("End X coordinate."),
                            "y2" to ToolSchemas.intSchema("End Y coordinate."),
                            "duration" to ToolSchemas.intSchema("Duration in milliseconds.")
                        ),
                        required = listOf("x1", "y1", "x2", "y2")
                    )
                ),
                SwipeTool(toolContext)
            )
            register(
                ToolDefinition(
                    name = "find_element",
                    title = "Find Element",
                    description = "Find a UI element by text, id, desc, or className.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "text" to ToolSchemas.stringSchema("Match element text."),
                            "id" to ToolSchemas.stringSchema("Match resource id."),
                            "desc" to ToolSchemas.stringSchema("Match content description."),
                            "className" to ToolSchemas.stringSchema("Match class name."),
                            "timeoutMillis" to ToolSchemas.intSchema("Search timeout in milliseconds.")
                        )
                    )
                ),
                FindElementTool(toolContext)
            )
            register(
                ToolDefinition(
                    name = "find_elements",
                    title = "Find Elements",
                    description = "Find multiple UI elements by text, id, desc, or className.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "text" to ToolSchemas.stringSchema("Match element text."),
                            "id" to ToolSchemas.stringSchema("Match resource id."),
                            "desc" to ToolSchemas.stringSchema("Match content description."),
                            "className" to ToolSchemas.stringSchema("Match class name."),
                            "timeoutMillis" to ToolSchemas.intSchema("Search timeout in milliseconds."),
                            "limit" to ToolSchemas.intSchema("Max number of results.")
                        )
                    )
                ),
                FindElementsTool(toolContext)
            )
            register(
                ToolDefinition(
                    name = "screenshot",
                    title = "Screenshot",
                    description = "Capture a screenshot (Android 11+, no extra permission) and store it on device.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf("asBase64" to ToolSchemas.booleanSchema("Include base64 in response."))
                    )
                ),
                ScreenshotTool(toolContext)
            )
            register(
                ToolDefinition(
                    name = "ocr",
                    title = "OCR",
                    description = "Run OCR (MLKit, Chinese) on an image file or a fresh screenshot.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "source" to ToolSchemas.stringSchema("Use 'screenshot' to capture and recognize now (Android 11+)."),
                            "path" to ToolSchemas.stringSchema("Absolute file path to image."),
                            "language" to ToolSchemas.stringSchema("Ignored; MLKit Chinese recognizer is used.")
                        )
                    )
                ),
                OcrTool(toolContext)
            )
            register(
                ToolDefinition(
                    name = "app_control",
                    title = "App Control",
                    description = "Launch an app, or force-stop it (force_stop requires root).",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "action" to ToolSchemas.stringSchema("launch | bring_to_front | force_stop"),
                            "packageName" to ToolSchemas.stringSchema("Target package name.")
                        ),
                        required = listOf("action", "packageName")
                    )
                ),
                AppControlTool(toolContext)
            )
            register(
                ToolDefinition(
                    name = "get_foreground_app",
                    title = "Get Foreground App",
                    description = "Get the foreground package name.",
                    inputSchema = ToolSchemas.emptyObject()
                ),
                GetForegroundAppTool(toolContext)
            )
            register(
                ToolDefinition(
                    name = "get_current_activity",
                    title = "Get Current Activity",
                    description = "Get the current activity class name.",
                    inputSchema = ToolSchemas.emptyObject()
                ),
                GetCurrentActivityTool(toolContext)
            )
            register(
                ToolDefinition(
                    name = "get_ui_tree",
                    title = "Get UI Tree",
                    description = "Capture the current UI tree in a compact JSON format (interactive nodes only).",
                    inputSchema = ToolSchemas.emptyObject()
                ),
                GetUiTreeTool(toolContext)
            )
            register(
                ToolDefinition(
                    name = "get_recent_screenshot",
                    title = "Get Recent Screenshot",
                    description = "Return metadata (and optional base64) of the most recent screenshot.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf("asBase64" to ToolSchemas.booleanSchema("Include base64 in response."))
                    )
                ),
                GetRecentScreenshotTool(toolContext)
            )
        }
    }

    companion object {
        private const val TAG = "McpService"
    }
}
