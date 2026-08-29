from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"marker not found in {path}: {old[:140]!r}")
    p.write_text(text.replace(old, new, 1))

bridge = "android/app/src/main/java/io/github/retrofrost/cts/android/RendererBridge.kt"
replace_once(
    bridge,
    '''    fun metadata(project: StudioProject): RenderMetadata = synchronized(lock) {
        val spec = RendererRuntime.active
        requireProjectCompatibility(project, spec)
        val fps = if (''',
    '''    fun metadata(project: StudioProject): RenderMetadata = synchronized(lock) {
        val spec = RendererRuntime.active
        val fps = if (''',
)
replace_once(
    bridge,
    '''    private fun renderEngine(project: StudioProject, spec: RendererSpec, frame: Int, width: Int, height: Int): Bitmap {
        requireProjectCompatibility(project, spec)
        return when (engine(spec)) {''',
    '''    private fun renderEngine(project: StudioProject, spec: RendererSpec, frame: Int, width: Int, height: Int): Bitmap {
        return when (engine(spec)) {''',
)
replace_once(
    bridge,
    '''    private fun renderEngineRgba(project: StudioProject, spec: RendererSpec, frame: Int, width: Int, height: Int): ByteArray {
        requireProjectCompatibility(project, spec)
        return when (engine(spec)) {''',
    '''    private fun renderEngineRgba(project: StudioProject, spec: RendererSpec, frame: Int, width: Int, height: Int): ByteArray {
        return when (engine(spec)) {''',
)

bundle = "android/app/src/main/java/io/github/retrofrost/cts/android/RendererBundle.kt"
replace_once(
    bundle,
    '''        val report = RendererCapabilities.report(spec)
        require(report.compatible) { report.errors.joinToString("\\n") }
        ProjectAutosave.load(context)?.let { project ->
            RendererProjectGuard.requireCompatible(project, spec)
        }
        dir.mkdirs()''',
    '''        val report = RendererCapabilities.report(spec)
        require(report.compatible) { report.errors.joinToString("\\n") }
        // Renderer compatibility is a project-quality diagnostic, never an import
        // gate. A renderer is a reusable visual/timing profile and must be installable
        // on a new, empty or differently-sized project. Exact-v2 still forces its
        // reference output size/FPS in the render/export path.
        dir.mkdirs()''',
)

app = "android/app/src/main/java/io/github/retrofrost/cts/android/CubicalCompareApplication.kt"
Path(app).write_text('''package io.github.retrofrost.cts.android

import android.app.Application

class CubicalCompareApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Keep the user's selected renderer active across projects. Project/card
        // differences are reported by the UI as Modified; they do not deactivate
        // or replace a renderer behind the user's back.
        RendererRuntime.active = RendererStore(this).active()
    }
}
''')

# Keep projectCompatibility as diagnostics for the Preview screen, but make sure
# no runtime/import path still treats it as a hard requirement.
text = Path(bridge).read_text()
if text.count('requireProjectCompatibility(project, spec)') != 0:
    raise SystemExit('hard render compatibility gate still present')
text = Path(bundle).read_text()
if 'RendererProjectGuard.requireCompatible(project, spec)' in text:
    raise SystemExit('hard activation compatibility gate still present')
