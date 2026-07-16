from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"Patch target not found: {label}")
    return text.replace(old, new, 1)


path = Path("android/app/src/main/java/io/github/retrofrost/cts/android/timeline/TimelineEngine.kt")
text = path.read_text()
text = replace_once(
    text,
    "import io.github.retrofrost.cts.android.model.CtsProject\n",
    "import io.github.retrofrost.cts.android.model.CtsProject\n"
    "import io.github.retrofrost.cts.android.model.VisualModel\n",
    "VisualModel import",
)
text = replace_once(
    text,
    "const val SCROLL_SECONDS = 4.4f",
    "const val SCROLL_SECONDS = 10f / 3f\n"
    "const val ILLUSTRATED_SCROLL_SECONDS = 4.4f",
    "model-specific scroll constants",
)
text = replace_once(
    text,
    '''    private fun maximumShift(project: CtsProject): Int =
        max(0, project.cards.size - project.model.visibleCards)

    fun automaticScrollDuration(project: CtsProject): Float =
        maximumShift(project) * SCROLL_SECONDS''',
    '''    private fun maximumShift(project: CtsProject): Int =
        max(0, project.cards.size - project.model.visibleCards)

    private fun scrollSecondsPerCard(project: CtsProject): Float =
        if (project.model == VisualModel.Illustrated) {
            ILLUSTRATED_SCROLL_SECONDS
        } else {
            SCROLL_SECONDS
        }

    fun automaticScrollDuration(project: CtsProject): Float =
        maximumShift(project) * scrollSecondsPerCard(project)''',
    "model-specific automatic scroll duration",
)
path.write_text(text)
