#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from typing import BinaryIO


ANALYSIS_WIDTH = 96
ANALYSIS_HEIGHT = 54


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def probe(path: Path) -> dict[str, object]:
    command = [
        "ffprobe",
        "-v",
        "error",
        "-select_streams",
        "v:0",
        "-show_entries",
        "stream=codec_name,width,height,r_frame_rate,avg_frame_rate,nb_frames",
        "-show_entries",
        "format=duration,size",
        "-of",
        "json",
        str(path),
    ]
    try:
        return json.loads(subprocess.check_output(command, text=True))
    except FileNotFoundError as exc:
        raise RuntimeError("ffprobe is required to analyze reference videos") from exc


def parse_rate(value: str) -> tuple[int, int]:
    numerator, denominator = value.split("/", 1)
    denominator_value = int(denominator)
    if denominator_value == 0:
        raise ValueError(f"Invalid frame rate: {value}")
    return int(numerator), denominator_value


def frame_stream(path: Path, stride: int) -> tuple[subprocess.Popen[bytes], BinaryIO]:
    select = f"select=not(mod(n\\,{max(1, stride)})),scale={ANALYSIS_WIDTH}:{ANALYSIS_HEIGHT},format=gray"
    command = [
        "ffmpeg",
        "-v",
        "error",
        "-i",
        str(path),
        "-an",
        "-vf",
        select,
        "-vsync",
        "vfr",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "gray",
        "pipe:1",
    ]
    try:
        process = subprocess.Popen(command, stdout=subprocess.PIPE)
    except FileNotFoundError as exc:
        raise RuntimeError("ffmpeg is required to analyze reference videos") from exc
    if process.stdout is None:
        process.kill()
        raise RuntimeError("Could not open FFmpeg analysis stream")
    return process, process.stdout


def analyze_frames(path: Path, frame_count: int, stride: int) -> dict[str, object]:
    process, stream = frame_stream(path, stride)
    frame_size = ANALYSIS_WIDTH * ANALYSIS_HEIGHT
    previous: bytes | None = None
    sampled: list[dict[str, object]] = []
    sample_index = 0

    while True:
        payload = stream.read(frame_size)
        if not payload:
            break
        if len(payload) != frame_size:
            process.kill()
            raise RuntimeError("FFmpeg returned a partial analysis frame")

        luma_sum = sum(payload)
        average_luma = luma_sum / frame_size
        difference = 0.0
        if previous is not None:
            difference = sum(abs(left - right) for left, right in zip(payload, previous)) / frame_size

        source_frame = min(sample_index * stride, max(0, frame_count - 1))
        sampled.append(
            {
                "frame": source_frame,
                "average_luma": round(average_luma, 6),
                "difference_from_previous_sample": round(difference, 6),
                "sha256_gray_96x54": hashlib.sha256(payload).hexdigest(),
            }
        )
        previous = payload
        sample_index += 1

    return_code = process.wait()
    if return_code != 0:
        raise RuntimeError(f"FFmpeg analysis exited with code {return_code}")

    strongest = sorted(
        sampled[1:],
        key=lambda item: float(item["difference_from_previous_sample"]),
        reverse=True,
    )[:64]
    strongest.sort(key=lambda item: int(item["frame"]))
    return {
        "analysis_size": [ANALYSIS_WIDTH, ANALYSIS_HEIGHT],
        "sample_stride_frames": stride,
        "sample_count": len(sampled),
        "samples": sampled,
        "strongest_changes": strongest,
    }


def detect_first_left_content(path: Path, fps_numerator: int, fps_denominator: int) -> int:
    """Detect the first frame whose left quarter departs from its opening baseline.

    This is a measurement aid, not a replacement for visual confirmation. It is
    deterministic for a fixed reference video and deliberately analyzes every
    source frame at a small resolution.
    """
    process, stream = frame_stream(path, 1)
    frame_size = ANALYSIS_WIDTH * ANALYSIS_HEIGHT
    left_width = ANALYSIS_WIDTH // 4
    baseline_values: list[float] = []
    frame = 0
    consecutive = 0
    first_candidate = 0
    detected = 0

    while True:
        payload = stream.read(frame_size)
        if not payload:
            break
        if len(payload) != frame_size:
            process.kill()
            raise RuntimeError("FFmpeg returned a partial detection frame")

        left_sum = 0
        for row in range(ANALYSIS_HEIGHT):
            start = row * ANALYSIS_WIDTH
            left_sum += sum(payload[start : start + left_width])
        average = left_sum / (left_width * ANALYSIS_HEIGHT)

        # First two seconds establish the logo/black baseline.
        baseline_frame_count = max(1, (fps_numerator * 2) // fps_denominator)
        if frame < baseline_frame_count:
            baseline_values.append(average)
        else:
            ordered = sorted(baseline_values)
            baseline = ordered[len(ordered) // 2]
            changed = abs(average - baseline) >= 0.45
            if changed:
                if consecutive == 0:
                    first_candidate = frame
                consecutive += 1
                if consecutive >= 4:
                    detected = first_candidate
                    break
            else:
                consecutive = 0
        frame += 1

    process.terminate()
    process.wait()
    return detected


def build_report(path: Path, model_id: str, stride: int, detect_first_content: bool) -> dict[str, object]:
    metadata = probe(path)
    streams = metadata.get("streams", [])
    if not streams:
        raise RuntimeError("No video stream was found")
    stream = streams[0]
    fps_numerator, fps_denominator = parse_rate(str(stream["r_frame_rate"]))
    frame_count = int(stream.get("nb_frames") or 0)
    if frame_count <= 0:
        raise RuntimeError("The reference must expose an exact frame count")

    report: dict[str, object] = {
        "schema": 1,
        "model_id": model_id,
        "source": {
            "filename": path.name,
            "size_bytes": path.stat().st_size,
            "sha256": sha256_file(path),
            "codec": stream.get("codec_name"),
            "width": int(stream["width"]),
            "height": int(stream["height"]),
            "fps_numerator": fps_numerator,
            "fps_denominator": fps_denominator,
            "frame_count": frame_count,
            "visual_duration_seconds": frame_count * fps_denominator / fps_numerator,
            "container_duration_seconds": float(metadata.get("format", {}).get("duration", 0.0)),
        },
        "fingerprint": analyze_frames(path, frame_count, stride),
    }
    if detect_first_content:
        report["detected_first_left_content_frame"] = detect_first_left_content(
            path, fps_numerator, fps_denominator
        )
    return report


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        description="Create a deterministic frame-level fingerprint for a Cubical Compare reference video."
    )
    result.add_argument("video", type=Path)
    result.add_argument("--model-id", required=True)
    result.add_argument("--output", type=Path, required=True)
    result.add_argument("--stride", type=int, default=30, help="Analyze one frame per N source frames")
    result.add_argument("--detect-first-content", action="store_true")
    result.add_argument("--expect-sha256", default="")
    return result


def main() -> int:
    args = parser().parse_args()
    video = args.video.expanduser().resolve()
    if not video.is_file():
        raise FileNotFoundError(video)
    report = build_report(video, args.model_id, max(1, args.stride), args.detect_first_content)
    actual_hash = str(report["source"]["sha256"])
    if args.expect_sha256 and actual_hash.lower() != args.expect_sha256.lower():
        raise RuntimeError(
            f"Reference hash mismatch: expected {args.expect_sha256}, got {actual_hash}"
        )
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(output)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(2)
