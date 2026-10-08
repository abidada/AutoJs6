package org.autojs.autojs.ui.settings

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import com.ai.assistance.operit.ui.main.MainActivity
import org.autojs.autojs.theme.preference.MaterialPreference

/**
 * Launches the Operit (AI Agent) main screen provided by the :modules:operit library.
 *
 * 归属模块：宿主接线（P7.1 / D-4）
 *
 * The module activity is `android:exported="false"` (same-app starts only), so the host
 * drawer is the single entry point — same pattern as [BibiSettingsPreference].
 *
 * `MainActivity` lives in the Operit library and relies on the module bootstrap performed in
 * `App.onCreate` (`OperitLibrary.init`) having populated `OperitApplication.instance`; opening
 * the activity without that wiring degrades to a "not initialized" screen rather than crashing.
 */
class OperitMainPreference : MaterialPreference {

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context) : super(context)

    override fun onClick() {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        super.onClick()
    }

}
