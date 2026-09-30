package org.autojs.autojs.ui.settings

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import com.brycewg.asrkb.ui.SettingsActivity
import org.autojs.autojs.theme.preference.MaterialPreference

/**
 * Launches the BIBI (说点啥) Compose settings screen provided by the :modules:bibi library.
 *
 * Added by the bibi port on Sep 30, 2026.
 */
class BibiSettingsPreference : MaterialPreference {

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context) : super(context)

    override fun onClick() {
        context.startActivity(Intent(context, SettingsActivity::class.java))
        super.onClick()
    }

}
