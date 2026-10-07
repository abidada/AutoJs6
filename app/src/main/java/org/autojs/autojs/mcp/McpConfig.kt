package org.autojs.autojs.mcp

/**
 * Configuration for the MCP in-app server.
 *
 * Ported from eness-1/AutoX `:mcp` module.
 * Unlike the AutoX fork (whose default host 0.0.0.0 exposed the server to the LAN),
 * the default here is loopback-only; enabling LAN access is an explicit choice.
 */
data class McpConfig(
    val enabled: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 27190,
    val token: String? = null,
    val allowBase64: Boolean = false,
    val allowNetwork: Boolean = true
)
