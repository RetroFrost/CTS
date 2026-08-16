from pathlib import Path


def replace(path: str, old: str, new: str, count: int = 1) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"expected text not found in {path}: {old[:80]!r}")
    p.write_text(text.replace(old, new, count), encoding="utf-8")


replace("android/app/build.gradle.kts", "versionCode = 20004", "versionCode = 20005")
replace("android/app/build.gradle.kts", 'versionName = "2.0.4"', 'versionName = "2.0.5"')
replace(
    "android/app/build.gradle.kts",
    '    implementation("androidx.core:core-ktx:1.16.0")\n',
    '    implementation("androidx.core:core-ktx:1.16.0")\n    implementation("androidx.documentfile:documentfile:1.0.1")\n',
)

app = Path("android/app/src/main/java/io/github/retrofrost/cts/android/FinalStudioApp.kt")
text = app.read_text(encoding="utf-8")
old = '''    val exportVideo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        if (uri != null) {
            isPlaying = false
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            FinalExportService.start(context, project.toJson(), uri)
            message = "Background export started · safe to leave the app or turn off the screen"
        }
    }
'''
new = '''    val exportFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            isPlaying = false
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            FinalExportService.start(context, project.toJson(), uri)
            message = "Background export started · MP4 and thumbnails will be saved in this folder"
        }
    }
'''
if old not in text:
    raise SystemExit("Android export launcher block not found")
text = text.replace(old, new, 1)
text = text.replace('exportVideo.launch("Cubical-Compare-2.0.4.mp4")', "exportFolder.launch(null)", 1)
app.write_text(text, encoding="utf-8")

service = Path("android/app/src/main/java/io/github/retrofrost/cts/android/FinalExportService.kt")
text = service.read_text(encoding="utf-8")
text = text.replace(
    "import androidx.core.content.ContextCompat\n",
    "import androidx.core.content.ContextCompat\nimport androidx.documentfile.provider.DocumentFile\n",
    1,
)
text = text.replace("        val uri = Uri.parse(uriText)\n", "        val folderUri = Uri.parse(uriText)\n", 1)
old_job = '''                val project = StudioProject.fromJson(json)
                FinalExportEngine(applicationContext, project, { cancelled }) { percent, stage, detail ->
                    FinalExportState.update(ExportProgress(true, percent, stage, detail))
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID,
                        notification(percent, "$stage · $detail", true),
                    )
                }.export(uri)
                preferences.edit().clear().apply()
                FinalExportState.update(ExportProgress(false, 100, "Finished", "MP4 saved"))
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(100, "Export finished · MP4 saved", false),
                )
'''
new_job = '''                val project = StudioProject.fromJson(json)
                val folder = DocumentFile.fromTreeUri(applicationContext, folderUri)
                    ?: error("The selected export folder could not be opened.")
                require(folder.canWrite()) { "The selected export folder is not writable." }
                val baseName = exportBaseName(project)
                val video = replaceDocument(folder, "video/mp4", "$baseName.mp4")
                FinalExportEngine(applicationContext, project, { cancelled }) { percent, stage, detail ->
                    val mapped = (percent.coerceIn(0, 100) * 94 / 100).coerceIn(0, 94)
                    FinalExportState.update(ExportProgress(true, mapped, stage, detail))
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID,
                        notification(mapped, "$stage · $detail", true),
                    )
                }.export(video.uri)

                if (cancelled) throw CancellationException("Export cancelled")
                FinalExportState.update(ExportProgress(true, 95, "Thumbnails", "Creating WatchData-inspired thumbnails"))
                val thumbnails = ThumbnailGenerator.create(project, baseName)
                thumbnails.forEachIndexed { index, thumbnail ->
                    if (cancelled) throw CancellationException("Export cancelled")
                    val document = replaceDocument(folder, "image/jpeg", thumbnail.fileName)
                    contentResolver.openOutputStream(document.uri, "w")?.use { output ->
                        output.write(thumbnail.jpeg)
                    } ?: error("Could not write ${thumbnail.fileName}.")
                    val progress = 96 + index
                    FinalExportState.update(ExportProgress(true, progress, "Thumbnails", "Saved ${thumbnail.fileName}"))
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID,
                        notification(progress, "Thumbnails · ${index + 1} / ${thumbnails.size}", true),
                    )
                }
                preferences.edit().clear().apply()
                FinalExportState.update(ExportProgress(false, 100, "Finished", "MP4 + ${thumbnails.size} thumbnails saved"))
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(100, "Export finished · MP4 + ${thumbnails.size} thumbnails saved", false),
                )
'''
if old_job not in text:
    raise SystemExit("FinalExportService export job block not found")
