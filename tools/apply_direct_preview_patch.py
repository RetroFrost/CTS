from pathlib import Path

path = Path("android/app/src/main/java/io/github/retrofrost/cts/android/MainActivity.kt")
s = path.read_text()

old = '''                StudioPage.PREVIEW -> PreviewPage(
                    project = project,
                    metadata = metadata,
                    metadataLoading = metadataLoading,
                    accuracy = accuracy,
                    onEditArtwork = { index ->
                        val target = project.cards.getOrNull(index)
                        if (target != null) {
                            selectedCard = index
                            transformRequestCardId = target.id
                            page = StudioPage.CARDS
                        }
                    },
                    modifier = Modifier.padding(padding),
                )'''

new = '''                StudioPage.PREVIEW -> DirectPreviewPage(
                    project = project,
                    metadata = metadata,
                    metadataLoading = metadataLoading,
                    accuracyLabel = accuracy.label,
                    accuracyDetail = accuracy.detail,
                    accuracyExact = accuracy.exact,
                    onProjectChange = ::applyProject,
                    onSelectedCardChange = { selectedCard = it },
                    modifier = Modifier.padding(padding),
                )'''

if old not in s:
    raise SystemExit("Preview wiring anchor not found; refusing to patch stale source")

s = s.replace(old, new, 1)
path.write_text(s)
