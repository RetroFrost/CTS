from pathlib import Path

path = Path('android/app/src/main/java/io/github/retrofrost/cts/android/ui/CtsAppV2.kt')
text = path.read_text()


def replace_once(old: str, new: str, label: str) -> None:
    global text
    if new in text:
        return
    if old not in text:
        raise SystemExit(f'{label}: expected source block not found')
    text = text.replace(old, new, 1)


def remove_between(start_marker: str, end_marker: str, label: str) -> None:
    global text
    start = text.find(start_marker)
    if start < 0:
        return
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit(f'{label}: end marker not found')
    text = text[:start] + text[end:]


# Remove the old Cards implementation completely. CTS 2.0 uses CardsWorkspace2 only;
# keeping an unreachable model-tweaking screen around is a regression risk.
remove_between(
    '@Composable\nprivate fun DataWorkspace(',
    '@Composable\nprivate fun readableOutlinedTextFieldColors()',
    'legacy Cards workspace',
)

# Exact reference timing is part of each model. There is no app-level duration editor.
text = text.replace('    var showLengthDialog by remember { mutableStateOf(false) }\n', '')
text = text.replace('                        onSetLength = { showLengthDialog = true },\n', '')
remove_between(
    '    if (showLengthDialog) {\n        VideoLengthDialog(',
    '    if (megaPackWarnings.isNotEmpty()) {',
    'custom video length dialog call',
)

# Present custom video as an app-level pre-roll; it never replaces the model intro.
text = text.replace('    Audio("Sound & intro"),', '    Audio("Sound & pre-roll"),')
text = text.replace(
    'Text("Intro and sound", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)',
    'Text("Sound & pre-roll", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)',
)
text = text.replace(
    '"Add an optional MP4 intro, edit the credits, and choose music for the finished video."',
    '"Add an optional pre-roll, edit text content, and choose music. The reference model itself stays untouched."',
)
text = text.replace('Text("Intro video", fontWeight = FontWeight.Black)', 'Text("Optional pre-roll", fontWeight = FontWeight.Black)')
text = text.replace(
    'project.introVideo.displayName.ifBlank { "Use the built-in intro, or choose any MP4." }',
    'project.introVideo.displayName.ifBlank { "Choose an MP4 to play before the model starts." }',
)
reference_toggle = '''                    ReferenceOption("Include intro", project.showIntro) {
                        onProjectChanged(project.copy(showIntro = it))
                    }
'''
text = text.replace(reference_toggle, '')
text = text.replace(
    '"${TimelineEngine.formatTime(project.introVideo.durationSeconds)} · fitted to 16:9 without stretching"',
    '"${TimelineEngine.formatTime(project.introVideo.durationSeconds)} · plays before the fixed reference intro"',
)
text = text.replace('                    showIntro = true,\n', '')
text = text.replace('message("Custom MP4 intro ready")', 'message("Pre-roll ready")')
text = text.replace('"Could not use that MP4 as the intro"', '"Could not use that MP4 as the pre-roll"')
text = text.replace(
    'project.introVideo.uri != null && project.showIntro ->\n                                "Original intro audio is kept when available"',
    'project.introVideo.uri != null ->\n                                "Pre-roll audio is kept when available"',
)

# Export screen: model timing/format is informative, not editable.
text = text.replace('    onSetLength: () -> Unit,\n', '')
text = text.replace(
    '"Choose the length, check the summary, then export. You can leave CTS while it finishes."',
    '"Review the reference output, then export. You can leave CTS while encoding continues."',
)
remove_between(
    '''        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Video length", fontWeight = FontWeight.Black)
''',
    '''        if (project.modelMode == ModelMode.ExactReference) {
''',
    'export video length card',
)

format_start = '        if (project.modelMode == ModelMode.ExactReference) {\n'
format_end = '        item {\n            OutlinedButton(\n                onClick = { showAdvanced = !showAdvanced },'
start = text.find(format_start)
end = text.find(format_end, start) if start >= 0 else -1
if start >= 0:
    if end < 0:
        raise SystemExit('reference format branch: end marker not found')
    fixed = '''        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Reference output", fontWeight = FontWeight.Black)
                    Text("${project.model.label} · fixed model timing")
                    Text(
                        "1920×1080 · 60 fps · model colors, geometry and animation locked",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
'''
    text = text[:start] + fixed + text[end:]

# The model pre-roll is always enabled by the sealed project contract if supplied.
text = text.replace('if (project.introVideo.uri != null && project.showIntro) {', 'if (project.introVideo.uri != null) {')

path.write_text(text)

final = path.read_text()
for forbidden in (
    'onSetLength',
    'Text("Video length"',
    'ReferenceOption("Include intro"',
    'WorkspaceSection.Data -> DataWorkspace(',
    'private fun DataWorkspace(',
    '"Editable timing"',
    'Audio("Sound & intro")',
    'Custom MP4 intro ready',
    'as the intro',
    'introVideo.uri != null && project.showIntro',
):
    if forbidden in final:
        raise SystemExit(f'CTS 2.0 shell still exposes stale/override UI: {forbidden}')

if 'WorkspaceSection.Data -> CardsWorkspace2(' not in final:
    raise SystemExit('CTS 2.0 Cards workspace is not wired')

print('Polished CTS 2.0 shell: sealed model timing/format, pre-roll semantics, no legacy Cards UI.')
