from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
from urllib.request import urlopen


FONT_FILES = {
    "Poppins-ExtraBold.ttf": (
        "https://raw.githubusercontent.com/google/fonts/main/ofl/poppins/Poppins-ExtraBold.ttf",
        "167667d203d98f5b27c3ff58d486eea9c5287fe4",
    ),
    "Poppins-Bold.ttf": (
        "https://raw.githubusercontent.com/google/fonts/main/ofl/poppins/Poppins-Bold.ttf",
        "1982f38ab21303459aa1155265052ca599fa58d1",
    ),
    "Poppins-SemiBold.ttf": (
        "https://raw.githubusercontent.com/google/fonts/main/ofl/poppins/Poppins-SemiBold.ttf",
        "c30ad104723a0e6e00e54768626cb02c5fdf6aee",
    ),
    "Poppins-Medium.ttf": (
        "https://raw.githubusercontent.com/google/fonts/main/ofl/poppins/Poppins-Medium.ttf",
        "a590f5c3e4902a7cb10f4bbc5da0e65e667f7950",
    ),
}


def git_blob_sha1(payload: bytes) -> str:
    return hashlib.sha1(f"blob {len(payload)}\0".encode("ascii") + payload).hexdigest()


def fetch_one(destination: Path, url: str, expected_blob: str) -> None:
    if destination.is_file() and git_blob_sha1(destination.read_bytes()) == expected_blob:
        return
    with urlopen(url, timeout=30) as response:
        payload = response.read()
    actual = git_blob_sha1(payload)
    if actual != expected_blob:
        raise RuntimeError(
            f"Official font verification failed for {destination.name}: expected {expected_blob}, got {actual}"
        )
    destination.write_bytes(payload)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--dest",
        action="append",
        default=[],
        help="Font destination directory. May be supplied more than once.",
    )
    args = parser.parse_args()
    destinations = [Path(value) for value in args.dest] or [Path("engine/ccengine/fonts")]
    for root in destinations:
        root.mkdir(parents=True, exist_ok=True)
        if not (root / "OFL.txt").is_file():
            raise RuntimeError(f"Poppins OFL.txt is missing from {root}")
        for filename, (url, expected_blob) in FONT_FILES.items():
            fetch_one(root / filename, url, expected_blob)
            print(f"verified {root / filename} {expected_blob}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
