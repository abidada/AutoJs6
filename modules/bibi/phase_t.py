# -*- coding: utf-8 -*-
"""Theme fix: replace raw Text with SettingsThemedText in wake manager + voice dispatch screens."""
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


# ================= WakeWordManagerScreen =================
p = 'ui/settings/compose/screens/WakeWordManagerScreen.kt'
edit(p, [
    ('''import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold''',
     '''import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsThemedText'''),
    # row name text
    ('''                        Text(
                            text = if (entry.name.isBlank()) {
                                stringResource(R.string.wake_word_all)
                            } else {
                                entry.name
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )''',
     '''                        SettingsThemedText(
                            text = if (entry.name.isBlank()) {
                                stringResource(R.string.wake_word_all)
                            } else {
                                entry.name
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )'''),
    # custom tag
    ('''                            Text(
                                text = stringResource(R.string.wake_word_custom_tag),
                                style = MaterialTheme.typography.bodySmall
                            )''',
     '''                            SettingsThemedText(
                                text = stringResource(R.string.wake_word_custom_tag),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )'''),
])

# ================= VoiceDispatchScreen =================
p = 'ui/settings/compose/screens/VoiceDispatchScreen.kt'
edit(p, [
    ('''import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold''',
     '''import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsThemedText'''),
    ('''                    Text(
                        text = stringResource(R.string.label_voice_dispatch_test),
                        style = MaterialTheme.typography.titleSmall
                    )''',
     '''                    SettingsThemedText(
                        text = stringResource(R.string.label_voice_dispatch_test),
                        style = MaterialTheme.typography.titleSmall
                    )'''),
    ('''                        Text(
                            text = testResult,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )''',
     '''                        SettingsThemedText(
                            text = testResult,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )'''),
    ('''                Text(
                    text = stringResource(R.string.label_voice_dispatch_rules, rules.size),
                    style = MaterialTheme.typography.titleSmall
                )''',
     '''                SettingsThemedText(
                    text = stringResource(R.string.label_voice_dispatch_rules, rules.size),
                    style = MaterialTheme.typography.titleSmall
                )'''),
    ('''                    Text(
                        text = stringResource(R.string.summary_voice_dispatch_empty),
                        style = MaterialTheme.typography.bodyMedium
                    )''',
     '''                    SettingsThemedText(
                        text = stringResource(R.string.summary_voice_dispatch_empty),
                        style = MaterialTheme.typography.bodyMedium
                    )'''),
])

s = io.open(p, encoding='utf-8').read()
# rule card texts
s = s.replace('''                Text(
                    text = "${rule.name} · ${rule.priority}",
                    style = MaterialTheme.typography.titleSmall
                )''', '''                SettingsThemedText(
                    text = "${rule.name} · ${rule.priority}",
                    style = MaterialTheme.typography.titleSmall
                )''')
s = s.replace('''                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall
                )''', '''                SettingsThemedText(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall
                )''')
s = s.replace('''                Text(
                    text = timeText,
                    style = MaterialTheme.typography.bodySmall
                )''', '''                SettingsThemedText(
                    text = timeText,
                    style = MaterialTheme.typography.bodySmall
                )''')
io.open(p, 'w', encoding='utf-8', newline='').write(s)
R.append(('ok', 'rule card texts themed'))

# ================= VoiceDispatchRuleEditScreen =================
p = 'ui/settings/compose/screens/VoiceDispatchRuleEditScreen.kt'
edit(p, [
    ('''import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold''',
     '''import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsThemedText'''),
    ('''            Text(
                text = stringResource(R.string.label_voice_dispatch_priority, priority.toInt()),
                style = MaterialTheme.typography.titleSmall
            )''',
     '''            SettingsThemedText(
                text = stringResource(R.string.label_voice_dispatch_priority, priority.toInt()),
                style = MaterialTheme.typography.titleSmall
            )'''),
    ('''            Text(
                text = stringResource(R.string.label_voice_dispatch_match_type),
                style = MaterialTheme.typography.titleSmall
            )''',
     '''            SettingsThemedText(
                text = stringResource(R.string.label_voice_dispatch_match_type),
                style = MaterialTheme.typography.titleSmall
            )'''),
    ('''            Text(
                text = stringResource(R.string.label_voice_dispatch_type),
                style = MaterialTheme.typography.titleSmall
            )''',
     '''            SettingsThemedText(
                text = stringResource(R.string.label_voice_dispatch_type),
                style = MaterialTheme.typography.titleSmall
            )'''),
])

io.open('phase_t.log', 'w', encoding='utf-8').write(chr(10).join(x[0] + ' ' + x[1] for x in R))
print('THEME FIX done')
