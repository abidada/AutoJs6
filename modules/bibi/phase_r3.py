# -*- coding: utf-8 -*-
"""R3: FloatingSettingsRoute wrapper + strings."""
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


# 1) FloatingSettingsRoute wrapper in FloatingSettingsScreen.kt
edit('ui/settings/compose/screens/FloatingSettingsScreen.kt', [
    ('''private class FloatingPackagePersistState {''',
     '''/**
 * 悬浮球设置路由包装：内部承载「唤醒词管理」二级页。
 */
@Composable
internal fun FloatingSettingsRoute(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    actions: SettingsActionController
) {
    var showWakeManager by remember { mutableStateOf(false) }
    if (showWakeManager) {
        WakeWordManagerScreen(uiMode = uiMode, onBack = { showWakeManager = false })
    } else {
        FloatingSettingsScreen(
            uiMode = uiMode,
            onBack = onBack,
            onOpenWakeManager = { showWakeManager = true },
            actions = actions
        )
    }
}

private class FloatingPackagePersistState {'''),
])

# 2) strings × 5 locales
entries = {
    '../../../../res/values/strings_floating.xml': '''    <string name="title_wake_word_manager">Wake word</string>
    <string name="btn_wake_word_add">Add</string>
    <string name="title_wake_word_edit">Edit wake word</string>
    <string name="wake_word_input_hint">Enter 2–5 Chinese characters</string>
    <string name="wake_word_invalid">Contains unsupported pronunciation</string>
    <string name="wake_word_exists">Wake word already exists</string>
    <string name="wake_word_custom_tag">Custom</string>
''',
    '../../../../res/values-zh-rCN/strings_floating.xml': '''    <string name="title_wake_word_manager">唤醒词</string>
    <string name="btn_wake_word_add">添加</string>
    <string name="title_wake_word_edit">编辑唤醒词</string>
    <string name="wake_word_input_hint">请输入 2~5 个汉字</string>
    <string name="wake_word_invalid">包含模型不支持的读音，请换一个词</string>
    <string name="wake_word_exists">唤醒词已存在</string>
    <string name="wake_word_custom_tag">自定义</string>
''',
    '../../../../res/values-zh-rTW/strings_floating.xml': '''    <string name="title_wake_word_manager">喚醒詞</string>
    <string name="btn_wake_word_add">新增</string>
    <string name="title_wake_word_edit">編輯喚醒詞</string>
    <string name="wake_word_input_hint">請輸入 2~5 個漢字</string>
    <string name="wake_word_invalid">包含模型不支援的讀音，請換一個詞</string>
    <string name="wake_word_exists">喚醒詞已存在</string>
    <string name="wake_word_custom_tag">自訂</string>
''',
    '../../../../res/values-ja/strings_floating.xml': '''    <string name="title_wake_word_manager">ウェイクワード</string>
    <string name="btn_wake_word_add">追加</string>
    <string name="title_wake_word_edit">ウェイクワードを編集</string>
    <string name="wake_word_input_hint">2〜5文字の漢字を入力</string>
    <string name="wake_word_invalid">サポートされていない読み方が含まれています</string>
    <string name="wake_word_exists">ウェイクワードは既に存在します</string>
    <string name="wake_word_custom_tag">カスタム</string>
''',
    '../../../../res/values-ar/strings_floating.xml': '''    <string name="title_wake_word_manager">كلمة الاستيقاظ</string>
    <string name="btn_wake_word_add">إضافة</string>
    <string name="title_wake_word_edit">تعديل كلمة الاستيقاظ</string>
    <string name="wake_word_input_hint">أدخل ٢ إلى ٥ أحرف صينية</string>
    <string name="wake_word_invalid">يحتوي على نطق غير مدعوم</string>
    <string name="wake_word_exists">كلمة الاستيقاظ موجودة بالفعل</string>
    <string name="wake_word_custom_tag">مخصص</string>
''',
}
for p, entry in entries.items():
    s = io.open(p, encoding='utf-8').read()
    s = s.rstrip()[:-len('</resources>')] + entry + '</resources>\n'
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    R.append(('ok', p.split('/')[-2] + ' | strings added'))

io.open('phase_r3.log', 'w', encoding='utf-8').write(chr(10).join(x[0] + ' ' + x[1] for x in R))
print('R3 done')
