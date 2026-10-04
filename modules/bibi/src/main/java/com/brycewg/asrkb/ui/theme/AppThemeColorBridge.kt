/**
 * app 宿主主题桥:bibi 跟随 app(com.xiaoyu.ai)主题色与夜模式的唯一读取点。
 *
 * app 的 ThemeColorManager 将用户主题色持久化到默认 SharedPreferences
 * (键 key_$_theme_color_primary 等,int);夜模式经 AppCompatDelegate 全局单例生效。
 * bibi 与 app 同进程同 APK,按默认规则取同一 prefs 实例,直读 + 注册监听即可实时跟随,app 模块零改动。
 *
 * 色板推导约定:Compose 通道用 Miuix ThemeController(MonetSystem + keyColor=种子色),
 * View 通道用同源 material-color-utilities SchemeTonalSpot,两者像素级一致。
 *
 * 归属模块:ui/theme
 */
package com.brycewg.asrkb.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.CopyOnWriteArraySet

object AppThemeColorBridge {

    /**
     * 宿主默认 SharedPreferences 文件名(PreferenceManager.getDefaultSharedPreferences 的默认规则)。
     * bibi 无 androidx.preference 依赖,按同规则取同一实例——SharedPreferencesImpl 在同进程内按
     * 文件名缓存,app 的 Pref.kt 写入的正是这一实例,监听因此可用。
     */
    private fun defaultPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)

    /** app 出厂默认主题色(#00695C / teal-800),宿主尚未配置过主题色时回退 */
    val FALLBACK_SEED_COLOR: Int = 0xFF00695C.toInt()

    private const val KEY_THEME_COLOR_PRIMARY = "key_\$_theme_color_primary"

    /** 当前主题色种子(ARGB)。app 内换色实时更新;Compose/View 两侧共同读取。 */
    val seedColor = mutableIntStateOf(FALLBACK_SEED_COLOR)

    /** app 夜模式覆盖:null=跟随系统;true/false=app 强制暗/亮。 */
    val darkOverride = mutableStateOf<Boolean?>(null)

    /** View 层变更监听(悬浮层刷新用);回调在主线程。 */
    private val listeners = CopyOnWriteArraySet<(seed: Int, darkOverride: Boolean?) -> Unit>()

    @Volatile
    private var initialized = false

    /**
     * prefs 变更监听。SharedPreferencesImpl 对 listener 只持弱引用,
     * 必须由本单例强持有,否则注册后随即被 GC 回收、监听失效。
     */
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * 幂等初始化:立即解析当前值并监听后续变化。
     * 消费方(主题解析/Activity/服务)无需显式调用,取色入口内部会调用。
     */
    fun initialize(context: Context) {
        val appContext = context.applicationContext ?: return
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val prefs = defaultPrefs(appContext)
            refresh(appContext, prefs)
            // app 主题色/夜模式键写入即回调(同进程同实例);其余键的变化经 refresh 差异检查后无副作用
            prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, _ ->
                refresh(appContext, sp)
            }.also { prefs.registerOnSharedPreferenceChangeListener(it) }
            initialized = true
        }
    }

    /** 解析当前是否暗色:app 夜模式覆盖优先,否则跟随系统配置。 */
    fun isDark(context: Context): Boolean = darkOverride.value ?: isSystemDark(context)

    fun isSystemDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun addThemeListener(listener: (seed: Int, darkOverride: Boolean?) -> Unit) {
        listeners.add(listener)
    }

    fun removeThemeListener(listener: (seed: Int, darkOverride: Boolean?) -> Unit) {
        listeners.remove(listener)
    }

    private fun refresh(
        appContext: Context,
        prefs: SharedPreferences = defaultPrefs(appContext)
    ) {
        val seed = prefs.getInt(KEY_THEME_COLOR_PRIMARY, FALLBACK_SEED_COLOR)
        val dark = when (AppCompatDelegate.getDefaultNightMode()) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> null
        }
        if (seed == seedColor.intValue && dark == darkOverride.value) return
        seedColor.intValue = seed
        darkOverride.value = dark
        listeners.forEach { it(seed, dark) }
    }
}
