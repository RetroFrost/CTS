from __future__ import annotations

import sys
from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise RuntimeError(f"Expected exactly one match in {path}, found {text.count(old)}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: alpha18-compiler-fixes.py SOURCE_ROOT")
    root = Path(sys.argv[1])

    replace_once(
        root / "native/linux-gtk/main.cpp",
        '    g_signal_connect(dialog, "response", G_CALLBACK(font_dialog_response), new FontDialogContext{s, index});\n',
        '    auto* context = new FontDialogContext{s, index};\n'
        '    g_signal_connect(dialog, "response", G_CALLBACK(font_dialog_response), context);\n',
    )

    replace_once(
        root / "native/windows/main.cpp",
        'RECT preview_rect{};GetClientRect(s->preview,&preview_rect);HBITMAP next=load_scaled_bitmap(payload->image_path,std::max(1,preview_rect.right),std::max(1,preview_rect.bottom));',
        'RECT preview_rect{};GetClientRect(s->preview,&preview_rect);'
        'const int preview_width=std::max(1,static_cast<int>(preview_rect.right-preview_rect.left));'
        'const int preview_height=std::max(1,static_cast<int>(preview_rect.bottom-preview_rect.top));'
        'HBITMAP next=load_scaled_bitmap(payload->image_path,preview_width,preview_height);',
    )

    replace_once(
        root / "CMakeLists.txt",
        "target_link_libraries(CubicalCreate PRIVATE cubical_core comctl32 shell32 ole32 uuid)",
        "target_link_libraries(CubicalCreate PRIVATE cubical_core comctl32 shell32 ole32 uuid comdlg32)",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
