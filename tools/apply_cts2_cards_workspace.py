from pathlib import Path

app = Path('android/app/src/main/java/io/github/retrofrost/cts/android/ui/CtsAppV2.kt')
cards = Path('android/app/src/main/java/io/github/retrofrost/cts/android/ui/CardsWorkspace2.kt')
text = app.read_text()

start_marker = '                    WorkspaceSection.Data -> DataWorkspace('
end_marker = '                    WorkspaceSection.Audio -> AudioWorkspace('
replacement = '''                    WorkspaceSection.Data -> CardsWorkspace2(
                        project = project,
                        selectedCardId = selectedCardId,
                        onSelectCard = ::selectCard,
                        onProjectChanged = ::applyProject,
                        onUpdateSelectedCard = ::updateSelectedCard,
                        onChooseImage = { imagePicker.launch(arrayOf("image/*")) },
                        onChooseBackground = { backgroundPicker.launch(arrayOf("image/*")) },
                        onImportCardStrip = { cardStripPicker.launch(arrayOf("image/*")) },
                        isImportingCardStrip = isImportingCardStrip,
                        onImportMegaPack = {
                            megaPackPicker.launch(
                                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
                            )
                        },
                        isImportingMegaPack = isImportingMegaPack,
                        onInsertData = { showInsertDialog = true },
                    )
'''

if 'WorkspaceSection.Data -> CardsWorkspace2(' not in text:
    start = text.find(start_marker)
    end = text.find(end_marker, start)
    if start < 0 or end < 0:
        raise SystemExit('Could not locate current Cards workspace call')
    text = text[:start] + replacement + text[end:]
    app.write_text(text)

integrated = app.read_text()
card_ui = cards.read_text()
assert 'WorkspaceSection.Data -> CardsWorkspace2(' in integrated
assert 'WorkspaceSection.Data -> DataWorkspace(' not in integrated
assert 'colors = titleFieldColors2()' in card_ui
assert 'focusedContainerColor = Color.White' in card_ui
assert 'focusedTextColor = Color.Black' in card_ui
assert 'VisualModel.entries.forEach' in card_ui
assert 'Compact' not in card_ui
assert 'Gradient Bars' not in card_ui
print('CTS 2.0 Cards workspace integrated; title contrast fix verified.')
