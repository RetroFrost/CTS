#!/usr/bin/env python3
"""Create a short H.264/AAC file through the same soundtrack filter builder."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from comparison_studio.data import AudioTrack
from comparison_studio.soundtrack import build_soundtrack_command, probe_audio_duration


def run(command: list[str]) -> None:
    subprocess.run(command, check=True)


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    output_dir = root / "qa"
    output_dir.mkdir(exist_ok=True)
    silent = output_dir / "soundtrack-silent.mp4"
    tone_one = output_dir / "tone-one.wav"
    tone_two = output_dir / "tone-two.wav"
    final = output_dir / "soundtrack_smoke_test.mp4"
    run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "color=c=#111827:s=640x360:r=30:d=3", "-an", "-c:v", "libx264", "-pix_fmt", "yuv420p", str(silent)])
    run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=1", str(tone_one)])
    run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=660:duration=2", str(tone_two)])
    tracks = [
        AudioTrack(str(tone_one), volume=0.35, fade_in=0.1, fade_out=0.2, loop=True),
        AudioTrack(str(tone_two), start_time=0.5, trim_start=0.25, volume=0.2, fade_in=0.2),
    ]
    durations = [probe_audio_duration("ffprobe", track.path) for track in tracks]
    run(build_soundtrack_command("ffmpeg", str(silent), str(final), tracks, durations, 3.0, 0.8))
    probe = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name", "-of", "csv=p=0", str(final)],
        check=True,
        stdout=subprocess.PIPE,
        text=True,
    ).stdout
    if "video,h264" not in probe and "h264,video" not in probe:
        raise SystemExit(f"H.264 stream missing: {probe}")
    if "audio,aac" not in probe and "aac,audio" not in probe:
        raise SystemExit(f"AAC stream missing: {probe}")
    silent.unlink(missing_ok=True)
    tone_one.unlink(missing_ok=True)
    tone_two.unlink(missing_ok=True)
    print(final)


if __name__ == "__main__":
    main()
