#!/usr/bin/env python3
"""Make large Renderer API v3 packages file-backed instead of heap-backed."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
V3 = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RendererV3.kt"
BUNDLE = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RendererBundle.kt"
FRAME = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RendererV3FrameRenderer.kt"
IMPORT = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RendererImportActivity.kt"
TEST = ROOT / "android/app/src/test/java/io/github/retrofrost/cts/android/RendererV3StreamingPackageTest.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# RendererV3.kt: ZIP sidecars stay on disk and are read only on demand.
# ---------------------------------------------------------------------------
s = V3.read_text()
s = replace_once(
    s,
    "import java.io.DataOutputStream\nimport java.io.OutputStream\n",
    "import java.io.DataOutputStream\nimport java.io.File\nimport java.io.InputStream\nimport java.io.OutputStream\n",
    "v3 file imports",
)
s = replace_once(
    s,
    "import java.util.zip.GZIPOutputStream\nimport java.util.zip.ZipInputStream\n",
    "import java.util.zip.GZIPOutputStream\nimport java.util.zip.ZipFile\nimport java.util.zip.ZipInputStream\n",
    "v3 zipfile import",
)
old_scene = '''    val raw: JSONObject,
    val assets: Map<String, ByteArray> = emptyMap(),
    val originalBytes: ByteArray = ByteArray(0),
) {
    fun resource(id: String?): JSONObject? = id?.let { resources.optJSONObject(it) }

    fun asset(path: String?): ByteArray? {
        val normalized = path?.replace('\\\\', '/')?.removePrefix("./") ?: return null
        return assets[normalized]
            ?: assets.entries.firstOrNull { it.key.endsWith("/$normalized") }?.value
    }

    fun objectById(id: String): RendererV3Object? = objects.firstOrNull { it.id == id }
}'''
new_scene = '''    val raw: JSONObject,
    val assets: Map<String, ByteArray> = emptyMap(),
    val assetEntries: Set<String> = emptySet(),
    val packageFile: File? = null,
    val originalBytes: ByteArray = ByteArray(0),
    val originalFile: File? = null,
) {
    fun resource(id: String?): JSONObject? = id?.let { resources.optJSONObject(it) }

    private fun normalizedAssetName(path: String?): String? {
        val normalized = path?.replace('\\\\', '/')?.removePrefix("./") ?: return null
        if (normalized.isBlank()) return null
        return normalized
    }

    fun hasAsset(path: String?): Boolean {
        val normalized = normalizedAssetName(path) ?: return false
        if (assets.containsKey(normalized) || assets.keys.any { it.endsWith("/$normalized") }) return true
        return assetEntries.contains(normalized) || assetEntries.any { it.endsWith("/$normalized") }
    }

    fun asset(path: String?): ByteArray? {
        val normalized = normalizedAssetName(path) ?: return null
        assets[normalized]?.let { return it }
        assets.entries.firstOrNull { it.key.endsWith("/$normalized") }?.value?.let { return it }
        val entryName = when {
            assetEntries.contains(normalized) -> normalized
            else -> assetEntries.firstOrNull { it.endsWith("/$normalized") }
        } ?: return null
        val source = packageFile?.takeIf(File::isFile) ?: return null
        return ZipFile(source).use { zip ->
            val entry = zip.getEntry(entryName) ?: return@use null
            require(entry.size <= RendererV3Bundle.MAX_FILE_BYTES.toLong()) { "Renderer v3 asset is too large." }
            zip.getInputStream(entry).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= RendererV3Bundle.MAX_FILE_BYTES) { "Renderer v3 asset is too large." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }

    fun objectById(id: String): RendererV3Object? = objects.firstOrNull { it.id == id }
}'''
s = replace_once(s, old_scene, new_scene, "file-backed scene assets")

# File probes/readers are added next to the existing byte-array entry points.
needle = '''    fun accepts(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes.size >= 8 && String(bytes, 0, 8, Charsets.US_ASCII) == MAGIC) return true
        return bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
    }

    fun read(bytes: ByteArray): RendererV3ReadResult {'''
replacement = '''    fun accepts(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes.size >= 8 && String(bytes, 0, 8, Charsets.US_ASCII) == MAGIC) return true
        return bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
    }

    fun accepts(file: File): Boolean {
        if (!file.isFile || file.length() < 4L || file.length() > MAX_FILE_BYTES.toLong()) return false
        val header = ByteArray(8)
        val count = file.inputStream().buffered().use { it.read(header) }
        if (count >= 8 && String(header, 0, 8, Charsets.US_ASCII) == MAGIC) return true
        return count >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
    }

    fun read(file: File): RendererV3ReadResult {
        require(file.isFile) { "Renderer v3 package does not exist." }
        require(file.length() <= MAX_FILE_BYTES.toLong()) { "Renderer v3 package is too large." }
        val header = ByteArray(8)
        val count = file.inputStream().buffered().use { it.read(header) }
        val result = if (count >= 8 && String(header, 0, 8, Charsets.US_ASCII) == MAGIC) {
            file.inputStream().buffered().use { input ->
                parseContainerInput(
                    input = input,
                    assets = emptyMap(),
                    assetEntries = emptySet(),
                    packageFile = null,
                    originalBytes = ByteArray(0),
                    originalFile = file,
                )
            }
        } else {
            parsePackage(file)
        }
        RendererV3Runtime.register(result.scene)
        return result
    }

    fun read(bytes: ByteArray): RendererV3ReadResult {'''
s = replace_once(s, needle, replacement, "file v3 reader")

s = replace_once(
    s,
    '''    fun write(scene: RendererV3Scene, output: OutputStream) {
        if (scene.originalBytes.isNotEmpty()) {''',
    '''    fun write(scene: RendererV3Scene, output: OutputStream) {
        scene.originalFile?.takeIf(File::isFile)?.let { source ->
            source.inputStream().buffered().use { it.copyTo(output, 64 * 1024) }
            return
        }
        if (scene.originalBytes.isNotEmpty()) {''',
    "file-backed v3 write",
)

# Insert file ZIP parser before safeEntryName.
anchor = '''    private fun safeEntryName(value: String): String {'''
file_parser = '''    private fun parsePackage(packageFile: File): RendererV3ReadResult {
        ZipFile(packageFile).use { zip ->
            val names = mutableListOf<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                require(names.size < MAX_PACKAGE_ENTRIES) { "Renderer v3 package contains too many files." }
                names += safeEntryName(entry.name)
            }
            val sceneName = names.firstOrNull { it.equals("renderer.renderer3", true) }
                ?: names.firstOrNull { it.equals("manifest.renderer3", true) }
                ?: names.firstOrNull { it.endsWith(".renderer3", true) }
                ?: error("Renderer v3 ZIP has no .renderer3 scene file.")
            val sceneEntry = zip.getEntry(sceneName) ?: error("Renderer v3 ZIP scene entry is missing.")
            require(sceneEntry.size <= MAX_FILE_BYTES.toLong()) { "Renderer v3 scene is too large." }
            val sceneBytes = zip.getInputStream(sceneEntry).use { readLimited(it, MAX_FILE_BYTES) }
            return parseContainer(
                container = sceneBytes,
                assets = emptyMap(),
                originalBytes = ByteArray(0),
                assetEntries = names.filterTo(linkedSetOf()) { it != sceneName },
                packageFile = packageFile,
                originalFile = packageFile,
            )
        }
    }

'''
if file_parser not in s:
    if anchor not in s:
        raise SystemExit("v3 safeEntryName anchor missing")
    s = s.replace(anchor, file_parser + anchor, 1)

# Replace byte-only container parser with a delegating byte parser + streaming parser.
old_parser_start = '''    private fun parseContainer(
        container: ByteArray,
        assets: Map<String, ByteArray>,
        originalBytes: ByteArray,
    ): RendererV3ReadResult {
        val data = DataInputStream(ByteArrayInputStream(container))'''
new_parser_start = '''    private fun parseContainer(
        container: ByteArray,
        assets: Map<String, ByteArray>,
        originalBytes: ByteArray,
        assetEntries: Set<String> = assets.keys,
        packageFile: File? = null,
        originalFile: File? = null,
    ): RendererV3ReadResult = ByteArrayInputStream(container).use { input ->
        parseContainerInput(input, assets, assetEntries, packageFile, originalBytes, originalFile)
    }

    private fun parseContainerInput(
        input: InputStream,
        assets: Map<String, ByteArray>,
        assetEntries: Set<String>,
        packageFile: File?,
        originalBytes: ByteArray,
        originalFile: File?,
    ): RendererV3ReadResult {
        val data = DataInputStream(input)'''
s = replace_once(s, old_parser_start, new_parser_start, "streaming v3 container parser")

s = replace_once(
    s,
    '''        val scene = parseScene(root, assets, originalBytes)
        return RendererV3ReadResult(specFor(scene), scene)
    }

    private fun parseScene(root: JSONObject, assets: Map<String, ByteArray>, originalBytes: ByteArray): RendererV3Scene {''',
    '''        val scene = parseScene(root, assets, assetEntries, packageFile, originalBytes, originalFile)
        return RendererV3ReadResult(specFor(scene), scene)
    }

    private fun parseScene(
        root: JSONObject,
        assets: Map<String, ByteArray>,
        assetEntries: Set<String>,
        packageFile: File?,
        originalBytes: ByteArray,
        originalFile: File?,
    ): RendererV3Scene {''',
    "v3 parseScene storage args",
)

s = replace_once(
    s,
    '''            selectors = selectors,
            assets = assets,
            frames = frames,
        )''',
    '''            selectors = selectors,
            assets = assets,
            assetEntries = assetEntries,
            frames = frames,
        )''',
    "v3 validation asset entries call",
)

s = replace_once(
    s,
    '''            raw = root,
            assets = assets,
            originalBytes = originalBytes,
        )''',
    '''            raw = root,
            assets = assets,
            assetEntries = assetEntries,
            packageFile = packageFile,
            originalBytes = originalBytes,
            originalFile = originalFile,
        )''',
    "v3 scene backing fields",
)

s = replace_once(
    s,
    '''        selectors: List<RendererV3Selector>,
        assets: Map<String, ByteArray>,
        frames: Int,''',
    '''        selectors: List<RendererV3Selector>,
        assets: Map<String, ByteArray>,
        assetEntries: Set<String>,
        frames: Int,''',
    "v3 validation signature",
)

s = replace_once(
    s,
    '''            return assets.containsKey(normalized) || assets.keys.any { it.endsWith("/$normalized") }
        }''',
    '''            return assets.containsKey(normalized) || assets.keys.any { it.endsWith("/$normalized") } ||
                assetEntries.contains(normalized) || assetEntries.any { it.endsWith("/$normalized") }
        }''',
    "v3 sidecar existence",
)
V3.write_text(s)


# ---------------------------------------------------------------------------
# RendererBundle.kt / RendererStore: candidate, install, active, rollback and
# hashes operate on files rather than whole-package ByteArrays.
# ---------------------------------------------------------------------------
s = BUNDLE.read_text()
s = replace_once(
    s,
    '''data class RendererCandidate(
    val bytes: ByteArray,
    val spec: RendererSpec,
    val sha256: String,
    val report: RendererValidationReport,
)''',
    '''data class RendererCandidate(
    val bytes: ByteArray,
    val spec: RendererSpec,
    val sha256: String,
    val report: RendererValidationReport,
    val sourceFile: File? = null,
)''',
    "candidate source file",
)
s = replace_once(
    s,
    '''    private val libraryDir = File(dir, "library")
    private val activeFile = File(dir, "active.renderer")''',
    '''    private val libraryDir = File(dir, "library")
    private val stagingDir = File(dir, "staging")
    private val activeFile = File(dir, "active.renderer")''',
    "renderer staging dir",
)
s = replace_once(
    s,
    '''    fun active(): RendererSpec = if (activeFile.isFile) {
        runCatching { activeFile.inputStream().use(RendererBundle::read) }
            .getOrElse { RendererSpec.builtIn() }
    } else {
        RendererSpec.builtIn()
    }

    fun inspect(input: InputStream): RendererCandidate {
        val bytes = readLimited(input, RendererBundle.MAX_FILE_BYTES)
        val spec = RendererBundle.read(ByteArrayInputStream(bytes))
        val structural = RendererBundle.validateDetailed(spec)
        val compatibility = RendererCapabilities.report(spec)
        val report = RendererValidationReport(
            structural.errors + compatibility.errors,
            structural.warnings + compatibility.warnings,
        )
        return RendererCandidate(bytes, spec, sha256(bytes), report)
    }

    fun install(candidate: RendererCandidate): InstalledRenderer {
        require(candidate.report.compatible) { candidate.report.errors.joinToString("\\n") }
        libraryDir.mkdirs()
        val destination = File(libraryDir, "${candidate.spec.id}.renderer")
        atomicWrite(destination, candidate.bytes)
        val activeBytes = activeFile.takeIf(File::isFile)?.readBytes()
        return InstalledRenderer(
            candidate.spec,
            destination,
            activeBytes?.contentEquals(candidate.bytes) == true,
        )
    }''',
    '''    fun active(): RendererSpec = if (activeFile.isFile) {
        runCatching { RendererBundle.read(activeFile) }
            .getOrElse { RendererSpec.builtIn() }
    } else {
        RendererSpec.builtIn()
    }

    fun inspect(input: InputStream): RendererCandidate {
        stagingDir.mkdirs()
        val staged = File.createTempFile("renderer-import-", ".package", stagingDir)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileOutputStream(staged).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= RendererBundle.MAX_FILE_BYTES.toLong()) { "Renderer file is too large." }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
            val spec = RendererBundle.read(staged)
            val structural = RendererBundle.validateDetailed(spec)
            val compatibility = RendererCapabilities.report(spec)
            val report = RendererValidationReport(
                structural.errors + compatibility.errors,
                structural.warnings + compatibility.warnings,
            )
            return RendererCandidate(
                bytes = ByteArray(0),
                spec = spec,
                sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                report = report,
                sourceFile = staged,
            )
        } catch (failure: Throwable) {
            staged.delete()
            throw failure
        }
    }

    fun discard(candidate: RendererCandidate?) {
        val source = candidate?.sourceFile ?: return
        if (source.parentFile == stagingDir) source.delete()
    }

    fun install(candidate: RendererCandidate): InstalledRenderer {
        require(candidate.report.compatible) { candidate.report.errors.joinToString("\\n") }
        libraryDir.mkdirs()
        val destination = File(libraryDir, "${candidate.spec.id}.renderer")
        val source = candidate.sourceFile
        if (source?.isFile == true) {
            atomicCopy(source, destination)
            if (source.parentFile == stagingDir) source.delete()
        } else {
            atomicWrite(destination, candidate.bytes)
        }
        return InstalledRenderer(
            candidate.spec,
            destination,
            activeSha256() == candidate.sha256,
        )
    }''',
    "streaming inspect and install",
)

# Replace active/rollback/hash/list/uninstall/reset/export block with file operations.
start = s.index("    fun activate(id: String): RendererSpec {")
end = s.index("    private fun atomicWrite(destination: File, bytes: ByteArray) {")
new_methods = '''    fun activate(id: String): RendererSpec {
        val file = File(libraryDir, "$id.renderer")
        require(file.isFile) { "Renderer '$id' is not installed." }
        val spec = RendererBundle.read(file)
        val report = RendererCapabilities.report(spec)
        require(report.compatible) { report.errors.joinToString("\\n") }
        dir.mkdirs()
        val installedHash = sha256(file)
        val currentHash = activeSha256()
        if (installedHash != currentHash) {
            if (activeFile.isFile) atomicCopy(activeFile, previousFile)
            atomicCopy(file, activeFile)
        }
        // Re-read from the installed library file above, so RendererV3Runtime keeps a
        // stable file-backed package even if active.renderer is later replaced.
        RendererBridge.setRuntimeActive(spec)
        return spec
    }

    fun rollback(): RendererSpec {
        require(previousFile.isFile) { "There is no previous renderer to restore." }
        val spec = RendererBundle.read(previousFile)
        val swap = File(dir, "rollback-current.tmp")
        if (activeFile.isFile) atomicCopy(activeFile, swap)
        atomicCopy(previousFile, activeFile)
        if (swap.isFile) {
            atomicCopy(swap, previousFile)
            swap.delete()
        } else {
            previousFile.delete()
        }
        // Load the now-active copy so any v3 package backing path remains valid.
        val activeSpec = RendererBundle.read(activeFile)
        RendererBridge.setRuntimeActive(activeSpec)
        return activeSpec
    }

    fun activeSha256(): String? = activeFile.takeIf(File::isFile)?.let(::sha256)

    fun installedSha256(id: String): String? = File(libraryDir, "$id.renderer")
        .takeIf(File::isFile)
        ?.let(::sha256)

    fun listInstalled(): List<InstalledRenderer> {
        val activeHash = activeSha256()
        if (!libraryDir.isDirectory) return emptyList()
        return libraryDir.listFiles { file -> file.isFile && file.extension.equals("renderer", true) }
            .orEmpty()
            .mapNotNull { file ->
                runCatching {
                    val spec = RendererBundle.read(file)
                    InstalledRenderer(spec, file, activeHash != null && activeHash == sha256(file))
                }.getOrNull()
            }
            .sortedBy { it.spec.name.lowercase() }
    }

    fun uninstall(id: String) {
        val target = File(libraryDir, "$id.renderer")
        require(target.isFile) { "Renderer '$id' is not installed." }
        require(activeSha256() == null || activeSha256() != sha256(target)) {
            "Activate another renderer before deleting the active renderer."
        }
        RendererV3Runtime.forget(id)
        require(target.delete()) { "Renderer '$id' could not be deleted." }
    }

    /** Backwards-compatible one-call import used by legacy entry points. */
    fun import(input: InputStream): RendererSpec {
        val candidate = inspect(input)
        install(candidate)
        return activate(candidate.spec.id)
    }

    fun reset(): RendererSpec {
        if (activeFile.isFile) atomicCopy(activeFile, previousFile)
        activeFile.delete()
        return RendererSpec.builtIn().also(RendererBridge::setRuntimeActive)
    }

    fun export(output: OutputStream) {
        if (activeFile.isFile) {
            activeFile.inputStream().buffered().use { it.copyTo(output, 64 * 1024) }
        } else {
            RendererBundle.write(RendererSpec.builtIn(), output)
        }
    }

    private fun atomicCopy(source: File, destination: File) {
        require(source.isFile) { "Renderer source file is missing." }
        destination.parentFile?.mkdirs()
        val tmp = File(destination.parentFile, destination.name + ".tmp")
        FileOutputStream(tmp).use { output ->
            source.inputStream().buffered().use { input -> input.copyTo(output, 64 * 1024) }
            output.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

'''
s = s[:start] + new_methods + s[end:]

# Remove now-unused readLimited helper and extend hashes to files while retaining byte hash for compatibility.
old_read_limited = '''    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Renderer file is too large." }
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}'''
new_hash = '''    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}'''
s = replace_once(s, old_read_limited, new_hash, "streaming file hashes")

# File-based dispatch avoids RendererBundle.read(InputStream).readBytes() for installed v3 packages.
needle = '''    fun read(input: InputStream): RendererSpec {
        val bytes = input.readBytes()'''
replacement = '''    fun read(file: File): RendererSpec {
        require(file.isFile) { "Renderer file does not exist." }
        require(file.length() <= MAX_FILE_BYTES.toLong()) { "Renderer file is too large." }
        if (RendererV3Bundle.accepts(file)) return RendererV3Bundle.read(file).spec
        return file.inputStream().buffered().use(::readLegacy)
    }

    fun read(input: InputStream): RendererSpec {
        val bytes = input.readBytes()'''
s = replace_once(s, needle, replacement, "file renderer dispatch")
BUNDLE.write_text(s)


# ---------------------------------------------------------------------------
# RendererImportActivity.kt: clean staging packages when choosing/cancelling.
# ---------------------------------------------------------------------------
s = IMPORT.read_text()
s = replace_once(
    s,
    '''    override fun onDestroy() {
        preview?.takeIf { !it.isRecycled }?.recycle()
        preview = null
        super.onDestroy()
    }''',
    '''    override fun onDestroy() {
        preview?.takeIf { !it.isRecycled }?.recycle()
        preview = null
        store.discard(candidate)
        candidate = null
        super.onDestroy()
    }''',
    "discard staged renderer on destroy",
)
s = replace_once(
    s,
    '''    private fun inspect(uri: Uri) {
        preview?.recycle(); preview = null; candidate = null; error = null''',
    '''    private fun inspect(uri: Uri) {
        preview?.recycle(); preview = null
        store.discard(candidate)
        candidate = null; error = null''',
    "discard previous staged renderer",
)
IMPORT.write_text(s)


# ---------------------------------------------------------------------------
# RendererV3FrameRenderer.kt: bounded static bitmap cache, dynamic exact-frame
# sequences are decoded for one frame and immediately released.
# ---------------------------------------------------------------------------
s = FRAME.read_text()
s = replace_once(
    s,
    "import android.os.Build\n",
    "import android.os.Build\nimport android.util.LruCache\n",
    "lru cache import",
)
s = s.replace("import java.util.concurrent.ConcurrentHashMap\n", "")
s = replace_once(
    s,
    '''class RendererV3FrameRenderer {
    private val bitmapCache = ConcurrentHashMap<String, Bitmap>()''',
    '''class RendererV3FrameRenderer {
    // Keep only a bounded working set of static card/text resources. Exact frame
    // sequences are deliberately not cached; otherwise a 400-frame 1443x1080 outro
    // can retain multiple gigabytes of decoded ARGB bitmaps.
    private val bitmapCache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }''',
    "bounded bitmap cache",
)

old_outro = '''        val bitmap = decodeBitmap(scene, source, JSONObject())
            ?: error("Exact outro overlay frame asset '$source' is missing or undecodable.")
        if (truthy(props["replaceCanvas"] ?: resource.opt("replaceCanvas"), false)) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }
        val sampling = stringValue(props["sampling"] ?: resource.opt("sampling"))?.lowercase() ?: "nearest"
        val paint = Paint().apply {
            isAntiAlias = false
            isFilterBitmap = sampling == "linear"
            alpha = (255f * opacity).toInt().coerceIn(0, 255)
        }
        val x = number(props["x"], resource.optDouble("x", 0.0)).toFloat()
        val y = number(props["y"], resource.optDouble("y", 0.0)).toFloat()
        val width = number(props["width"], resource.optDouble("width", scene.canvas.width.toDouble())).toFloat()
        val height = number(props["height"], resource.optDouble("height", scene.canvas.height.toDouble())).toFloat()
        canvas.drawBitmap(bitmap, null, RectF(x, y, x + width, y + height), paint)'''
new_outro = '''        val bitmap = decodeBitmap(scene, source, JSONObject(), cache = false)
            ?: error("Exact outro overlay frame asset '$source' is missing or undecodable.")
        try {
            if (truthy(props["replaceCanvas"] ?: resource.opt("replaceCanvas"), false)) {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            }
            val sampling = stringValue(props["sampling"] ?: resource.opt("sampling"))?.lowercase() ?: "nearest"
            val paint = Paint().apply {
                isAntiAlias = false
                isFilterBitmap = sampling == "linear"
                alpha = (255f * opacity).toInt().coerceIn(0, 255)
            }
            val x = number(props["x"], resource.optDouble("x", 0.0)).toFloat()
            val y = number(props["y"], resource.optDouble("y", 0.0)).toFloat()
            val width = number(props["width"], resource.optDouble("width", scene.canvas.width.toDouble())).toFloat()
            val height = number(props["height"], resource.optDouble("height", scene.canvas.height.toDouble())).toFloat()
            canvas.drawBitmap(bitmap, null, RectF(x, y, x + width, y + height), paint)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }'''
s = replace_once(s, old_outro, new_outro, "one-frame outro bitmap lifetime")

old_decode = '''    private fun decodeBitmap(scene: RendererV3Scene, source: String?, resource: JSONObject): Bitmap? {
        val inline = resource.optString("base64").takeIf { it.isNotBlank() }
        val cacheKey = source ?: inline?.hashCode()?.toString() ?: return null
        bitmapCache[cacheKey]?.takeIf { !it.isRecycled }?.let { return it }
        if (source != null) {
            val local = File(source)
            if (local.isFile) {
                return BitmapFactory.decodeFile(local.absolutePath)?.also { bitmapCache[cacheKey] = it }
            }
        }
        val bytes = when {
            inline != null -> runCatching { Base64.getDecoder().decode(inline) }.getOrNull()
            source != null -> scene.asset(source)
            else -> null
        } ?: return null
        return BitmapFactory.decodeStream(ByteArrayInputStream(bytes))?.also { bitmapCache[cacheKey] = it }
    }'''
new_decode = '''    private fun decodeBitmap(
        scene: RendererV3Scene,
        source: String?,
        resource: JSONObject,
        cache: Boolean = true,
    ): Bitmap? {
        val inline = resource.optString("base64").takeIf { it.isNotBlank() }
        val cacheKey = source ?: inline?.hashCode()?.toString() ?: return null
        if (cache) bitmapCache.get(cacheKey)?.takeIf { !it.isRecycled }?.let { return it }
        if (source != null) {
            val local = File(source)
            if (local.isFile) {
                return BitmapFactory.decodeFile(local.absolutePath)?.also { if (cache) bitmapCache.put(cacheKey, it) }
            }
        }
        val bytes = when {
            inline != null -> runCatching { Base64.getDecoder().decode(inline) }.getOrNull()
            source != null -> scene.asset(source)
            else -> null
        } ?: return null
        return BitmapFactory.decodeStream(ByteArrayInputStream(bytes))?.also { if (cache) bitmapCache.put(cacheKey, it) }
    }'''
s = replace_once(s, old_decode, new_decode, "bounded bitmap decoder")
FRAME.write_text(s)


# ---------------------------------------------------------------------------
# Regression test: package contents remain file-backed through Store.inspect().
# ---------------------------------------------------------------------------
TEST.parent.mkdir(parents=True, exist_ok=True)
TEST.write_text(r'''package io.github.retrofrost.cts.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RendererV3StreamingPackageTest {
    @Test
    fun storeInspectionKeepsLargeSidecarsOffHeap() {
        val root = createTempDir(prefix = "renderer-v3-stream-")
        try {
            val packageFile = File(root, "large.renderer3")
            val sidecar = ByteArray(4 * 1024 * 1024) { index -> ((index * 73 + 19) and 0xff).toByte() }
            packageFile.writeBytes(packageBytes(sidecar))

            val direct = RendererV3Bundle.read(packageFile)
            assertTrue(direct.scene.assets.isEmpty())
            assertEquals(packageFile.canonicalPath, direct.scene.packageFile?.canonicalPath)
            assertTrue("assets/large.bin" in direct.scene.assetEntries)
            assertArrayEquals(sidecar, direct.scene.asset("assets/large.bin"))

            val store = RendererStore(File(root, "store"))
            val candidate = packageFile.inputStream().use(store::inspect)
            assertEquals(0, candidate.bytes.size)
            assertNotNull(candidate.sourceFile)
            assertTrue(candidate.sourceFile!!.isFile)
            assertTrue(candidate.report.compatible)
            store.discard(candidate)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun packageBytes(sidecar: ByteArray): ByteArray {
        val scene = JSONObject()
            .put("api", 3)
            .put("id", "streaming-package-test")
            .put("name", "Streaming package test")
            .put("minAppVersion", "3.0.300")
            .put("canvas", JSONObject().put("width", 64).put("height", 64).put("fps", 60))
            .put("timeline", JSONObject().put("frames", 2).put("clock", "absolute").put("implicitAnimation", false))
            .put("features", JSONArray().put("renderer-api-v3-scene-ir").put("renderer-v3-sidecar-resources").put("renderer-v3-zip-package"))
            .put("resources", JSONObject())
            .put("objects", JSONArray())
            .put("selectors", JSONArray())
            .put("layers", JSONArray())
            .put("checkpoints", JSONArray().put(0))
        val container = renderer3(scene)
        return ByteArrayOutputStream().use { destination ->
            ZipOutputStream(destination).use { zip ->
                zip.putNextEntry(ZipEntry("renderer.renderer3"))
                zip.write(container)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("assets/large.bin"))
                zip.write(sidecar)
                zip.closeEntry()
            }
            destination.toByteArray()
        }
    }

    private fun renderer3(scene: JSONObject): ByteArray {
        val raw = scene.toString().toByteArray(Charsets.UTF_8)
        val payload = ByteArrayOutputStream().use { destination ->
            GZIPOutputStream(destination).use { it.write(raw) }
            destination.toByteArray()
        }
        val crc = CRC32().apply { update(payload) }.value
        return ByteArrayOutputStream().use { destination ->
            DataOutputStream(destination).use { data ->
                data.write("CCRNDR03".toByteArray(Charsets.US_ASCII))
                data.writeInt(1)
                data.writeInt(payload.size)
                data.writeInt(crc.toInt())
                data.write(payload)
            }
            destination.toByteArray()
        }
    }
}
''')

print("Applied file-backed Renderer v3 packages, bounded image cache, and large-package regression test")
