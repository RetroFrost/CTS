# Android–desktop platform parity

CTS has two native interfaces but one product contract.

## Shared changes

Edit `shared/cts_contract.json` for changes to the canonical model, fields, project version, compatibility IDs, visible-card count, timing, easing, normalized layout, colors, or starter cards.

Then run:

```bash
python tools/sync_shared_contract.py
python tools/sync_shared_contract.py --check
python -m unittest tests.test_shared_contract -v
```

Do not edit these generated files directly:

- `comparison_studio/shared_contract.py`
- `android/app/src/main/java/io/github/retrofrost/cts/android/shared/SharedContract.kt`

## Native changes

Compose and PySide6 cannot be generated from each other safely. A change that affects native interaction, rendering code, import/export behavior, or interface flow must update both implementations in the same pull request, or explicitly document why the feature is platform-specific.

Relevant parity points:

| Behavior | Desktop | Android |
| --- | --- | --- |
| Project/card model | `comparison_studio/data.py` | `model/CtsProject.kt` |
| Timeline | `reference_illustrated.py`, `easy_timing.py` | `timeline/TimelineEngine.kt` |
| Program monitor | `reference_illustrated.py` | `ui/ProgramMonitor.kt` |
| Easy workflow | `csv_text_easy.py`, `easy_ui.py` | `ui/CtsApp.kt` |
| Contract adapter | `shared_contract.py` | `shared/SharedContract.kt` |

## Pull-request rule

A pull request changing shared behavior must pass:

1. `CTS Platform Parity`
2. `CTS Android`
3. the desktop unit-test suite when desktop runtime code changes

The parity workflow catches contract drift. Reviewers should also check that native-only behavior has a corresponding implementation and test on the other platform.

## Compatibility rule

Historical model IDs are accepted for project-file compatibility, but they normalize to `illustrated_cards`, the canonical four-column Reference Timeline. Do not reintroduce separate platform-only model choices into saved projects.
