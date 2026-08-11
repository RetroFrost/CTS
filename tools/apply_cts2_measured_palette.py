import json
from pathlib import Path


def replace(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    if new in text:
        return
    if old not in text:
        raise SystemExit(f'{label}: source value not found in {path}')
    path.write_text(text.replace(old, new))


renderer = Path('android/app/src/main/java/io/github/retrofrost/cts/android/export/ReferenceFrameRenderer.kt')
monitor = Path('android/app/src/main/java/io/github/retrofrost/cts/android/ui/ProgramMonitor.kt')
badges = Path('android/app/src/main/java/io/github/retrofrost/cts/android/render/ReferenceBadgePainter.kt')
contract = Path('shared/cts_contract.json')

# Flat-band colours measured from settled frames throughout the canonical MP4s.
replace(renderer, 'Color.rgb(245, 245, 243)\n            } else Color.rgb(240, 240, 240)',
        'Color.rgb(232, 230, 226)\n            } else Color.rgb(242, 242, 242)', 'export title bands')
replace(renderer, 'Color.rgb(47, 47, 47)\n            } else Color.rgb(98, 95, 86)',
        'Color.rgb(24, 24, 24)\n            } else Color.rgb(99, 94, 87)', 'export description bands')
replace(renderer, 'paint.color = Color.rgb(234, 127, 28)',
        'paint.color = Color.rgb(192, 111, 0)', 'export Relationships rule')

replace(monitor, 'Color(0xFFF5F5F3) else Color(0xFFF0F0F0)',
        'Color(0xFFE8E6E2) else Color(0xFFF2F2F2)', 'preview title bands')
replace(monitor, 'Color(0xFF2F2F2F) else Color(0xFF625F56)',
        'Color(0xFF181818) else Color(0xFF635E57)', 'preview description bands')
replace(monitor, 'Color(0xFFEA7F1C)', 'Color(0xFFC06F00)', 'preview Relationships rule')

# Canonical badges are flat red in settled source frames; shine/streak remain separate layers.
text = badges.read_text()
old_males = '''        fill.shader = LinearGradient(
            0f, 32f, 0f, 375f,
            intArrayOf(Color.rgb(235, 9, 9), Color.rgb(224, 0, 0), Color.rgb(213, 0, 0)),
            null, Shader.TileMode.CLAMP,
        )
'''
new_males = '''        fill.shader = null
        fill.color = Color.rgb(211, 8, 9)
'''
if new_males not in text:
    if old_males not in text:
        raise SystemExit('Males badge fill block not found')
    text = text.replace(old_males, new_males, 1)
text = text.replace('fill.color = Color.rgb(224, 17, 27)', 'fill.color = Color.rgb(211, 15, 14)')
text = text.replace('stroke.color = Color.rgb(239, 194, 72)', 'stroke.color = Color.rgb(254, 186, 97)')
badges.write_text(text)

# Export title text differs between the two references; preserve that distinction.
r = renderer.read_text()
r = r.replace(
    'color = Color.rgb(16, 16, 16),\n                bold = project.model != VisualModel.Relationships,',
    'color = if (project.model == VisualModel.Relationships) Color.rgb(24, 22, 20) else Color.rgb(2, 2, 2),\n                bold = project.model != VisualModel.Relationships,',
)
renderer.write_text(r)

m = monitor.read_text()
m = m.replace(
    'color = Color(0xFF101010),\n                fontWeight = if (model == VisualModel.Relationships)',
    'color = if (model == VisualModel.Relationships) Color(0xFF181614) else Color(0xFF020202),\n                fontWeight = if (model == VisualModel.Relationships)',
)
monitor.write_text(m)

# The shared contract's canonical palette is the Males reference. Keep desktop and
# Android adapters aligned with the measured source instead of the old approximations.
spec = json.loads(contract.read_text())
spec['colors'].update({
    'title_background': '#F2F2F2',
    'title_text': '#020202',
    'description_background': '#635E57',
    'badge_top': '#D30809',
    'badge_middle': '#D30809',
    'badge_bottom': '#D30809',
    'badge_border': '#B90008',
})
contract.write_text(json.dumps(spec, indent=2, ensure_ascii=False) + '\n')

# Source-measurement invariants.
assert 'Color.rgb(232, 230, 226)' in renderer.read_text()
assert 'Color.rgb(242, 242, 242)' in renderer.read_text()
assert 'Color.rgb(24, 24, 24)' in renderer.read_text()
assert 'Color.rgb(99, 94, 87)' in renderer.read_text()
assert 'Color.rgb(211, 8, 9)' in badges.read_text()
assert 'Color.rgb(211, 15, 14)' in badges.read_text()
assert 'Color.rgb(254, 186, 97)' in badges.read_text()
assert 'Color(0xFFE8E6E2)' in monitor.read_text()
assert 'Color(0xFF181818)' in monitor.read_text()
assert 'Color(0xFFC06F00)' in monitor.read_text()
assert json.loads(contract.read_text())['colors']['title_background'] == '#F2F2F2'
print('Applied model-owned palette values measured directly from canonical CTS reference frames.')
