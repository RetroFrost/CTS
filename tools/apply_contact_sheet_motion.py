from __future__ import annotations

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
RENDERER = ROOT / "engine/ccengine/renderer.py"
TEST_ANIM = ROOT / "tests/test_animation_contract.py"
TEST_OUTRO = ROOT / "tests/test_males_middle_outro_regression.py"


def replace_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"{label}: expected one replacement, got {count}")
    return updated


text = RENDERER.read_text(encoding="utf-8")
text = text.replace("SHINE_START = 1.72\nSHINE_SECONDS = 0.52", "SHINE_START = 2.18\nSHINE_SECONDS = 0.72")

opening = '''# Opening badge affine states measured from dense contact sheets of the
# 1920x1080/60 Evolution Of Language reference.  The old renderer used a
# handful of exaggerated hand-tuned transforms which made the badge far too
# large through the middle of its ingress.  These keys are source-frame
# measurements; interpolation is deliberately linear between nearby measured
# states so the renderer does not introduce a second easing curve.
OPENING_BADGE_FRAME_KEYFRAMES = (
    (35, 0.493398, -0.085460, -0.331527, 1.161492, -150.997648, -39.870887),
    (40, 0.592169, -0.078765, -0.283855, 1.188568, -125.999331, -19.155194),
    (44, 0.653847, -0.078786, -0.293365, 1.172057, -105.417801, -5.568381),
    (48, 0.696013, -0.090493, -0.273790, 1.202435, -82.436779, -19.898183),
    (52, 0.721844, -0.076480, -0.273350, 1.200237, -60.473294, -18.705733),
    (56, 0.729938, -0.029255, -0.225309, 1.111702, -45.263002, -10.894799),
    (60, 0.774691, -0.031915, -0.189815, 1.114362, -38.958629, -14.476950),
    (64, 0.817901, -0.031915, -0.168210, 1.114362, -34.569740, -17.032506),
    (68, 0.859568, -0.039894, -0.121914, 1.087766, -30.989953, -17.599882),
    (72, 0.898148, -0.037234, -0.114198, 1.069149, -29.794326, -14.969267),
    (76, 0.922840, -0.026596, -0.101852, 1.053191, -28.178487, -11.698582),
    (80, 0.945988, -0.029255, -0.067901, 1.058511, -23.818558, -15.196217),
    (84, 0.964506, -0.018617, -0.064815, 1.042553, -23.258274, -10.258865),
    (88, 0.979938, -0.023936, -0.038580, 1.039894, -19.316194, -13.121158),
    (92, 0.987654, -0.021277, -0.023148, 1.029255, -16.898345, -10.625887),
    (96, 1.001543, -0.013298, -0.021605, 1.021277, -15.978132, -8.657210),
    (100, 1.010802, -0.013298, -0.006173, 1.005319, -14.144799, -6.608747),
    (104, 1.013889, -0.007979, -0.006173, 1.010638, -11.420213, -6.661939),
    (108, 1.010802, 0.002660, -0.015432, 1.015957, -8.304374, -3.548463),
    (112, 1.021605, -0.010638, 0.009259, 1.005319, -4.449173, -6.219858),
    (116, 1.024691, -0.010638, 0.020062, 0.986702, -2.171395, -1.311466),
    (120, 1.000000, 0.000000, 0.000000, 1.000000, 0.000000, 0.000000),
)

# Cards 5 onward use a constant-size hexagon.  Dense contact sheets show that
# the visible shell does not perform the old 1.12x scale/bounce animation; it
# simply drops from above on this measured vertical clock and is settled by
# source frame 734.  Frame numbers are for the first continuous card, whose
# card cycle begins at source frame 528.
POST_BADGE_FALL_FRAME_OFFSETS = (
    (650, -430.0), (670, -410.0), (679, -386.0),
    (680, -381.0), (682, -381.0), (684, -341.0), (686, -321.0),
    (688, -300.0), (690, -279.0), (692, -266.0), (694, -246.0),
    (696, -226.0), (698, -206.0), (700, -187.0), (702, -175.0),
    (704, -156.0), (706, -138.0), (708, -121.0), (710, -105.0),
    (712, -94.0), (714, -80.0), (716, -66.0), (718, -53.0),
    (720, -41.0), (722, -34.0), (724, -25.0), (726, -17.0),
    (728, -10.0), (730, -5.0), (732, -2.0), (734, 0.0),
)

# Measured body travel.'''

