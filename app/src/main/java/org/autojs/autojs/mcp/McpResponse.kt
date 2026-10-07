package org.autojs.autojs.mcp

/**
 * Standardized response envelope for MCP tools.
 */
data class McpResponse(
    val ok: Boolean,
    val data: Any? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null
) {
    companion object {
        fun ok(data: Any? = null) = McpResponse(true, data, null, null)
        fun error(code: String, message: String) = McpResponse(false, null, code, message)
    }
}
