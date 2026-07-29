from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "source")
path = root / "native" / "windows" / "main.cpp"
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
if old not in text:
    raise SystemExit("The expected Alpha 21 wheel-crop block was not found.")
path.write_text(text.replace(old, new), encoding="utf-8")
print(f"Patched MSVC-safe wheel-crop calculation in {path}")