text = replace_once(
    text,
    r"# Opening-only affine keyframes reconstructed from the actual badge contour\..*?# Measured body travel\.",
    opening,
    "badge motion constants",
)

motion_functions = '''def _sample_affine_source_frame(
    keys: tuple[tuple[float, float, float, float, float, float, float], ...],
    source_frame: float,
) -> tuple[float, float, float, float, float, float]:
    if source_frame <= keys[0][0]:
        return keys[0][1:]
    if source_frame >= keys[-1][0]:
        return keys[-1][1:]
    for left, right in zip(keys, keys[1:]):
        if source_frame <= right[0]:
            amount = (source_frame - left[0]) / max(1e-9, right[0] - left[0])
            return tuple(lerp(left[index], right[index], amount) for index in range(1, 7))  # type: ignore[return-value]
    return keys[-1][1:]


def _sample_source_offset(keys: tuple[tuple[int, float], ...], source_frame: float) -> float:
    if source_frame <= keys[0][0]:
        return keys[0][1]
    if source_frame >= keys[-1][0]:
        return keys[-1][1]
    for left, right in zip(keys, keys[1:]):
        if source_frame <= right[0]:
            amount = (source_frame - left[0]) / max(1e-9, right[0] - left[0])
            return lerp(left[1], right[1], amount)
    return keys[-1][1]


def badge_entry_affine(age: float) -> tuple[float, float, float, float, float, float]:
    # Opening animation age 0..2.9 maps exactly to source frames 35..120.
    source_frame = 35.0 + clamp(age / BADGE_ENTRY_END) * 85.0
    return _sample_affine_source_frame(OPENING_BADGE_FRAME_KEYFRAMES, source_frame)


def post_badge_fall_affine(age: float) -> tuple[float, float, float, float, float, float]:
    # The later-badge age clock maps source frame 650 to age 0 and advances
    # 103 source frames over 2.25 age units.  Keep the shell at source scale;
    # only its measured vertical translation changes.
    source_frame = 650.0 + max(0.0, age) * (103.0 / 2.25)
    vertical = _sample_source_offset(POST_BADGE_FALL_FRAME_OFFSETS, source_frame)
    return 1.0, 0.0, 0.0, 1.0, 0.0, vertical
'''

text = replace_once(
    text,
    r"def badge_entry_affine\(age: float\).*?(?=\n\n@dataclass\(slots=True\))",
    motion_functions.rstrip(),
    "badge transform functions",
)

# Always use the source-frame transform helpers, including their settled state.
text = text.replace(
    "affine = badge_entry_affine(age) if age < BADGE_ENTRY_END else (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)",
    "affine = badge_entry_affine(age)",
)
text = text.replace(
    "affine = post_badge_fall_affine(age) if age < 2.25 else (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)",
    "affine = post_badge_fall_affine(age)",
)

