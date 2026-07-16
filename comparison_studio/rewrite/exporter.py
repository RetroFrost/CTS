from __future__ import annotations

import math
import os
import shutil
import subprocess
import threading
import time
from pathlib import Path

from PySide6.QtCore import QThread, Signal

from .model import Project
from .render import AssetCache, Renderer
from .timing import Timeline


class ExportError(RuntimeError):
    pass


class ExportWorker(QThread):
    stage_changed = Signal(str)
    progress_changed = Signal(int, int, float)
    completed = Signal(str)
    failed = Signal(str)
    canceled = Signal()

    def __init__(self, project: Project, output_path: str, parent=None) -> None:
        super().__init__(parent)
        self.project = project
        self.output_path = str(Path(output_path).expanduser().resolve())
        self._cancel = threading.Event()
        self._process: subprocess.Popen[bytes] | subprocess.Popen[str] | None = None

    def request_cancel(self) -> None:
        self._cancel.set()
        process = self._process
        if process is not None and process.poll() is None:
            process.terminate()

    def run(self) -> None:
        output = Path(self.output_path)
        silent = output.with_name(f"{output.stem}.video-part{output.suffix}")
        final_part = output.with_name(f"{output.stem}.part{output.suffix}")
        try:
            ffmpeg = shutil.which("ffmpeg")
            if not ffmpeg:
                raise ExportError("FFmpeg is not installed. On Ubuntu run: sudo apt install ffmpeg")

            cards = self.project.content_cards()
            if not cards:
                raise ExportError("There are no cards to export.")
            output.parent.mkdir(parents=True, exist_ok=True)
            silent.unlink(missing_ok=True)
            final_part.unlink(missing_ok=True)

            cache = AssetCache()
            missing = []
            for index, card in enumerate(cards, start=1):
                if card.image.strip() and cache.load(card.image) is None:
                    missing.append(f"Card {index}: {cache.errors.get(card.image, 'could not load image')}")
            if missing:
                raise ExportError("Some artwork could not be loaded:\n" + "\n".join(missing[:8]))

            timeline = Timeline(self.project, len(cards))
            duration = timeline.output_duration
            width, height, fps = self.project.width, self.project.height, self.project.fps
            total_frames = max(1, math.ceil(duration * fps))
            renderer = Renderer(cache)

            command = [
                ffmpeg,
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-f",
                "rawvideo",
                "-pixel_format",
                "rgb24",
                "-video_size",
                f"{width}x{height}",
                "-framerate",
                str(fps),
                "-i",
                "-",
                "-an",
                "-c:v",
                "libx264",
                "-preset",
                "medium",
                "-crf",
                "18",
                "-pix_fmt",
                "yuv420p",
                "-movflags",
                "+faststart",
                str(silent),
            ]
            self.stage_changed.emit("Rendering video frames")
            process = subprocess.Popen(
                command,
                stdin=subprocess.PIPE,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
            )
            self._process = process
            if process.stdin is None:
                raise ExportError("FFmpeg did not open its frame input.")

            started = time.monotonic()
            for frame_index in range(total_frames):
                if self._cancel.is_set():
                    process.terminate()
                    self.canceled.emit()
                    return
                image = renderer.render(self.project, frame_index / fps)
                payload = image.convert("RGB").tobytes("raw", "RGB")
                expected = width * height * 3
                if len(payload) != expected:
                    raise ExportError(
                        f"Renderer returned {len(payload):,} bytes; expected {expected:,}."
                    )
                try:
                    process.stdin.write(payload)
                except BrokenPipeError as exc:
                    error = process.stderr.read().decode("utf-8", errors="replace") if process.stderr else ""
                    raise ExportError(error or "FFmpeg stopped during rendering.") from exc

                completed = frame_index + 1
                if completed == 1 or completed == total_frames or completed % max(1, fps // 2) == 0:
                    elapsed = max(0.001, time.monotonic() - started)
                    rate = completed / elapsed
                    eta = (total_frames - completed) / rate if rate else 0.0
                    self.progress_changed.emit(completed, total_frames, eta)

            process.stdin.close()
            error = process.stderr.read().decode("utf-8", errors="replace") if process.stderr else ""
            return_code = process.wait()
            self._process = None
            if return_code != 0:
                raise ExportError(error or "FFmpeg could not finish the video.")
            if not silent.is_file() or silent.stat().st_size == 0:
                raise ExportError("FFmpeg finished without creating a usable video.")

            audio = self.project.audio.normalized()
            if audio.path:
                audio_path = Path(audio.path).expanduser()
                if not audio_path.is_file():
                    raise ExportError(f"Soundtrack not found: {audio.path}")
                self.stage_changed.emit("Adding soundtrack")
                audio_input = ["-stream_loop", "-1"] if audio.loop else []
                filters = [f"volume={audio.volume:.6f}"]
                if audio.fade_in > 0:
                    filters.append(f"afade=t=in:st=0:d={audio.fade_in:.6f}")
                if audio.fade_out > 0:
                    fade_start = max(0.0, duration - audio.fade_out)
                    filters.append(f"afade=t=out:st={fade_start:.6f}:d={audio.fade_out:.6f}")
                mix_command = [
                    ffmpeg,
                    "-hide_banner",
                    "-loglevel",
                    "error",
                    "-y",
                    "-i",
                    str(silent),
                    *audio_input,
                    "-i",
                    str(audio_path),
                    "-filter:a",
                    ",".join(filters),
                    "-t",
                    f"{duration:.6f}",
                    "-c:v",
                    "copy",
                    "-c:a",
                    "aac",
                    "-b:a",
                    "256k",
                    "-shortest",
                    str(final_part),
                ]
                result = subprocess.run(mix_command, capture_output=True, text=True)
                if result.returncode != 0:
                    raise ExportError(result.stderr.strip() or "FFmpeg could not add the soundtrack.")
                silent.unlink(missing_ok=True)
            else:
                os.replace(silent, final_part)

            os.replace(final_part, output)
            self.completed.emit(str(output))
        except Exception as exc:
            silent.unlink(missing_ok=True)
            final_part.unlink(missing_ok=True)
            self.failed.emit(str(exc))
        finally:
            self._process = None
