package org.autojs.autojs.mcp

import android.content.Context
import android.content.pm.PackageInfo
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.autojs.autojs.mcp.tool.ToolRegistry
import java.net.NetworkInterface

/**
 * Lightweight MCP server wrapper based on Ktor (CIO engine).
 *
 * Ported from eness-1/AutoX `:mcp` module. The original used the Netty engine and
 * installed (but never used) the WebSockets plugin; CIO is pure JVM and lighter,
 * and the unused plugin install is dropped.
 */
class McpServer(
    private val appContext: Context,
    private val registry: ToolRegistry
) {
    private val gson = Gson()
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jsonRpcHandler = McpJsonRpcHandler(
        registry,
        serverInfoProvider = { buildServerInfo() }
    )

    val isRunning: Boolean
        get() = engine != null

    fun start(config: McpConfig) {
        stop()
        if (!config.enabled) return
        Log.i(TAG, "Starting MCP server on ${config.host}:${config.port}")
        try {
            engine = embeddedServer(CIO, port = config.port, host = config.host) {
                routing {
                    post("/mcp") {
                        try {
                            if (!isOriginAllowed(config, call.request.headers["Origin"])) {
                                Log.d(TAG, "Origin not allowed")
                                call.respond(HttpStatusCode.Forbidden)
                                return@post
                            }

                            if (!authorize(config, call.request.headers["X-Token"])) {
                                Log.d(TAG, "Unauthorized")
                                call.respond(HttpStatusCode.Unauthorized)
                                return@post
                            }

                            val body = call.receiveText()
                            Log.d(TAG, "Received body: $body")
                            val response = jsonRpcHandler.handleText(body)
                            Log.d(TAG, "Response status: ${response.status}, body: ${response.body}")
                            if (response.body == null) {
                                call.respond(response.status)
                            } else {
                                val jsonResponse = gson.toJson(response.body)
                                call.respondText(
                                    jsonResponse,
                                    ContentType.Application.Json,
                                    response.status
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling POST /mcp", e)
                            call.respond(HttpStatusCode.InternalServerError)
                        }
                    }

                    get("/mcp") {
                        call.respond(HttpStatusCode.MethodNotAllowed)
                    }

                    get("/") {
                        call.respondText("MCP Server Running", ContentType.Text.Plain)
                    }
                }
            }.also { engine ->
                Log.i(TAG, "Engine created, starting...")
                engine.start(wait = false)
                Log.i(TAG, "Engine started successfully")
            }
            Log.i(TAG, "MCP server started on ${config.host}:${config.port}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MCP server", e)
        }
    }

    fun stop() {
        try {
            engine?.stop(1000, 2000)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        } finally {
            engine = null
        }
    }

    private fun authorize(config: McpConfig, tokenHeader: String?): Boolean {
        val expected = config.token
        return expected.isNullOrBlank() || expected == tokenHeader
    }

    private fun isOriginAllowed(config: McpConfig, originHeader: String?): Boolean {
        if (originHeader.isNullOrBlank()) {
            return true
        }
        val host = try {
            Uri.parse(originHeader).host
        } catch (_: Exception) {
            null
        } ?: return false

        val allowedHosts = if (config.allowNetwork) {
            localHosts()
        } else {
            setOf("localhost", "127.0.0.1", "::1")
        }
        return allowedHosts.contains(host)
    }

    private fun localHosts(): Set<String> {
        val hosts = mutableSetOf("localhost", "127.0.0.1", "::1")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    val host = address.hostAddress?.substringBefore('%')
                    if (!host.isNullOrBlank()) {
                        hosts.add(host)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve local addresses", e)
        }
        return hosts
    }

    private fun buildServerInfo(): McpServerInfo {
        val name = "AutoJs6 MCP"
        val versionName = try {
            val info: PackageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            info.versionName
        } catch (_: Exception) {
            null
        }
        return McpServerInfo(
            name = name,
            title = name,
            version = versionName,
            description = "AutoJs6 embedded MCP tools server"
        )
    }

    companion object {
        private const val TAG = "McpServer"
    }
}
