#!/usr/bin/env python3
"""Patch Cubical Create Alpha 20 so opening the editor does not launch the renderer."""

from __future__ import annotations

import sys
from pathlib import Path


def insert_after_function(text: str, signature: str, addition: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise RuntimeError(f"Could not find function: {signature}")
    brace = text.find("{", start + len(signature))
    if brace < 0:
        raise RuntimeError(f"Could not find opening brace for: {signature}")

    depth = 0
    for index in range(brace, len(text)):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[: index + 1] + addition + text[index + 1 :]
    raise RuntimeError(f"Could not find closing brace for: {signature}")


def patch_windows(root: Path) -> None:
    path = root / "native" / "windows" / "main.cpp"
    text = path.read_text(encoding="utf-8")

    idle_function = """

void initialize_player_idle(AppState* s) {
    s->playing = false;
    s->preview_pending = false;
    s->duration = cubical::timeline_duration(s->project);
    s->current_time = 0.0;
    update_player_ui(s);
    set_status(s, "Ready - press Play to render.");
}
"""
    if "void initialize_player_idle(AppState* s)" not in text:
        text = insert_after_function(text, "void reset_player(AppState* s)", idle_function)

    startup = "load_project(s);reset_player(s);SetTimer(hwnd,PLAYER_TIMER,100,nullptr);"
    replacement = "load_project(s);initialize_player_idle(s);SetTimer(hwnd,PLAYER_TIMER,100,nullptr);"
    count = text.count(startup)
    if count != 1:
        raise RuntimeError(f"Expected one Windows startup reset call, found {count}.")
    text = text.replace(startup, replacement, 1)

    if "load_project(s);reset_player(s);SetTimer" in text:
        raise RuntimeError("Windows startup still launches reset_player().")
    path.write_text(text, encoding="utf-8")


def patch_linux(root: Path) -> None:
    path = root / "native" / "linux-gtk" / "main.cpp"
    text = path.read_text(encoding="utf-8")

    idle_function = """

void initialize_player_idle(AppState* s) {
    s->playing = false;
    s->preview_pending = false;
    s->duration = cubical::timeline_duration(s->project);
    s->current_time = 0.0;
    update_player_ui(s);
    set_status(s, "Ready - press Play to render.");
}
"""
    if "void initialize_player_idle(AppState* s)" not in text:
        text = insert_after_function(text, "void reset_player(AppState* s)", idle_function)

    startup = "reset_player(static_cast<AppState*>(data));"
    replacement = "initialize_player_idle(static_cast<AppState*>(data));"
    count = text.count(startup)
    if count != 1:
        raise RuntimeError(f"Expected one Linux startup reset call, found {count}.")
    text = text.replace(startup, replacement, 1)

    if "reset_player(static_cast<AppState*>(data));" in text:
        raise RuntimeError("Linux startup still launches reset_player().")
    path.write_text(text, encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: alpha20-idle-startup-fix.py SOURCE_DIRECTORY", file=sys.stderr)
        return 2
    root = Path(sys.argv[1]).resolve()
    patch_windows(root)
    patch_linux(root)
    print("Patched Cubical Create to start idle without launching the renderer.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
