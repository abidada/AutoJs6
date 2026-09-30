# -*- coding: utf-8 -*-
"""R2: prefs custom json + service builder + manager screen + route wrapper."""
import io

R = []


def edit(path, repls):
    s = io.open(path, encoding='utf-8').read()
    for old, new in repls:
        if old not in s:
            R.append(('MISS', path.split('/')[-1] + ' | ' + old[:80].replace('\n', '\\n')))
            continue
        s = s.replace(old, new, 1)
        R.append(('ok', path.split('/')[-1] + ' | ' + old[:60].replace('\n', '\\n')))
    io.open(path, 'w', encoding='utf-8', newline='').write(s)


# 1) Prefs: custom words json
edit('store/Prefs.kt', [
    ('''    // 选中的唤醒词显示名（如「小爱同学」）；空 = 全部预置词生效
    var wakeWordSelected: String
        get() = sp.getString(KEY_WAKE_WORD_SELECTED, "") ?: ""
        set(value) = sp.edit { putString(KEY_WAKE_WORD_SELECTED, value) }''',
     '''    // 选中的唤醒词显示名（如「小爱同学」）；空 = 全部预置词生效
    var wakeWordSelected: String
        get() = sp.getString(KEY_WAKE_WORD_SELECTED, "") ?: ""
        set(value) = sp.edit { putString(KEY_WAKE_WORD_SELECTED, value) }

    // 自定义唤醒词列表（JSON：[{name, tokens}]）
    var wakeWordCustomJson: String
        get() = sp.getString(KEY_WAKE_WORD_CUSTOM_JSON, "[]") ?: "[]"
        set(value) = sp.edit { putString(KEY_WAKE_WORD_CUSTOM_JSON, value) }'''),
])

edit('store/PrefsKeys.kt', [
    ('internal const val KEY_WAKE_WORD_SELECTED = "wake_word_selected"',
     '''internal const val KEY_WAKE_WORD_SELECTED = "wake_word_selected"
internal const val KEY_WAKE_WORD_CUSTOM_JSON = "wake_word_custom_json"'''),
])

# 2) Service: createEngine via store
edit('wake/WakeWordService.kt', [
    ('''    private fun createEngine(): KwsWakeEngine {
        val selected = try {
            prefs.wakeWordSelected
        } catch (e: Throwable) {
            ""
        }
        val keywords = if (selected.isBlank()) {
            null // 使用 keywords.txt 全部预置词
        } else {
            // 按 @显示名 过滤 keywords.txt 行，拼成运行时自定义关键词串
            val lines = assets.open("kws/wenetspeech-3.3M/keywords.txt").bufferedReader()
                .readLines()
                .filter { it.isNotBlank() }
            val picked = lines.filter { it.substringAfterLast('@') == selected }
            if (picked.isEmpty()) null else picked.joinToString("\\n")
        }
        return KwsWakeEngine(assets, keywords)
    }''',
     '''    private fun createEngine(): KwsWakeEngine {
        val selected = try {
            prefs.wakeWordSelected
        } catch (e: Throwable) {
            ""
        }
        val keywords = com.brycewg.asrkb.wake.WakeWordStore.buildActiveKeywords(this, selected)
        return KwsWakeEngine(assets, keywords)
    }'''),
])

# 3) FloatingSettingsScreen: signature + wake row opens manager; remove sheet funcs + charge row
edit('ui/settings/compose/screens/FloatingSettingsScreen.kt', [
    ('''fun FloatingSettingsScreen(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    actions: SettingsActionController
) {''',
     '''fun FloatingSettingsScreen(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    onOpenWakeManager: () -> Unit,
    actions: SettingsActionController
) {'''),
])

s = io.open('ui/settings/compose/screens/FloatingSettingsScreen.kt', encoding='utf-8').read()
# remove wake helper funcs (wakeKeywordOptions/wakeKeywordLabel/showWakeKeywordSheet)
a = s.index('    fun wakeKeywordOptions(): List<String>')
b = s.index('    fun showVolumeKeyModeSheet() {', a)
s = s[:a] + s[b:]
R.append(('cut', 'wake sheet helpers'))
# remove old wake section and insert new one (2 rows: master + keyword row)
a = s.index('            item("wake_word") {')
b = s.index('            item("compat") {', a)
new_section = '''            item("wake_word") {
                FloatingSection(uiMode = uiMode, titleRes = R.string.section_wake_word) {
                    FloatingExplainedSwitch(
                        id = "wake_word_enabled",
                        titleRes = R.string.label_wake_word_enabled,
                        checked = uiState.wakeWordEnabled,
                        onToggle = { target ->
                            if (target &&
                                androidx.core.content.ContextCompat.checkSelfPermission(
                                    context,
                                    android.Manifest.permission.RECORD_AUDIO
                                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                // 缺麦克风权限：路由到宿主权限区，不写开关
                                try {
                                    PermissionRouter.route(context, BibiPermissionType.MICROPHONE)
                                } catch (t: Throwable) {
                                    Log.e(FLOATING_TAG, "Failed to route microphone permission", t)
                                }
                            } else {
                                prefs.wakeWordEnabled = target
                                if (target) {
                                    com.brycewg.asrkb.wake.WakeWordService.start(context)
                                } else {
                                    com.brycewg.asrkb.wake.WakeWordService.stop(context)
                                }
                                refreshState()
                            }
                        },
                        index = 0,
                        count = 2
                    )
                    if (uiState.wakeWordEnabled) {
                        FloatingValuePreference(
                            titleRes = R.string.label_wake_word_selected,
                            value = if (prefs.wakeWordSelected.isBlank()) {
                                stringResource(R.string.wake_word_all)
                            } else {
                                prefs.wakeWordSelected
                            },
                            uiMode = uiMode,
                            index = 1,
                            count = 2,
                            onClick = onOpenWakeManager
                        )
                    }
                }
            }

'''
s = s[:a] + new_section + s[b:]
R.append(('ok', 'wake section rewritten (2 rows)'))
io.open('ui/settings/compose/screens/FloatingSettingsScreen.kt', 'w', encoding='utf-8', newline='').write(s)

# 4) SettingsRootScreen: Floating route -> wrapper
edit('ui/settings/compose/screens/SettingsRootScreen.kt', [
    ('''        BibiSettingsRoute.Floating -> FloatingSettingsScreen(
            uiMode = uiState.uiMode,
            onBack = { onPopRoute() },
            actions = actions
        )''',
     '''        BibiSettingsRoute.Floating -> FloatingSettingsRoute(
            uiMode = uiState.uiMode,
            onBack = { onPopRoute() },
            actions = actions
        )'''),
])

io.open('phase_r2.log', 'w', encoding='utf-8').write(chr(10).join(x[0] + ' ' + x[1] for x in R))
print('R2 done')
