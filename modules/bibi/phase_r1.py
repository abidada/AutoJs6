# -*- coding: utf-8 -*-
"""R1: remove charge-only + add pinyin4j dependency."""
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


# 1) pinyin4j dependency
edit('G:/code/autojs/source/AutoJs6/modules/bibi/build.gradle.kts', [
    ('''    implementation("com.alibaba:dashscope-sdk-java:2.23.1")''',
     '''    implementation("com.alibaba:dashscope-sdk-java:2.23.1")

    // 汉字→带调拼音（自定义唤醒词生成），LGPL
    implementation("com.belerweb:pinyin4j:2.5.1")'''),
])

# 2) Prefs: remove charge-only
edit('store/Prefs.kt', [
    ('''
    // 仅充电时启用唤醒监听（省电，默认关闭）
    var wakeWordOnlyWhileCharging: Boolean
        get() = sp.getBoolean(KEY_WAKE_WORD_ONLY_CHARGING, false)
        set(value) = sp.edit { putBoolean(KEY_WAKE_WORD_ONLY_CHARGING, value) }
''', ''),
])

edit('store/PrefsKeys.kt', [
    ('internal const val KEY_WAKE_WORD_ONLY_CHARGING = "wake_word_only_charging"\n', ''),
])

# 3) Service: remove charge gate + isCharging
edit('wake/WakeWordService.kt', [
    ('''        while (running && prefs.wakeWordEnabled) {
            try {
                if (prefs.wakeWordOnlyWhileCharging && !isCharging()) {
                    releaseAudio()
                    record = null
                    Thread.sleep(2_000)
                    continue
                }

                if (AsrRecordingState.active) {''',
     '''        while (running && prefs.wakeWordEnabled) {
            try {
                if (AsrRecordingState.active) {'''),
    ('''    private fun isCharging(): Boolean {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager ?: return false
        val status = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS)
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

''', ''),
])

# 4) strings: remove label_wake_word_only_charging from all locales
import glob
for p in glob.glob('../../../../res/values*/strings_floating.xml'):
    s = io.open(p, encoding='utf-8').read()
    marker = '<string name="label_wake_word_only_charging">'
    idx = s.find(marker)
    if idx >= 0:
        line_start = s.rfind('\n', 0, idx)
        line_end = s.find('\n', idx)
        s = s[:line_start] + s[line_end:]
        io.open(p, 'w', encoding='utf-8', newline='').write(s)
        R.append(('ok', p.split('/')[-2] + ' | charge-only string removed'))

io.open('phase_r1.log', 'w', encoding='utf-8').write(chr(10).join(x[0] + ' ' + x[1] for x in R))
print('R1 done')
