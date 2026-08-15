from pathlib import Path

renderer_path = Path("engine/ccengine/renderer.py")
renderer = renderer_path.read_text(encoding="utf-8")
old = '''    @staticmethod
    def _value_lines(value: str) -> list[str]:
        words = value.upper().split()
        if not words:
            return []
        if len(words) == 1:
            return words
        if len(words) == 2:
            return words
        if words[-1] == "OLD":
            middle = " ".join(words[1:-1])
            return [words[0], middle, words[-1]] if middle else [words[0], words[-1]]
        return [words[0], " ".join(words[1:-1]), words[-1]]
'''
new = '''    @staticmethod
    def _value_lines(value: str) -> list[str]:
        words = value.upper().split()
        if not words:
            return []
        if len(words) == 1:
            return words
        # The dense Evolution Of Language contact sheets show the current
        # template consistently keeps the numeric/time token on the first
        # line and the complete qualifier on one second line: e.g.
        # ``7M`` + ``YEARS AGO`` and ``300K`` + ``YEARS AGO``. Splitting
        # three-word values over three lines changes both the layout and the
        # perceived text-entry motion, so preserve the source's two-line form.
        return [words[0], " ".join(words[1:])]
'''
if old not in renderer:
    raise SystemExit("renderer _value_lines block no longer matches expected source")
renderer_path.write_text(renderer.replace(old, new, 1), encoding="utf-8")

test_path = Path("tests/test_animation_contract.py")
test = test_path.read_text(encoding="utf-8")
test = test.replace(
    "from ccengine.renderer import badge_entry_affine, body_progress, post_badge_fall_affine",
    "from ccengine.renderer import FrameRenderer, badge_entry_affine, body_progress, post_badge_fall_affine",
    1,
)
marker = "def test_badge_value_layout_matches_new_reference()"
if marker not in test:
    test += '''\n\ndef test_badge_value_layout_matches_new_reference() -> None:\n    assert FrameRenderer._value_lines("7M YEARS AGO") == ["7M", "YEARS AGO"]\n    assert FrameRenderer._value_lines("300K YEARS AGO") == ["300K", "YEARS AGO"]\n    assert FrameRenderer._value_lines("7 YEARS OLD") == ["7", "YEARS OLD"]\n    assert FrameRenderer._value_lines("8000 BC") == ["8000", "BC"]\n'''
test_path.write_text(test, encoding="utf-8")
