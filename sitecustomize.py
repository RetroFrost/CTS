"""Final Windows build compatibility patch, loaded automatically by Python."""
from pathlib import Path


def _patch_msvc_wheel_crop() -> None:
    path = Path.cwd() / "source" / "native" / "windows" / "main.cpp"
    if not path.is_file():
        return
    text = path.read_text(encoding="utf-8")
    old = """                const int distances[] = {point.x, point.y, std::max(0, rect.right-point.x), std::max(0, rect.bottom-point.y)};
                const int edge = static_cast<int>(std::min_element(std::begin(distances), std::end(distances)) - std::begin(distances));"""
    new = """                const int distances[] = {
                    point.x,
                    point.y,
                    rect.right > point.x ? rect.right - point.x : 0,
                    rect.bottom > point.y ? rect.bottom - point.y : 0,
                };
                const int edge = static_cast<int>(std::min_element(distances, distances + 4) - distances);"""
    if old in text:
        path.write_text(text.replace(old, new), encoding="utf-8")
        print(f"Applied MSVC-safe wheel-crop patch to {path}")


_patch_msvc_wheel_crop()