text = text.replace(old_job, new_job, 1)
insert_at = "    private fun stopMissingRequest(startId: Int): Int {\n"
helpers = '''    private fun replaceDocument(folder: DocumentFile, mime: String, name: String): DocumentFile {
        folder.findFile(name)?.delete()
        return requireNotNull(folder.createFile(mime, name)) { "Could not create $name in the selected folder." }
    }

    private fun exportBaseName(project: StudioProject): String {
        val raw = project.name.trim().takeIf { it.isNotBlank() && !it.equals("Untitled", true) }
            ?: "Cubical-Compare-2.0.5"
        val safe = raw.replace(Regex("[\\\\/:*?\\\"<>|]"), "-").replace(Regex("\\s+"), " ").trim().take(96)
        return safe.ifBlank { "Cubical-Compare-2.0.5" }
    }

'''
if insert_at not in text:
    raise SystemExit("FinalExportService helper insertion point not found")
text = text.replace(insert_at, helpers + insert_at, 1)
service.write_text(text, encoding="utf-8")

win = Path("native/windows/main.cpp")
text = win.read_text(encoding="utf-8")
text = text.replace(
    "    fs::path assets;\n};",
    "    fs::path assets;\n    int thumbnail_count{0};\n    std::string thumbnail_error;\n};",
    1,
)
old_begin = '''    const fs::path cancel = app->cancel_file;
    std::thread([input, output, progress, cancel, window]() {
        auto* task = new TaskResult();
        task->kind = TaskResult::Kind::Export; task->output = output;
        task->process = cubical::run_engine({
            "export", narrow_path(input), narrow_path(output),
            "--progress-file", narrow_path(progress), "--cancel-file", narrow_path(cancel)
        });
        PostMessageW(window, WM_TASK_DONE, 0, reinterpret_cast<LPARAM>(task));
    }).detach();
'''
new_begin = '''    const fs::path cancel = app->cancel_file;
    const int total_frames = app->total_frames;
    std::thread([input, output, progress, cancel, window, total_frames]() {
        auto* task = new TaskResult();
        task->kind = TaskResult::Kind::Export; task->output = output;
        task->process = cubical::run_engine({
            "export", narrow_path(input), narrow_path(output),
            "--progress-file", narrow_path(progress), "--cancel-file", narrow_path(cancel)
        });
        if (task->process.exit_code == 0) {
            const double fractions[] = {0.16, 0.48, 0.78};
            for (int index = 0; index < 3; ++index) {
                const int frame = std::clamp(static_cast<int>((std::max(1, total_frames) - 1) * fractions[index]), 0, std::max(0, total_frames - 1));
                const fs::path thumb = output.parent_path() / (output.stem().wstring() + L" - Thumbnail " + std::to_wstring(index + 1) + L".jpg");
                const auto rendered = cubical::run_engine({
                    "render-preview", narrow_path(input), narrow_path(thumb),
                    "--frame", std::to_string(frame), "--width", "1280", "--height", "720"
                });
                if (rendered.exit_code != 0) {
                    task->thumbnail_error = rendered.output.empty() ? "Thumbnail rendering failed." : rendered.output;
                    break;
                }
                ++task->thumbnail_count;
            }
        }
        PostMessageW(window, WM_TASK_DONE, 0, reinterpret_cast<LPARAM>(task));
    }).detach();
'''
if old_begin not in text:
    raise SystemExit("Windows begin_export block not found")
text = text.replace(old_begin, new_begin, 1)
old_status = '                status(app,"Export finished · "+narrow_path(task->output));\n'
new_status = '''                if (task->thumbnail_error.empty()) {
                    status(app,"Export finished · "+std::to_string(task->thumbnail_count)+" thumbnails saved beside "+narrow_path(task->output));
                } else {
                    status(app,"MP4 saved · thumbnail warning: "+task->thumbnail_error);
                }
'''
if old_status not in text:
    raise SystemExit("Windows export completion status not found")
text = text.replace(old_status, new_status, 1)
text = text.replace('L"Cubical Compare 2.0 · Final"', 'L"Cubical Compare 2.0.5 · Final"', 1)
win.write_text(text, encoding="utf-8")

replace(
    "CMakeLists.txt",
    "project(CubicalCompare VERSION 2.0.0 LANGUAGES CXX)",
    "project(CubicalCompare VERSION 2.0.5 LANGUAGES CXX)",
)
