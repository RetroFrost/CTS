#!/usr/bin/env python3
"""Make Renderer API v3 packages genuinely disk-backed on Android.

Large source-exact renderers can contain tens/hundreds of MB of sidecar artwork.
The app must never require the whole package, every ZIP entry, or every decoded
bitmap to coexist in the managed heap. This patch:

* stages imports to disk while hashing them incrementally;
* parses ZIP packages with ZipFile and keeps only the scene manifest in memory;
* lazily opens individual sidecars from the installed package;
* copies/compares installed renderers with streaming I/O and streaming SHA-256;
* reopens export snapshots through the file-backed reader;
* bounds the decoded bitmap cache so frame sequences cannot grow without limit;
* cleans abandoned import staging files; and
* decodes percent-escaped display names in the import dialog.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source match, found {count}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# RendererV3.kt: package-backed scenes and lazy ZIP sidecar streams.
# ---------------------------------------------------------------------------
path = ANDROID / "RendererV3.kt"
text = path.read_text()

if "import java.io.File\n" not in text:
    text = text.replace("import java.io.DataOutputStream\n", "import java.io.DataOutputStream\nimport java.io.File\nimport java.io.FilterInputStream\nimport java.io.InputStream\n", 1)
if "import java.util.zip.ZipFile\n" not in text:
    text = text.replace("import java.util.zip.ZipInputStream\n", "import java.util.zip.ZipInputStream\nimport java.util.zip.ZipFile\n", 1)

old_scene_tail = '''    val raw: JSONObject,
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
}
'''
new_scene_tail = '''    val raw: JSONObject,
    val assets: Map<String, ByteArray> = emptyMap(),
    val assetEntries: Set<String> = emptySet(),
    val packageFile: File? = null,
    val originalBytes: ByteArray = ByteArray(0),
) {
    fun resource(id: String?): JSONObject? = id?.let { resources.optJSONObject(it) }

    private fun normalizedAsset(path: String?): String? =
        path?.replace('\\\\', '/')?.removePrefix("./")?.takeIf { it.isNotBlank() }

    private fun resolvedAsset(path: String?): String? {
        val normalized = normalizedAsset(path) ?: return null
        if (normalized in assets || normalized in assetEntries) return normalized
        return (assets.keys.asSequence() + assetEntries.asSequence())
            .firstOrNull { it.endsWith("/$normalized") }
    }

    fun assetExists(path: String?): Boolean = resolvedAsset(path) != null

    /**
     * Opens one sidecar without materialising the whole package. The returned stream
     * owns its ZipFile and closes it when the stream is closed.
     */
    fun openAsset(path: String?): InputStream? {
        val resolved = resolvedAsset(path) ?: return null
        assets[resolved]?.let { return ByteArrayInputStream(it) }
        val packagePath = packageFile?.takeIf(File::isFile) ?: return null
        val zip = ZipFile(packagePath)
        val entry = zip.getEntry(resolved)
        if (entry == null || entry.isDirectory) {
            zip.close()
            return null
        }
        if (entry.size !in 0..RendererV3Bundle.MAX_ASSET_BYTES.toLong()) {
            zip.close()
            error("Renderer v3 asset '$resolved' is too large.")
        }
        val source = zip.getInputStream(entry)
        return object : FilterInputStream(source) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    zip.close()
                }
            }
        }
    }

    /** Backwards-compatible helper for small sidecars/tests. Rendering uses openAsset(). */
    fun asset(path: String?): ByteArray? = openAsset(path)?.use {
        RendererV3Bundle.readLimitedAsset(it)
    }

    fun objectById(id: String): RendererV3Object? = objects.firstOrNull { it.id == id }
}
'''
text = replace_once(text, old_scene_tail, new_scene_tail, "disk-backed v3 scene")

if "const val MAX_ASSET_BYTES" not in text:
    text = text.replace(
        "    private const val MAX_PACKAGE_ENTRIES = 2048\n",
        "    private const val MAX_PACKAGE_ENTRIES = 2048\n    internal const val MAX_ASSET_BYTES = 32 * 1024 * 1024\n",
        1,
    )

accept_anchor = '''    fun accepts(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes.size >= 8 && String(bytes, 0, 8, Charsets.US_ASCII) == MAGIC) return true
        return bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
    }

'''
accept_file = accept_anchor + '''    fun accepts(file: File): Boolean {
        if (!file.isFile || file.length() < 4L) return false
        val header = ByteArray(8)
        val count = file.inputStream().buffered().use { it.read(header) }
        if (count >= 8 && String(header, 0, 8, Charsets.US_ASCII) == MAGIC) return true
        return count >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
    }

    fun read(file: File): RendererV3ReadResult {
        require(file.isFile) { "Renderer v3 package does not exist." }
        require(file.length() in 1..MAX_FILE_BYTES.toLong()) { "Renderer v3 package is too large." }
        val result = if (file.inputStream().buffered().use { input ->
                val magic = ByteArray(8)
                input.read(magic) >= 8 && String(magic, Charsets.US_ASCII) == MAGIC
            }) {
            // Raw scenes are compact manifests; ZIP packages are the large-resource path.
            parseContainer(file.inputStream().use { readLimited(it, MAX_FILE_BYTES) }, emptyMap(), ByteArray(0))
        } else {
            parsePackage(file)
        }
        RendererV3Runtime.register(result.scene)
        return result
    }

'''
text = replace_once(text, accept_anchor, accept_file, "v3 file reader")

package_bytes_block = '''    private fun parsePackage(packageBytes: ByteArray): RendererV3ReadResult {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(packageBytes)).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                count += 1
                require(count <= MAX_PACKAGE_ENTRIES) { "Renderer v3 package contains too many files." }
                val name = safeEntryName(entry.name)
                val bytes = readLimited(zip, MAX_FILE_BYTES)
                entries[name] = bytes
                zip.closeEntry()
            }
        }
        val sceneEntry = entries.entries.firstOrNull { it.key.equals("renderer.renderer3", true) }
            ?: entries.entries.firstOrNull { it.key.equals("manifest.renderer3", true) }
            ?: entries.entries.firstOrNull { it.key.endsWith(".renderer3", true) }
            ?: error("Renderer v3 ZIP has no .renderer3 scene file.")
        val sidecars = entries.filterKeys { it != sceneEntry.key }
        return parseContainer(sceneEntry.value, sidecars, packageBytes)
    }

'''
package_file_block = package_bytes_block + '''    private fun parsePackage(packageFile: File): RendererV3ReadResult {
        ZipFile(packageFile).use { zip ->
            var count = 0
            val names = linkedSetOf<String>()
            var sceneName: String? = null
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                count += 1
                require(count <= MAX_PACKAGE_ENTRIES) { "Renderer v3 package contains too many files." }
                val name = safeEntryName(entry.name)
                names += name
                if (sceneName == null && (
                        name.equals("renderer.renderer3", true) ||
                            name.equals("manifest.renderer3", true) ||
                            name.endsWith(".renderer3", true)
                    )) {
                    sceneName = name
                }
            }
            val selected = sceneName ?: error("Renderer v3 ZIP has no .renderer3 scene file.")
            val sceneEntry = requireNotNull(zip.getEntry(selected))
            val sceneBytes = zip.getInputStream(sceneEntry).use { readLimited(it, MAX_FILE_BYTES) }
            val sidecars = names.filterTo(linkedSetOf()) { it != selected }
            return parseContainer(
                container = sceneBytes,
                assets = emptyMap(),
                originalBytes = ByteArray(0),
                assetEntries = sidecars,
                packageFile = packageFile,
            )
        }
    }

'''
text = replace_once(text, package_bytes_block, package_file_block, "streamed v3 ZIP parser")

old_parse_container_sig = '''    private fun parseContainer(
        container: ByteArray,
        assets: Map<String, ByteArray>,
        originalBytes: ByteArray,
    ): RendererV3ReadResult {
'''
new_parse_container_sig = '''    private fun parseContainer(
        container: ByteArray,
        assets: Map<String, ByteArray>,
        originalBytes: ByteArray,
        assetEntries: Set<String> = assets.keys,
        packageFile: File? = null,
    ): RendererV3ReadResult {
'''
text = replace_once(text, old_parse_container_sig, new_parse_container_sig, "v3 package metadata")
text = replace_once(
    text,
    '        val scene = parseScene(root, assets, originalBytes)\n',
    '        val scene = parseScene(root, assets, originalBytes, assetEntries, packageFile)\n',
    "v3 parse scene disk metadata",
)
text = replace_once(
    text,
    '    private fun parseScene(root: JSONObject, assets: Map<String, ByteArray>, originalBytes: ByteArray): RendererV3Scene {\n',
    '    private fun parseScene(\n        root: JSONObject,\n        assets: Map<String, ByteArray>,\n        originalBytes: ByteArray,\n        assetEntries: Set<String> = assets.keys,\n        packageFile: File? = null,\n    ): RendererV3Scene {\n',
    "v3 scene parser disk metadata",
)
text = replace_once(
    text,
    '''            selectors = selectors,
            assets = assets,
            frames = frames,
''',
    '''            selectors = selectors,
            assets = assets,
            assetEntries = assetEntries,
            frames = frames,
''',
    "v3 validation asset index",
)
text = replace_once(
    text,
    '''            raw = root,
            assets = assets,
            originalBytes = originalBytes,
''',
    '''            raw = root,
            assets = assets,
            assetEntries = assetEntries,
            packageFile = packageFile,
            originalBytes = originalBytes,
''',
    "v3 scene disk fields",
)
text = replace_once(
    text,
    '''        selectors: List<RendererV3Selector>,
        assets: Map<String, ByteArray>,
        frames: Int,
''',
    '''        selectors: List<RendererV3Selector>,
        assets: Map<String, ByteArray>,
        assetEntries: Set<String> = assets.keys,
        frames: Int,
''',
    "v3 validation disk assets signature",
)
text = replace_once(
    text,
    '''        fun assetExists(path: String): Boolean {
            val normalized = path.replace('\\\\', '/').removePrefix("./")
            return assets.containsKey(normalized) || assets.keys.any { it.endsWith("/$normalized") }
        }
''',
    '''        fun assetExists(path: String): Boolean {
            val normalized = path.replace('\\\\', '/').removePrefix("./")
            return assets.containsKey(normalized) || assetEntries.contains(normalized) ||
                assets.keys.any { it.endsWith("/$normalized") } || assetEntries.any { it.endsWith("/$normalized") }
        }
''',
    "v3 validation lazy asset lookup",
)

write_anchor = '''    fun write(scene: RendererV3Scene, output: OutputStream) {
        if (scene.originalBytes.isNotEmpty()) {
'''
write_new = '''    fun write(scene: RendererV3Scene, output: OutputStream) {
        scene.packageFile?.takeIf(File::isFile)?.let { source ->
            source.inputStream().buffered().use { it.copyTo(output, 64 * 1024) }
            return
        }
        if (scene.originalBytes.isNotEmpty()) {
'''
text = replace_once(text, write_anchor, write_new, "v3 streamed package export")

# RendererV3Scene.asset() needs a bounded helper that is visible inside this file.
helper_anchor = '''    private fun readLimited(input: InputStream, limit: Int): ByteArray {
'''
helper = '''    internal fun readLimitedAsset(input: InputStream): ByteArray = readLimited(input, MAX_ASSET_BYTES)

'''
if helper not in text:
    if helper_anchor not in text:
        raise SystemExit("v3 readLimited helper anchor changed")
    text = text.replace(helper_anchor, helper + helper_anchor, 1)

path.write_text(text)


# ---------------------------------------------------------------------------
# RendererBundle.kt / RendererStore: never hold full renderer packages in heap.
# ---------------------------------------------------------------------------
path = ANDROID / "RendererBundle.kt"
text = path.read_text()

old_candidate = '''data class RendererCandidate(
    val bytes: ByteArray,
    val spec: RendererSpec,
    val sha256: String,
    val report: RendererValidationReport,
)
'''
new_candidate = '''data class RendererCandidate(
    val bytes: ByteArray,
    val spec: RendererSpec,
    val sha256: String,
    val report: RendererValidationReport,
    val stagedFile: File? = null,
)
'''
text = replace_once(text, old_candidate, new_candidate, "renderer candidate staging file")

text = replace_once(
    text,
    '    private val libraryDir = File(dir, "library")\n',
    '    private val libraryDir = File(dir, "library")\n    private val stagingDir = File(dir, "staging")\n',
    "renderer staging directory",
)

old_active = '''    fun active(): RendererSpec = if (activeFile.isFile) {
        runCatching { activeFile.inputStream().use(RendererBundle::read) }
            .getOrElse { RendererSpec.builtIn() }
    } else {
        RendererSpec.builtIn()
    }
'''
new_active = '''    fun active(): RendererSpec = if (activeFile.isFile) {
        runCatching { RendererBundle.read(activeFile) }
            .getOrElse { RendererSpec.builtIn() }
    } else {
        RendererSpec.builtIn()
    }
'''
text = replace_once(text, old_active, new_active, "streamed active renderer read")

old_inspect = '''    fun inspect(input: InputStream): RendererCandidate {
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
'''
new_inspect = '''    fun inspect(input: InputStream): RendererCandidate {
        stagingDir.mkdirs()
        // Remove abandoned imports from prior process deaths without touching a live import.
        stagingDir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24L * 60L * 60L * 1000L }
            ?.forEach(File::delete)
        val staged = File(stagingDir, "renderer-${System.nanoTime()}.incoming")
        return try {
            val digest = copyLimitedAndHash(input, staged, RendererBundle.MAX_FILE_BYTES)
            val spec = RendererBundle.read(staged)
            val structural = RendererBundle.validateDetailed(spec)
            val compatibility = RendererCapabilities.report(spec)
            val report = RendererValidationReport(
                structural.errors + compatibility.errors,
                structural.warnings + compatibility.warnings,
            )
            RendererCandidate(ByteArray(0), spec, digest, report, staged)
        } catch (failure: Throwable) {
            staged.delete()
            throw failure
        }
    }
'''
text = replace_once(text, old_inspect, new_inspect, "streamed renderer inspection")

old_install = '''    fun install(candidate: RendererCandidate): InstalledRenderer {
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
    }
'''
new_install = '''    fun install(candidate: RendererCandidate): InstalledRenderer {
        require(candidate.report.compatible) { candidate.report.errors.joinToString("\\n") }
        libraryDir.mkdirs()
        val destination = File(libraryDir, "${candidate.spec.id}.renderer")
        val staged = candidate.stagedFile?.takeIf(File::isFile)
        if (staged != null) atomicCopy(staged, destination) else atomicWrite(destination, candidate.bytes)
        // Relocate the registered v3 scene from the disposable staging file to
        // the installed package before deleting that staging file.
        val installedSpec = RendererBundle.read(destination)
        require(installedSpec.id == candidate.spec.id) { "Installed renderer identity changed while copying." }
        val installedSha = sha256(destination)
        val activeSha = activeFile.takeIf(File::isFile)?.let(::sha256)
        // Installing without activation must not replace a currently active scene
        // that happens to use the same renderer ID.
        activeFile.takeIf(File::isFile)?.let { file ->
            runCatching { RendererBundle.read(file) }
        }
        staged?.delete()
        return InstalledRenderer(installedSpec, destination, activeSha == installedSha)
    }
'''
text = replace_once(text, old_install, new_install, "streamed renderer installation")

old_activate = '''    fun activate(id: String): RendererSpec {
        val file = File(libraryDir, "$id.renderer")
        require(file.isFile) { "Renderer '$id' is not installed." }
        val bytes = file.readBytes()
        val spec = RendererBundle.read(ByteArrayInputStream(bytes))
        val report = RendererCapabilities.report(spec)
        require(report.compatible) { report.errors.joinToString("\\n") }
        // Renderer compatibility is a project-quality diagnostic, never an import
        // gate. A renderer is a reusable visual/timing profile and must be installable
        // on a new, empty or differently-sized project. Exact-v2 still forces its
        // reference output size/FPS in the render/export path.
        dir.mkdirs()
        val current = activeFile.takeIf(File::isFile)?.readBytes()
        if (current == null || !current.contentEquals(bytes)) {
            if (current != null) atomicWrite(previousFile, current)
            atomicWrite(activeFile, bytes)
        }
        RendererBridge.setRuntimeActive(spec)
        return spec
    }
'''
new_activate = '''    fun activate(id: String): RendererSpec {
        val file = File(libraryDir, "$id.renderer")
        require(file.isFile) { "Renderer '$id' is not installed." }
        val spec = RendererBundle.read(file)
        val report = RendererCapabilities.report(spec)
        require(report.compatible) { report.errors.joinToString("\\n") }
        // Renderer compatibility is a project-quality diagnostic, never an import gate.
        dir.mkdirs()
        val incomingSha = sha256(file)
        val currentSha = activeFile.takeIf(File::isFile)?.let(::sha256)
        if (currentSha != incomingSha) {
            if (activeFile.isFile) atomicCopy(activeFile, previousFile)
            atomicCopy(file, activeFile)
        }
        RendererBridge.setRuntimeActive(spec)
        return spec
    }
'''
text = replace_once(text, old_activate, new_activate, "streamed renderer activation")

old_rollback = '''    fun rollback(): RendererSpec {
        require(previousFile.isFile) { "There is no previous renderer to restore." }
        val bytes = previousFile.readBytes()
        val spec = RendererBundle.read(ByteArrayInputStream(bytes))
        val current = if (activeFile.isFile) activeFile.readBytes() else null
        atomicWrite(activeFile, bytes)
        if (current != null) atomicWrite(previousFile, current) else previousFile.delete()
        RendererBridge.setRuntimeActive(spec)
        return spec
    }

    fun activeSha256(): String? = activeFile.takeIf(File::isFile)?.readBytes()?.let(::sha256)

    fun installedSha256(id: String): String? = File(libraryDir, "$id.renderer")
        .takeIf(File::isFile)
        ?.readBytes()
        ?.let(::sha256)
'''
new_rollback = '''    fun rollback(): RendererSpec {
        require(previousFile.isFile) { "There is no previous renderer to restore." }
        val spec = RendererBundle.read(previousFile)
        val swap = File(dir, "rollback-${System.nanoTime()}.tmp")
        if (activeFile.isFile) atomicCopy(activeFile, swap)
        atomicCopy(previousFile, activeFile)
        if (swap.isFile) {
            atomicCopy(swap, previousFile)
            swap.delete()
        } else previousFile.delete()
        RendererBridge.setRuntimeActive(spec)
        return spec
    }

    fun activeSha256(): String? = activeFile.takeIf(File::isFile)?.let(::sha256)

    fun installedSha256(id: String): String? = File(libraryDir, "$id.renderer")
        .takeIf(File::isFile)
        ?.let(::sha256)
'''
text = replace_once(text, old_rollback, new_rollback, "streamed renderer rollback and hashes")

old_list = '''    fun listInstalled(): List<InstalledRenderer> {
        val activeBytes = activeFile.takeIf(File::isFile)?.readBytes()
        if (!libraryDir.isDirectory) return emptyList()
        return libraryDir.listFiles { file -> file.isFile && file.extension.equals("renderer", true) }
            .orEmpty()
            .mapNotNull { file ->
                runCatching {
                    val bytes = file.readBytes()
                    val spec = RendererBundle.read(ByteArrayInputStream(bytes))
                    InstalledRenderer(spec, file, activeBytes?.contentEquals(bytes) == true)
                }.getOrNull()
            }
            .sortedBy { it.spec.name.lowercase() }
    }
'''
new_list = '''    fun listInstalled(): List<InstalledRenderer> {
        val activeSha = activeFile.takeIf(File::isFile)?.let(::sha256)
        if (!libraryDir.isDirectory) return emptyList()
        return libraryDir.listFiles { file -> file.isFile && file.extension.equals("renderer", true) }
            .orEmpty()
            .mapNotNull { file ->
                runCatching {
                    val spec = RendererBundle.read(file)
                    InstalledRenderer(spec, file, activeSha != null && activeSha == sha256(file))
                }.getOrNull()
            }
            .sortedBy { it.spec.name.lowercase() }
    }
'''
text = replace_once(text, old_list, new_list, "streamed renderer library listing")

old_uninstall = '''    fun uninstall(id: String) {
        val target = File(libraryDir, "$id.renderer")
        require(target.isFile) { "Renderer '$id' is not installed." }
        val activeBytes = activeFile.takeIf(File::isFile)?.readBytes()
        val targetBytes = target.readBytes()
        require(activeBytes == null || !activeBytes.contentEquals(targetBytes)) {
            "Activate another renderer before deleting the active renderer."
        }
        require(target.delete()) { "Renderer '$id' could not be deleted." }
    }
'''
new_uninstall = '''    fun uninstall(id: String) {
        val target = File(libraryDir, "$id.renderer")
        require(target.isFile) { "Renderer '$id' is not installed." }
        val activeSha = activeFile.takeIf(File::isFile)?.let(::sha256)
        require(activeSha == null || activeSha != sha256(target)) {
            "Activate another renderer before deleting the active renderer."
        }
        require(target.delete()) { "Renderer '$id' could not be deleted." }
        RendererV3Runtime.forget(id)
    }
'''
text = replace_once(text, old_uninstall, new_uninstall, "streamed renderer uninstall")

text = replace_once(
    text,
    '''    fun reset(): RendererSpec {
        if (activeFile.isFile) atomicWrite(previousFile, activeFile.readBytes())
        activeFile.delete()
        return RendererSpec.builtIn().also(RendererBridge::setRuntimeActive)
    }
''',
    '''    fun reset(): RendererSpec {
        if (activeFile.isFile) atomicCopy(activeFile, previousFile)
        activeFile.delete()
        return RendererSpec.builtIn().also(RendererBridge::setRuntimeActive)
    }

    fun discard(candidate: RendererCandidate?) {
        val pending = candidate ?: return
        val staged = pending.stagedFile?.takeIf(File::isFile) ?: return
        staged.delete()
        // Inspecting a same-ID update temporarily replaces the active scene in
        // RendererV3Runtime. Restore the active package after cancellation.
        val active = activeFile.takeIf(File::isFile)?.let { file ->
            runCatching { RendererBundle.read(file) }.getOrNull()
        }
        if (active?.id != pending.spec.id) RendererV3Runtime.forget(pending.spec.id)
    }
''',
    "renderer staging cleanup",
)

atomic_anchor = '''    private fun atomicWrite(destination: File, bytes: ByteArray) {
'''
atomic_copy = '''    private fun atomicCopy(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        val tmp = File(destination.parentFile, destination.name + ".tmp")
        source.inputStream().buffered(64 * 1024).use { input ->
            FileOutputStream(tmp).use { output ->
                input.copyTo(output, 64 * 1024)
                output.fd.sync()
            }
        }
        try {
            Files.move(tmp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

'''
if atomic_copy not in text:
    text = text.replace(atomic_anchor, atomic_copy + atomic_anchor, 1)

old_read_hash = '''    private fun readLimited(input: InputStream, limit: Int): ByteArray {
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
'''
new_read_hash = '''    private fun copyLimitedAndHash(input: InputStream, destination: File, limit: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        FileOutputStream(destination).use { output ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit.toLong()) { "Renderer file is too large." }
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().buffered(64 * 1024).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
'''
text = replace_once(text, old_read_hash, new_read_hash, "streamed renderer hash")

read_signature = '''    fun read(input: InputStream): RendererSpec {
'''
file_overload = '''    fun read(file: File): RendererSpec {
        require(file.isFile) { "Renderer file does not exist." }
        require(file.length() in 1..MAX_FILE_BYTES.toLong()) { "Renderer file is too large." }
        if (RendererV3Bundle.accepts(file)) return RendererV3Bundle.read(file).spec
        return file.inputStream().buffered().use(::readLegacy)
    }

'''
if file_overload not in text:
    if read_signature not in text:
        raise SystemExit("renderer file reader insertion anchor changed")
    text = text.replace(read_signature, file_overload + read_signature, 1)

path.write_text(text)


# ---------------------------------------------------------------------------
# RendererV3FrameRenderer.kt: lazy asset decode + bounded bitmap LRU.
# ---------------------------------------------------------------------------
path = ANDROID / "RendererV3FrameRenderer.kt"
text = path.read_text()
text = text.replace("import java.util.concurrent.ConcurrentHashMap\n", "import android.util.LruCache\n")

old_cache = '''class RendererV3FrameRenderer {
    private val bitmapCache = ConcurrentHashMap<String, Bitmap>()
'''
new_cache = '''class RendererV3FrameRenderer {
    // A 1920x1080 ARGB frame is ~8 MiB. Keep only a small working set so a
    // frame-addressed ending cannot retain hundreds of decoded bitmaps.
    private val bitmapCache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && oldValue !== newValue && !oldValue.isRecycled) oldValue.recycle()
        }
    }
'''
text = replace_once(text, old_cache, new_cache, "bounded renderer bitmap cache")

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
    }
'''
new_decode = '''    private fun decodeBitmap(scene: RendererV3Scene, source: String?, resource: JSONObject): Bitmap? {
        val inline = resource.optString("base64").takeIf { it.isNotBlank() }
        val cacheKey = source ?: inline?.hashCode()?.toString() ?: return null
        bitmapCache.get(cacheKey)?.takeIf { !it.isRecycled }?.let { return it }
        if (source != null) {
            val local = File(source)
            if (local.isFile) {
                return BitmapFactory.decodeFile(local.absolutePath)?.also { bitmapCache.put(cacheKey, it) }
            }
        }
        val bitmap = when {
            inline != null -> runCatching {
                val bytes = Base64.getDecoder().decode(inline)
                BitmapFactory.decodeStream(ByteArrayInputStream(bytes))
            }.getOrNull()
            source != null -> scene.openAsset(source)?.use(BitmapFactory::decodeStream)
            else -> null
        } ?: return null
        bitmapCache.put(cacheKey, bitmap)
        return bitmap
    }
'''
text = replace_once(text, old_decode, new_decode, "lazy renderer bitmap decode")

path.write_text(text)


# ---------------------------------------------------------------------------
# RendererImportActivity.kt: release abandoned staging files and decode names.
# ---------------------------------------------------------------------------
path = ANDROID / "RendererImportActivity.kt"
text = path.read_text()

old_destroy = '''    override fun onDestroy() {
        preview?.takeIf { !it.isRecycled }?.recycle()
        preview = null
        super.onDestroy()
    }
'''
new_destroy = '''    override fun onDestroy() {
        preview?.takeIf { !it.isRecycled }?.recycle()
        preview = null
        store.discard(candidate)
        candidate = null
        super.onDestroy()
    }
'''
text = replace_once(text, old_destroy, new_destroy, "renderer import staging cleanup")

text = replace_once(
    text,
    '''    private fun inspect(uri: Uri) {
        preview?.recycle(); preview = null; candidate = null; error = null
''',
    '''    private fun inspect(uri: Uri) {
        preview?.recycle(); preview = null
        store.discard(candidate); candidate = null; error = null
''',
    "renderer replacement staging cleanup",
)

old_display = '''    private fun displayName(uri: Uri): String? {
        if (uri.scheme != "content") return uri.lastPathSegment?.substringAfterLast('/')
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }
'''
new_display = '''    private fun displayName(uri: Uri): String? {
        val raw = if (uri.scheme != "content") uri.lastPathSegment?.substringAfterLast('/') else runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return raw?.let(Uri::decode)
    }
'''
text = replace_once(text, old_display, new_display, "renderer import filename decode")
path.write_text(text)


# ---------------------------------------------------------------------------
# ExportService.kt: preserve file-backed sidecars for the full export lifetime.
# ---------------------------------------------------------------------------
path = ANDROID / "ExportService.kt"
text = path.read_text()
text = replace_once(
    text,
    '                val spec = rendererFile.inputStream().use(RendererBundle::read)\n',
    '''                // Keep Renderer v3 packages disk-backed during export. The stream
                // overload materializes its input for legacy callers, while the
                // file overload lazily opens v3 sidecars from the snapshot ZIP.
                val spec = RendererBundle.read(rendererFile)
''',
    "file-backed renderer export snapshot",
)
text = replace_once(
    text,
    '''                if (localWakeLock.isHeld) localWakeLock.release()
                snapshotDir.deleteRecursively()
''',
    '''                if (localWakeLock.isHeld) localWakeLock.release()
                // Renderer v3 scene lookup is process-global. Re-register the real
                // active file before deleting the export snapshot it temporarily used.
                runCatching { RendererStore(applicationContext).active() }
                snapshotDir.deleteRecursively()
''',
    "restore active renderer after snapshot export",
)
path.write_text(text)


# ---------------------------------------------------------------------------
# Regression coverage: prove package bytes/sidecars stay off heap after inspect.
# ---------------------------------------------------------------------------
path = ROOT / "android/app/src/androidTest/java/io/github/retrofrost/cts/android/RuntimeIntegrityInstrumentedTest.kt"
text = path.read_text()
anchor = '''    private fun rendererBytes(spec: RendererSpec): ByteArray = ByteArrayOutputStream().use { output ->
'''
test = '''    @Test
    fun rendererV3ZipImportIsDiskBackedAndSidecarsAreLazy() {
        val store = RendererStore(File(scratch, "v3-low-memory-store").apply { mkdirs() })
        val sceneRoot = JSONObject()
            .put("api", 3)
            .put("id", "test.v3.low-memory")
            .put("name", "Low-memory v3")
            .put("minAppVersion", "3.0.300")
            .put("canvas", JSONObject().put("width", 1920).put("height", 1080).put("fps", 60))
            .put("timeline", JSONObject().put("frames", 2).put("clock", "absolute").put("implicitAnimation", false))
            .put("features", JSONArray().put("renderer-v3-sidecar-resources").put("renderer-v3-zip-package"))
            .put("resources", JSONObject().put("pixel", JSONObject().put("type", "image").put("asset", "assets/pixel.bin")))
            .put("objects", JSONArray())
            .put("selectors", JSONArray())
        val sceneBytes = ByteArrayOutputStream().use { output ->
            val raw = sceneRoot.toString().toByteArray(Charsets.UTF_8)
            val compressed = ByteArrayOutputStream().use { gzOut ->
                java.util.zip.GZIPOutputStream(gzOut).use { it.write(raw) }
                gzOut.toByteArray()
            }
            val crc = java.util.zip.CRC32().apply { update(compressed) }.value
            java.io.DataOutputStream(output).use { data ->
                data.write("CCRNDR03".toByteArray(Charsets.US_ASCII))
                data.writeInt(1)
                data.writeInt(compressed.size)
                data.writeInt(crc.toInt())
                data.write(compressed)
            }
            output.toByteArray()
        }
        fun packageBytes(fill: Byte) = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("renderer.renderer3"))
                zip.write(sceneBytes)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("assets/pixel.bin"))
                repeat(1024) { zip.write(ByteArray(1024) { fill }) }
                zip.closeEntry()
            }
            output.toByteArray()
        }
        val candidate = store.inspect(ByteArrayInputStream(packageBytes(0x5a)))
        assertTrue("Large-package candidate must not retain package bytes", candidate.bytes.isEmpty())
        assertTrue(candidate.stagedFile?.isFile == true)
        val stagedFile = requireNotNull(candidate.stagedFile)
        val scene = requireNotNull(RendererV3Runtime.scene(candidate.spec))
        assertTrue(scene.assets.isEmpty())
        assertTrue(scene.assetEntries.contains("assets/pixel.bin"))
        assertEquals(0x5a, scene.openAsset("assets/pixel.bin")!!.use { it.read() })

        val installed = store.install(candidate)
        assertFalse("Install must remove its staging file", stagedFile.exists())
        val installedScene = requireNotNull(RendererV3Runtime.scene(candidate.spec))
        assertEquals(installed.file.canonicalPath, installedScene.packageFile?.canonicalPath)
        assertEquals(0x5a, installedScene.openAsset("assets/pixel.bin")!!.use { it.read() })
        store.activate(candidate.spec.id)

        val replacement = store.inspect(ByteArrayInputStream(packageBytes(0x33)))
        assertEquals(0x33, RendererV3Runtime.scene(candidate.spec)!!.openAsset("assets/pixel.bin")!!.use { it.read() })
        store.discard(replacement)
        val restored = requireNotNull(RendererV3Runtime.scene(candidate.spec))
        assertEquals(0x5a, restored.openAsset("assets/pixel.bin")!!.use { it.read() })

        val installOnlyReplacement = store.inspect(ByteArrayInputStream(packageBytes(0x44)))
        val replacementStage = requireNotNull(installOnlyReplacement.stagedFile)
        store.install(installOnlyReplacement)
        assertFalse(replacementStage.exists())
        val stillActive = requireNotNull(RendererV3Runtime.scene(candidate.spec))
        assertEquals(0x5a, stillActive.openAsset("assets/pixel.bin")!!.use { it.read() })
    }

'''
if test not in text:
    if anchor not in text:
        raise SystemExit("v3 low-memory test anchor changed")
    text = text.replace(anchor, test + anchor, 1)
path.write_text(text)

print("Applied disk-backed/lazy Renderer API v3 import and bounded render cache")
