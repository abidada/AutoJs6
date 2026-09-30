# -*- coding: utf-8 -*-
"""Phase B3: wake section UI in FloatingSettingsScreen + strings."""
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


# 1) helper: wake keyword list from assets + sheet function (after showShakeSensitivitySheet's zone; put before showVolumeKeyModeSheet usage)
edit('ui/settings/compose/screens/FloatingSettingsScreen.kt', [
    ('''    fun showVolumeKeyModeSheet() {''',
     '''    fun wakeKeywordOptions(): List<String> = try {
        val lines = appContext.assets.open("kws/wenetspeech-3.3M/keywords.txt")
            .bufferedReader().readLines()
            .filter { it.isNotBlank() }
        listOf("") + lines.map { it.substringAfterLast('@') }
    } catch (t: Throwable) {
        Log.e(FLOATING_TAG, "Failed to read wake keywords", t)
        listOf("")
    }

    fun wakeKeywordLabel(option: String): String = if (option.isBlank()) {
        context.getString(R.string.wake_word_all)
    } else {
        option
    }

    fun showWakeKeywordSheet() {
        val options = wakeKeywordOptions()
        val current = prefs.wakeWordSelected
        val selectedIndex = options.indexOf(current).takeIf { it >= 0 } ?: 0
        choiceSheet = settingsChoiceSheetState(
            title = context.getString(R.string.label_wake_word_selected),
            items = options.map { wakeKeywordLabel(it) },
            selectedIndex = selectedIndex
        ) { index ->
            prefs.wakeWordSelected = options.getOrElse(index) { "" }
            // 已运行的服务按新唤醒词重建
            if (prefs.wakeWordEnabled) {
                com.brycewg.asrkb.wake.WakeWordService.stop(context)
                com.brycewg.asrkb.wake.WakeWordService.start(context)
            }
            refreshState()
        }
    }

    fun showVolumeKeyModeSheet() {'''),
])

# 2) wake section UI (insert before compat item)
section = '''            item("wake_word") {
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
                        count = 3
                    )
                    if (uiState.wakeWordEnabled) {
                        FloatingValuePreference(
                            titleRes = R.string.label_wake_word_selected,
                            value = wakeKeywordLabel(prefs.wakeWordSelected),
                            uiMode = uiMode,
                            index = 1,
                            count = 3,
                            onClick = {
                                showWakeKeywordSheet()
                            }
                        )
                        FloatingExplainedSwitch(
                            id = "wake_word_only_charging",
                            titleRes = R.string.label_wake_word_only_charging,
                            checked = prefs.wakeWordOnlyWhileCharging,
                            onToggle = { target ->
                                prefs.wakeWordOnlyWhileCharging = target
                                refreshState()
                            },
                            index = 2,
                            count = 3
                        )
                    }
                }
            }

'''
edit('ui/settings/compose/screens/FloatingSettingsScreen.kt', [
    ('            item("compat") {', section + '            item("compat") {'),
])

# 3) strings × 5 locales
entries = {
    '../../../../res/values/strings_floating.xml': '''    <string name="section_wake_word">Voice Wake-up</string>
    <string name="label_wake_word_enabled">Enable voice wake-up</string>
    <string name="label_wake_word_only_charging">Listen only while charging</string>
    <string name="label_wake_word_selected">Wake word</string>
    <string name="wake_word_all">All (any preset word)</string>
    <string name="notif_channel_wake_word">Wake word</string>
    <string name="notif_channel_wake_word_desc">Keeps wake-word listening alive in the background</string>
    <string name="notif_wake_word_title">Wake word listening</string>
    <string name="notif_wake_word_desc">Say the wake word to start voice recognition</string>
''',
    '../../../../res/values-zh-rCN/strings_floating.xml': '''    <string name="section_wake_word">语音唤醒</string>
    <string name="label_wake_word_enabled">启用语音唤醒</string>
    <string name="label_wake_word_only_charging">仅充电时监听</string>
    <string name="label_wake_word_selected">唤醒词</string>
    <string name="wake_word_all">全部（任一预置词）</string>
    <string name="notif_channel_wake_word">语音唤醒</string>
    <string name="notif_channel_wake_word_desc">保持后台唤醒词监听</string>
    <string name="notif_wake_word_title">语音唤醒监听中</string>
    <string name="notif_wake_word_desc">说出唤醒词即可开始语音识别</string>
''',
    '../../../../res/values-zh-rTW/strings_floating.xml': '''    <string name="section_wake_word">語音喚醒</string>
    <string name="label_wake_word_enabled">啟用語音喚醒</string>
    <string name="label_wake_word_only_charging">僅充電時監聽</string>
    <string name="label_wake_word_selected">喚醒詞</string>
    <string name="wake_word_all">全部（任一預置詞）</string>
    <string name="notif_channel_wake_word">語音喚醒</string>
    <string name="notif_channel_wake_word_desc">保持背景喚醒詞監聽</string>
    <string name="notif_wake_word_title">語音喚醒監聽中</string>
    <string name="notif_wake_word_desc">說出喚醒詞即可開始語音辨識</string>
''',
    '../../../../res/values-ja/strings_floating.xml': '''    <string name="section_wake_word">音声ウェイクワード</string>
    <string name="label_wake_word_enabled">音声ウェイクワードを有効化</string>
    <string name="label_wake_word_only_charging">充電中のみリッスン</string>
    <string name="label_wake_word_selected">ウェイクワード</string>
    <string name="wake_word_all">すべて（任意のプリセット語）</string>
    <string name="notif_channel_wake_word">ウェイクワード</string>
    <string name="notif_channel_wake_word_desc">バックグラウンドのウェイクワード検出を維持します</string>
    <string name="notif_wake_word_title">ウェイクワード待機中</string>
    <string name="notif_wake_word_desc">ウェイクワードを話すと音声認識を開始します</string>
''',
    '../../../../res/values-ar/strings_floating.xml': '''    <string name="section_wake_word">الاستيقاظ الصوتي</string>
    <string name="label_wake_word_enabled">تفعيل الاستيقاظ الصوتي</string>
    <string name="label_wake_word_only_charging">الاستماع أثناء الشحن فقط</string>
    <string name="label_wake_word_selected">كلمة الاستيقاظ</string>
    <string name="wake_word_all">الكل（أي كلمة مضبوطة）</string>
    <string name="notif_channel_wake_word">كلمة الاستيقاظ</string>
    <string name="notif_channel_wake_word_desc">يحافظ على الاستماع لكلمة الاستيقاظ في الخلفية</string>
    <string name="notif_wake_word_title">بانتظار كلمة الاستيقاظ</string>
    <string name="notif_wake_word_desc">قل كلمة الاستيقاظ لبدء التعرف على الصوت</string>
''',
}
for p, entry in entries.items():
    s = io.open(p, encoding='utf-8').read()
    s = s.rstrip()[:-len('</resources>')] + entry + '</resources>\n'
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    R.append(('ok', p.split('/')[-2] + ' | strings added'))

io.open('phase_b3.log', 'w', encoding='utf-8').write(chr(10).join(x[0] + ' ' + x[1] for x in R))
print('PHASE B3 done')
