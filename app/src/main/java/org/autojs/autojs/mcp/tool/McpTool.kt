package org.autojs.autojs.mcp.tool

import com.google.gson.JsonObject
import org.autojs.autojs.mcp.McpResponse

/**
 * A single MCP tool handler.
 */
fun interface McpTool {
    suspend fun handle(params: JsonObject?): McpResponse
}
