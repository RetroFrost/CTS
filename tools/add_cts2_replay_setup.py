from pathlib import Path

app = Path('android/app/src/main/java/io/github/retrofrost/cts/android/ui/CtsAppV2.kt')
main = Path('android/app/src/main/java/io/github/retrofrost/cts/android/MainActivity.kt')

text = app.read_text()

if 'import androidx.compose.material.icons.filled.Settings\n' not in text:
    marker = 'import androidx.compose.material.icons.filled.Save\n'
    if marker not in text:
        raise SystemExit('Settings import marker missing')
    text = text.replace(marker, marker + 'import androidx.compose.material.icons.filled.Settings\n', 1)

old_sig = 'fun CtsAndroidAppV2(initialModel: VisualModel = VisualModel.Males) {'
new_sig = '''fun CtsAndroidAppV2(
    initialModel: VisualModel = VisualModel.Males,
    onReplaySetup: () -> Unit = {},
) {'''
if old_sig in text:
    text = text.replace(old_sig, new_sig, 1)
elif 'onReplaySetup: () -> Unit = {}' not in text:
    raise SystemExit('CtsAndroidAppV2 signature not found')

old_actions = '''                    IconButton(onClick = { saveProject.launch("comparison-project.cts.json") }) {
                        Icon(Icons.Filled.Save, contentDescription = "Save project")
                    }
'''
new_actions = old_actions + '''                    IconButton(onClick = onReplaySetup) {
                        Icon(Icons.Filled.Settings, contentDescription = "Run setup again")
                    }
'''
if 'contentDescription = "Run setup again"' not in text:
    if old_actions not in text:
        raise SystemExit('Top app bar action marker missing')
    text = text.replace(old_actions, new_actions, 1)

app.write_text(text)

m = main.read_text()
old_call = '''                } else {
                    CtsAndroidAppV2(initialModel = preferredModel)
                }
'''
new_call = '''                } else {
                    CtsAndroidAppV2(
                        initialModel = preferredModel,
                        onReplaySetup = {
                            preferences.edit()
                                .putBoolean("setup-complete-v2", false)
                                .apply()
                            setupComplete = false
                        },
                    )
                }
'''
if old_call in m:
    m = m.replace(old_call, new_call, 1)
elif 'onReplaySetup = {' not in m:
    raise SystemExit('MainActivity CTS app call not found')
main.write_text(m)

final_app = app.read_text()
final_main = main.read_text()
assert 'onReplaySetup: () -> Unit = {}' in final_app
assert 'contentDescription = "Run setup again"' in final_app
assert 'onReplaySetup = {' in final_main
assert '.putBoolean("setup-complete-v2", false)' in final_main
print('CTS 2.0 setup can now be replayed from the app bar.')
