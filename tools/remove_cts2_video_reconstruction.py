from pathlib import Path

app_path = Path('android/app/src/main/java/io/github/retrofrost/cts/android/ui/CtsAppV2.kt')
text = app_path.read_text()


def remove_between(start_marker: str, end_marker: str, label: str) -> None:
    global text
    start = text.find(start_marker)
    if start < 0:
        return
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit(f'{label}: end marker not found')
    text = text[:start] + text[end:]


def remove_braced_block(start_marker: str, label: str) -> None:
    global text
    start = text.find(start_marker)
    if start < 0:
        return
    brace = text.find('{', start)
    if brace < 0:
        raise SystemExit(f'{label}: opening brace not found')
    depth = 0
    i = brace
    while i < len(text):
        ch = text[i]
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                end = i + 1
                while end < len(text) and text[end] in ' \t':
                    end += 1
                if end < len(text) and text[end] == '\n':
                    end += 1
                text = text[:start] + text[end:]
                return
        i += 1
    raise SystemExit(f'{label}: closing brace not found')


# Reconstruction was an experimental alpha feature and is intentionally not part of CTS 2.0.
for import_line in (
    'import io.github.retrofrost.cts.android.importer.VideoReconstructionPhase\n',
    'import io.github.retrofrost.cts.android.importer.VideoReconstructionProgress\n',
    'import io.github.retrofrost.cts.android.importer.VideoReconstructionResult\n',
    'import io.github.retrofrost.cts.android.importer.VideoReconstructionWorker\n',
):
    text = text.replace(import_line, '')

remove_between(
    '    var requestedReconstructionId by remember',
    '    var project by remember',
    'reconstruction WorkManager state',
)
remove_between(
    '    var isReconstructingVideo by remember',
    '    var pendingExportPermission by remember',
    'reconstruction UI state',
)

remove_braced_block(
    '    LaunchedEffect(Unit) {\n        VideoReconstructionWorker.peekPendingResult',
    'pending reconstruction result effect',
)
remove_braced_block(
    '    LaunchedEffect(reconstructionWork)',
    'reconstruction WorkManager effect',
)

remove_between(
    '    val backgroundPicker = rememberLauncherForActivityResult',
    '    val cardStripPicker = rememberLauncherForActivityResult',
    'model background picker',
)
remove_between(
    '    val comparisonVideoPicker = rememberLauncherForActivityResult',
    '    val soundtrackPicker = rememberLauncherForActivityResult',
    'comparison video picker',
)
remove_between(
    '    val activeVideoReconstruction = videoReconstruction',
    '    val activeCardStripReview = cardStripReview',
    'comparison reconstruction review flow',
)
remove_braced_block(
    '    if (isReconstructingVideo) {\n        VideoReconstructionProgressDialog',
    'reconstruction progress dialog',
)

app_path.write_text(text)

# Remove the unused OCR/reconstruction implementation and its dedicated tests. Card-strip
# recognition and MegaPack import remain supported.
for relative in (
    'android/app/src/main/java/io/github/retrofrost/cts/android/importer/VideoComparisonImporter.kt',
    'android/app/src/main/java/io/github/retrofrost/cts/android/importer/VideoReconstructionWorker.kt',
    'android/app/src/main/java/io/github/retrofrost/cts/android/ui/VideoReconstructionReview.kt',
    'android/app/src/test/java/io/github/retrofrost/cts/android/importer/VideoPanelAnalysisTest.kt',
    'android/app/src/test/java/io/github/retrofrost/cts/android/importer/VideoReconstructionProgressTest.kt',
):
    path = Path(relative)
    if path.exists():
        path.unlink()

final = app_path.read_text()
for forbidden in (
    'VideoReconstructionWorker',
    'VideoReconstructionProgress',
    'VideoReconstructionResult',
    'VideoReconstructionPhase',
    'comparisonVideoPicker',
    'backgroundPicker',
):
    if forbidden in final:
        raise SystemExit(f'CTS 2.0 app still references removed feature: {forbidden}')

print('Removed abandoned comparison-video reconstruction and model background picker from CTS 2.0.')