outro_methods = '''    @staticmethod
    def _outro_action_bar_bounds(outro_local_frame: int) -> tuple[int, int, int, int] | None:
        # Dense two-frame contact-sheet measurements. The white action bar
        # begins as a 42x8 dash at source f11912 and reaches 540x130 at f11958.
        keys = (
            (54, 716, 98, 42, 8), (56, 696, 93, 82, 18),
            (58, 665, 85, 143, 33), (60, 632, 77, 211, 49),
            (62, 580, 64, 314, 75), (64, 563, 60, 349, 84),
            (66, 548, 56, 379, 91), (68, 536, 53, 403, 97),
            (70, 517, 49, 441, 106), (72, 510, 47, 455, 109),
            (74, 503, 45, 469, 113), (76, 498, 44, 479, 115),
            (78, 489, 42, 497, 120), (80, 485, 41, 505, 122),
            (82, 482, 40, 511, 123), (84, 479, 39, 517, 125),
            (86, 474, 38, 526, 127), (88, 473, 38, 529, 127),
            (90, 471, 37, 533, 129), (92, 471, 37, 533, 129),
            (94, 470, 37, 535, 129), (96, 468, 37, 539, 129),
            (98, 468, 37, 539, 130), (100, 468, 37, 540, 130),
            (102, 468, 37, 540, 130),
        )
        local = int(outro_local_frame)
        if local < keys[0][0]:
            return None
        if local >= keys[-1][0]:
            return keys[-1][1:]
        for left, right in zip(keys, keys[1:]):
            if local <= right[0]:
                amount = (local - left[0]) / max(1, right[0] - left[0])
                return tuple(int(round(lerp(left[i], right[i], amount))) for i in range(1, 5))  # type: ignore[return-value]
        return keys[-1][1:]

    @staticmethod
    def _outro_subscribe_bounds(outro_local_frame: int) -> tuple[int, int, int, int] | None:
        keys = (
            (74, 796, 103, 22, 7), (76, 782, 98, 52, 15),
            (78, 754, 89, 110, 32), (80, 746, 86, 128, 37),
            (82, 740, 84, 140, 40), (84, 735, 82, 150, 44),
            (86, 728, 80, 164, 48), (88, 726, 79, 169, 49),
            (90, 724, 78, 173, 51), (92, 724, 78, 173, 51),
            (94, 722, 78, 177, 51), (96, 720, 78, 182, 52),
            (98, 719, 77, 183, 53), (100, 718, 77, 185, 53),
            (102, 718, 77, 185, 53),
        )
        local = int(outro_local_frame)
        if local < keys[0][0]:
            return None
        if local >= keys[-1][0]:
            return keys[-1][1:]
        for left, right in zip(keys, keys[1:]):
            if local <= right[0]:
                amount = (local - left[0]) / max(1, right[0] - left[0])
                return tuple(int(round(lerp(left[i], right[i], amount))) for i in range(1, 5))  # type: ignore[return-value]
        return keys[-1][1:]

    @staticmethod
    def _draw_thumb_icon(draw: ImageDraw.ImageDraw, x: float, y: float, scale: float, *, down: bool = False) -> None:
        if scale <= 0.02:
            return
        s = max(0.02, float(scale))
        # Compact vector silhouette matching the source action-bar icon family.
        points = [
            (x + 8 * s, y + 15 * s), (x + 17 * s, y + 15 * s),
            (x + 24 * s, y + 4 * s), (x + 29 * s, y + 6 * s),
            (x + 28 * s, y + 15 * s), (x + 39 * s, y + 15 * s),
            (x + 42 * s, y + 20 * s), (x + 38 * s, y + 34 * s),
            (x + 17 * s, y + 34 * s), (x + 17 * s, y + 38 * s),
            (x + 8 * s, y + 38 * s),
        ]
        if down:
            cy = y + 21 * s
            points = [(px, 2 * cy - py) for px, py in points]
        draw.polygon(points, fill=(38, 38, 38, 255))

    @staticmethod
    def _draw_bell_icon(draw: ImageDraw.ImageDraw, x: float, y: float, scale: float) -> None:
        if scale <= 0.02:
            return
        s = max(0.02, float(scale))
        box = (x + 6 * s, y + 8 * s, x + 38 * s, y + 39 * s)
        draw.arc(box, start=195, end=345, fill=(48, 48, 48, 255), width=max(1, int(round(3 * s))))
        draw.line((x + 8 * s, y + 27 * s, x + 4 * s, y + 38 * s, x + 40 * s, y + 38 * s, x + 36 * s, y + 27 * s), fill=(48, 48, 48, 255), width=max(1, int(round(3 * s))), joint="curve")
        draw.ellipse((x + 19 * s, y + 40 * s, x + 25 * s, y + 46 * s), fill=(48, 48, 48, 255))

    def _draw_outro_action_bar(self, canvas: Image.Image, outro_local_frame: int) -> None:
        bounds = self._outro_action_bar_bounds(outro_local_frame)
        if bounds is None:
            return
        x, y, width, height = bounds
        layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        radius = max(2, min(24, height // 4, width // 8))
        draw.rounded_rectangle((x, y, x + width, y + height), radius=radius, fill=(236, 236, 236, 255))

        subscribe = self._outro_subscribe_bounds(outro_local_frame)
        if subscribe is not None:
            sx, sy, sw, sh = subscribe
            sr = max(1, min(8, sh // 5))
            draw.rounded_rectangle((sx, sy, sx + sw, sy + sh), radius=sr, fill=(253, 67, 69, 255))
            if sh >= 28 and sw >= 90:
                font = self._font_for_width("Subscribe", max(11, int(sh * 0.49)), max(20, sw - 16), bold=True, role="credits", minimum=9)
                draw.text((sx + sw / 2, sy + sh / 2 - 1), "Subscribe", font=font, fill=(255, 255, 255, 255), anchor="mm")

        local = int(outro_local_frame)
        like_p = smoothstep((local - 86) / 26.0)
        dislike_p = smoothstep((local - 92) / 26.0)
        bell_p = smoothstep((local - 88) / 16.0)
        line_p = smoothstep((local - 102) / 18.0)

        self._draw_thumb_icon(draw, 516, 58, like_p)
        self._draw_thumb_icon(draw, 607, 58, dislike_p, down=True)
        self._draw_bell_icon(draw, 925, 61, bell_p)
        if line_p > 0.0:
            draw.line((508, 138, lerp(508, 684, line_p), 138), fill=(32, 32, 32, 255), width=4)

        canvas.paste(layer.convert("RGB"), (0, 0), layer.getchannel("A"))

'''

