package org.autojs.autojs.ui.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.EdgeEffect
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import org.autojs.autojs.mcp.McpPrefKeys
import org.autojs.autojs.mcp.McpServerService
import org.autojs.autojs.theme.ThemeColorManager
import org.autojs.autojs.util.ViewUtils.excludePaddingClippableViewFromBottomNavigationBar
import com.xiaoyu.ai.R

class PreferencesFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.fragment_preferences, rootKey)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.edgeEffectFactory = object : RecyclerView.EdgeEffectFactory() {
            override fun createEdgeEffect(recyclerView: RecyclerView, direction: Int): EdgeEffect {
                return EdgeEffect(recyclerView.context).apply {
                    color = ThemeColorManager.colorPrimary
                }
            }
        }
        listView.isHorizontalScrollBarEnabled = false
        listView.isVerticalScrollBarEnabled = false
        listView.excludePaddingClippableViewFromBottomNavigationBar()
    }

    override fun onStart() {
        super.onStart()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStop() {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(this)
        super.onStop()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            McpPrefKeys.KEY_ENABLED -> {
                val context = context ?: return
                val enabled = sharedPreferences?.getBoolean(McpPrefKeys.KEY_ENABLED, false) ?: false
                if (enabled) {
                    McpServerService.start(context)
                } else {
                    McpServerService.stop(context)
                }
            }
            // Other MCP keys (host/port/token/…) are hot-reloaded by McpServerService
            // through its own preference listener; nothing to do here.
        }
    }
}
