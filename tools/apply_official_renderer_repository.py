from pathlib import Path

manager = Path('android/app/src/main/java/io/github/retrofrost/cts/android/RendererManagerActivity.kt')
text = manager.read_text(encoding='utf-8')
text = text.replace(
    'Text("2.0.7 • renderer API ${RendererCapabilities.RENDERER_API}", style = MaterialTheme.typography.labelLarge)',
    'Text("${BuildConfig.VERSION_NAME} • renderer API ${RendererCapabilities.RENDERER_API}", style = MaterialTheme.typography.labelLarge)',
)
needle = '''                    item {
                        Button(
                            onClick = { importRenderer.launch(arrayOf("application/octet-stream", "*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Inspect .renderer") }
                    }
'''
addition = needle + '''                    item {
                        OutlinedButton(
                            onClick = {
                                startActivity(Intent(this@RendererManagerActivity, RendererRepositoryActivity::class.java))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Browse official renderer repository") }
                    }
'''
if 'Browse official renderer repository' not in text:
    if needle not in text:
        raise SystemExit('Renderer manager import button block not found')
    text = text.replace(needle, addition, 1)
text = text.replace('message = "Built-in 2.0.7 native renderer restored."', 'message = "Built-in renderer restored."')
manager.write_text(text, encoding='utf-8')

manifest = Path('android/app/src/main/AndroidManifest.xml')
text = manifest.read_text(encoding='utf-8')
if 'android.permission.INTERNET' not in text:
    text = text.replace(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n',
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n    <uses-permission android:name="android.permission.INTERNET" />\n',
        1,
    )
activity = '''        <activity
            android:name=".RendererRepositoryActivity"
            android:exported="false"
            android:label="Official renderer repository" />

'''
if '.RendererRepositoryActivity' not in text:
    marker = '''        <activity
            android:name=".RendererManagerActivity"
            android:exported="false"
            android:label="Renderer library" />

'''
    if marker not in text:
        raise SystemExit('RendererManagerActivity manifest block not found')
    text = text.replace(marker, marker + activity, 1)
manifest.write_text(text, encoding='utf-8')

bundle = Path('android/app/src/main/java/io/github/retrofrost/cts/android/RendererBundle.kt')
text = bundle.read_text(encoding='utf-8')
text = text.replace('val name: String = "Cubical Compare 2.0.7 Native",', 'val name: String = "Cubical Compare 2.0.8 Native",')
text = text.replace('const val APP_VERSION = "2.0.7"', 'const val APP_VERSION = "2.0.8"')
bundle.write_text(text, encoding='utf-8')