text = text.replace(
    "    @staticmethod\n    def _age_cover_y(outro_local_frame: int) -> int:",
    outro_methods + "    @staticmethod\n    def _age_cover_y(outro_local_frame: int) -> int:",
    1,
)

text = text.replace(
    "            if end_group_top is not None:\n                self._draw_end_group(base, end_group_top, project)\n\n            if segment.kind == \"fade\":",
    "            if end_group_top is not None:\n                self._draw_end_group(base, end_group_top, project)\n            self._draw_outro_action_bar(base, outro_local)\n\n            if segment.kind == \"fade\":",
    1,
)

RENDERER.write_text(text, encoding="utf-8")

# Replace sparse self-referential samples with source-frame checks from the
# contact sheets used to rebuild the curves.
test = TEST_ANIM.read_text(encoding="utf-8")
test = replace_once(
    test,
    r"def test_opening_badge_transform_is_unchanged\(\) -> None:.*?(?=\n\ndef test_continuous_badge_fall_is_unchanged)",
    '''def test_opening_badge_transform_matches_contact_sheet_frames() -> None:\n    def age(frame: int) -> float:\n        return (frame - 35) * 2.9 / 85.0\n\n    expected = {\n        40: (0.592169, -0.078765, -0.283855, 1.188568, -125.999331, -19.155194),\n        60: (0.774691, -0.031915, -0.189815, 1.114362, -38.958629, -14.476950),\n        80: (0.945988, -0.029255, -0.067901, 1.058511, -23.818558, -15.196217),\n        100: (1.010802, -0.013298, -0.006173, 1.005319, -14.144799, -6.608747),\n        120: (1.0, 0.0, 0.0, 1.0, 0.0, 0.0),\n    }\n    for frame, values in expected.items():\n        assert badge_entry_affine(age(frame)) == pytest.approx(values, abs=1e-6)\n'''.rstrip(),
    "opening badge test",
)
test = replace_once(
    test,
    r"def test_continuous_badge_fall_is_unchanged\(\) -> None:.*\Z",
    '''def test_continuous_badge_fall_matches_contact_sheet_frames() -> None:\n    def age(frame: int) -> float:\n        return (frame - 650) * 2.25 / 103.0\n\n    expected = {\n        680: (1.0, 0.0, 0.0, 1.0, 0.0, -381.0),\n        700: (1.0, 0.0, 0.0, 1.0, 0.0, -187.0),\n        720: (1.0, 0.0, 0.0, 1.0, 0.0, -41.0),\n        734: (1.0, 0.0, 0.0, 1.0, 0.0, 0.0),\n    }\n    for frame, values in expected.items():\n        assert post_badge_fall_affine(age(frame)) == pytest.approx(values, abs=1e-6)\n'''.rstrip() + "\n",
    "later badge test",
)
TEST_ANIM.write_text(test, encoding="utf-8")

outro = TEST_OUTRO.read_text(encoding="utf-8")
addition = '''\n\ndef test_outro_action_bar_uses_measured_contact_sheet_bounds() -> None:\n    renderer = FrameRenderer()\n    assert renderer._outro_action_bar_bounds(53) is None\n    assert renderer._outro_action_bar_bounds(54) == (716, 98, 42, 8)\n    assert renderer._outro_action_bar_bounds(62) == (580, 64, 314, 75)\n    assert renderer._outro_action_bar_bounds(78) == (489, 42, 497, 120)\n    assert renderer._outro_action_bar_bounds(100) == (468, 37, 540, 130)\n    assert renderer._outro_action_bar_bounds(140) == (468, 37, 540, 130)\n'''
if "test_outro_action_bar_uses_measured_contact_sheet_bounds" not in outro:
    outro += addition
TEST_OUTRO.write_text(outro, encoding="utf-8")

print("Applied dense contact-sheet motion fidelity patch")
