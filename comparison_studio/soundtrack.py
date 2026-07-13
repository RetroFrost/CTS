from __future__ import annotations

import json
import math
import subprocess
from pathlib import Path

from .data import AudioTrack, FriendlyError


def probe_audio_duration(ffprobe: str, path: str) -> float:
    try:
        result = subprocess.run(
            [
                ffprobe,
                "-v",
                "error",
                "-show_entries",
                "format=duration",
                "-of",
                "json",
                str(Path(path).expanduser()),
            ],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        duration = float(json.loads(result.stdout)["format"]["duration"])
        if not math.isfinite(duration) or duration <= 0:
            raise ValueError("duration is missing or zero")
        return duration
    except Exception as exc:
        raise FriendlyError(
            f"Could not read soundtrack duration: {Path(path).name}",
            "Try converting the file to WAV, MP3, M4A, FLAC, or OGG.",
            str(exc),
        ) from exc


def build_soundtrack_command(
    ffmpeg: str,
    silent_video: str,
    output_path: str,
    tracks: list[AudioTrack],
    source_durations: list[float],
    video_duration: float,
    master_volume: float,
) -> list[str]:
    """Build one FFmpeg filter graph for multiple trimmed, delayed, faded tracks."""

    if len(tracks) != len(source_durations):
        raise ValueError("Each audio track needs a probed source duration.")
    command = [ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", silent_video]
    for track in tracks:
        command.extend(["-i", str(Path(track.path).expanduser())])

    filters: list[str] = []
    labels: list[str] = []
    for index, (track, source_duration) in enumerate(zip(tracks, source_durations), start=1):
        if track.start_time >= video_duration:
            raise FriendlyError(
                f"Soundtrack track {index} starts after the video ends.",
                "Move its Start time earlier or remove the track.",
            )
        if track.trim_start >= source_duration:
            raise FriendlyError(
                f"Soundtrack track {index} Trim In is past the end of its file.",
                f"Use a value below {source_duration:.2f} seconds.",
            )
        available_on_timeline = max(0.001, video_duration - track.start_time)
        trim_end = min(source_duration, track.trim_end if track.trim_end is not None else source_duration)
        segment_duration = max(0.001, trim_end - track.trim_start)
        output_duration = available_on_timeline if track.loop else min(segment_duration, available_on_timeline)
        fade_out = min(track.fade_out, output_duration)
        fade_start = max(0.0, output_duration - fade_out)
        pieces = [
            f"[{index}:a]aresample=48000",
            f"atrim=start={track.trim_start:.6f}:end={trim_end:.6f}",
            "asetpts=PTS-STARTPTS",
        ]
        if track.loop:
            loop_samples = max(1, round(segment_duration * 48000))
            pieces.extend([f"aloop=loop=-1:size={loop_samples}", f"atrim=end={output_duration:.6f}"])
        if track.fade_in > 0:
            pieces.append(f"afade=t=in:st=0:d={min(track.fade_in, output_duration):.6f}")
        if fade_out > 0:
            pieces.append(f"afade=t=out:st={fade_start:.6f}:d={fade_out:.6f}")
        pieces.extend(
            [
                f"volume={track.volume:.6f}",
                f"adelay=delays={round(track.start_time * 1000)}:all=1[a{index - 1}]",
            ]
        )
        filters.append(",".join(pieces))
        labels.append(f"[a{index - 1}]")

    filters.append(
        f"{''.join(labels)}amix=inputs={len(labels)}:duration=longest:normalize=0,"
        f"volume={max(0.0, master_volume):.6f},alimiter=limit=0.95[mixout]"
    )
    command.extend(
        [
            "-filter_complex",
            ";".join(filters),
            "-map",
            "0:v:0",
            "-map",
            "[mixout]",
            "-c:v",
            "copy",
            "-c:a",
            "aac",
            "-b:a",
            "256k",
            "-ar",
            "48000",
            "-t",
            f"{video_duration:.6f}",
            "-movflags",
            "+faststart",
            "-progress",
            "pipe:1",
            "-nostats",
            output_path,
        ]
    )
    return command
