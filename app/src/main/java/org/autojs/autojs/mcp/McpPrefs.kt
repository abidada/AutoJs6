package org.autojs.autojs.mcp

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Loads [McpConfig] from the default shared preferences (the same file AutoJs6's
 * `Pref` singleton and the settings `PreferenceFragmentCompat` use).
 *
 * AutoJs6 is single-process, so no MODE_MULTI_PROCESS is needed (unlike AutoX).
 */
object McpPrefs {
    private const val DEFAULT_HOST = "127.0.0.1"
    private const val LOCAL_HOST = "127.0.0.1"
    private val LOCAL_HOSTS = setOf(LOCAL_HOST, "localhost", "::1")

    private fun getSharedPreferences(context: Context): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(context)
    }

    fun load(context: Context): McpConfig {
        val prefs = getSharedPreferences(context)
        val enabled = prefs.getBoolean(McpPrefKeys.KEY_ENABLED, false)
        val allowBase64 = prefs.getBoolean(McpPrefKeys.KEY_ALLOW_BASE64, false)
        val host = prefs.getString(McpPrefKeys.KEY_HOST, DEFAULT_HOST).orEmpty().ifBlank { DEFAULT_HOST }
        val allowNetworkPref = prefs.getBoolean(McpPrefKeys.KEY_ALLOW_NETWORK, true)
        // A non-loopback host only makes sense with LAN access; host wins over the switch.
        val allowNetwork = allowNetworkPref || !LOCAL_HOSTS.contains(host)
        val port = prefs.getString(McpPrefKeys.KEY_PORT, DEFAULT_PORT.toString())
            ?.toIntOrNull()
            ?.coerceIn(1, 65535)
            ?: DEFAULT_PORT
        val token = prefs.getString(McpPrefKeys.KEY_TOKEN, null)?.trim().orEmpty().ifBlank { null }
        val finalHost = if (allowNetwork) host else LOCAL_HOST

        return McpConfig(
            enabled = enabled,
            host = finalHost,
            port = port,
            token = token,
            allowBase64 = allowBase64,
            allowNetwork = allowNetwork
        )
    }

    private const val DEFAULT_PORT = 27190
}
