package org.autojs.autojs.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.EdgeEffect
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import org.autojs.autojs.theme.ThemeColorManager
import org.autojs.autojs.util.ViewUtils.excludePaddingClippableViewFromBottomNavigationBar
import com.xiaoyu.ai.R

class PreferencesFragment : PreferenceFragmentCompat() {

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
}
