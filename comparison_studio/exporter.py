from __future__ import annotations

import math
import os
import shutil
import subprocess
import threading
import time
from pathlib import Path

from PySide6.QtCore import QThread, Signal

from .data import AudioTrack, CardData, FriendlyError, ProjectSettings
from .renderer import AssetCache, TimelineRenderer
from .soundtrack import build_soundtrack_command, probe_audio_duration


class ExportWorker(QThread):
    """Render and encode on a worker thread while the UI remains responsive."""

    stage_changed = Signal(str, str)
    progress_changed = Signal(int, int, float)
    completed = Signal(str)
    failed = Signal(str, str, str)
    canceled = Signal()

    def __init__(
        self,
        cards: list[CardData],
        settings: ProjectSettings,
        output_path: str,
        audio_tracks: list[AudioTrack] | None = None,
        parent=None,
    ) -> None:
        super().__init__(parent)
        self.cards = list(cards)
        self.settings = settings
        self.output_path = str(Path(output_path).expanduser().resolve())
        self.audio_tracks = list(audio_tracks or [])
        self._cancel_event = threading.Event()
        self._process: subprocess.Popen[bytes] | None = None

    def request_cancel(self) -> None:
        self._cancel_event.set()

    def run(self) -> None:  # noqa: C901 - sequential stages are intentional here
        output = Path(self.output_path)
        temporary = output.with_name(f"{output.stem}.part{output.suffix}")
        silent_temporary = output.with_name(f"{output.stem}.video-part{output.suffix}")
        try:
            if not self.cards:
                raise FriendlyError(
                    "There are no cards to export.",
                    "Paste spreadsheet data or import an .xlsx workbook first.",
                )
            ffmpeg = shutil.which("ffmpeg")
            if not ffmpeg:
                raise FriendlyError(
                    "FFmpeg is not installed.",
                    "On Ubuntu run: sudo apt install ffmpeg",
                )
            output.parent.mkdir(parents=True, exist_ok=True)
            temporary.unlink(missing_ok=True)
            silent_temporary.unlink(missing_ok=True)

            self.stage_changed.emit("Validating", "Checking spreadsheet rows and image files…")
            cache = AssetCache()
            image_errors = cache.preload(self.cards)
            if image_errors:
                preview = "\n".join(image_errors[:8])
                if len(image_errors) > 8:
                    preview += f"\n…and {len(image_errors) - 8} more."
                raise FriendlyError(
                    f"{len(image_errors)} card image(s) could not be loaded.",
                    "Fix the listed paths or URLs, then export again.",
                    preview,
                )
            for track in self.audio_tracks:
                track.validate()
            if self._cancel_event.is_set():
                self.canceled.emit()
                return

            duration = self.settings.duration(len(self.cards))
            total_frames = max(1, math.ceil(duration * self.settings.fps))
            width, height, fps = (
                self.settings.width,
                self.settings.height,
                self.settings.fps,
            )
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
                str(silent_temporary),
            ]
            self.stage_changed.emit(
                "Rendering",
                f"Creating {total_frames:,} frames at {width} × {height}…",
            )
            self._process = subprocess.Popen(
                command,
                stdin=subprocess.PIPE,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
            )
            if self._process.stdin is None:
                raise FriendlyError("FFmpeg did not open its video input stream.")

            renderer = TimelineRenderer(cache)
            started = time.monotonic()
            for frame_index in range(total_frames):
                if self._cancel_event.is_set():
                    self._abort_process()
                    temporary.unlink(missing_ok=True)
                    silent_temporary.unlink(missing_ok=True)
                    self.canceled.emit()
                    return
                frame_time = frame_index / fps
                image = renderer.render(self.cards, frame_time, self.settings)
                try:
                    self._process.stdin.write(image.tobytes())
                except BrokenPipeError as exc:
                    error_text = self._read_ffmpeg_error()
                    raise FriendlyError(
                        "FFmpeg stopped before the video was finished.",
                        "Check the error details and available disk space.",
                        error_text,
                    ) from exc
                completed = frame_index + 1
                if completed == 1 or completed == total_frames or completed % max(1, fps // 2) == 0:
                    elapsed = max(0.001, time.monotonic() - started)
                    rate = completed / elapsed
                    eta = (total_frames - completed) / rate if rate > 0 else 0.0
                    self.progress_changed.emit(completed, total_frames, eta)

            self.stage_changed.emit("Encoding", "Finishing the MP4 container…")
            self._process.stdin.close()
            error_text = self._read_ffmpeg_error()
            return_code = self._process.wait()
            self._process = None
            if return_code != 0:
                raise FriendlyError(
                    "FFmpeg could not finish the video.",
                    "Check the encoder error and try again.",
                    error_text,
                )
            if not silent_temporary.is_file() or silent_temporary.stat().st_size == 0:
                raise FriendlyError(
                    "The encoder finished without creating a usable video.",
                    "Check that the destination has enough free space.",
                )

            if self.audio_tracks:
                ffprobe = shutil.which("ffprobe")
                if not ffprobe:
                    raise FriendlyError("FFprobe is not installed.", "Install the complete FFmpeg package.")
                self.stage_changed.emit("Soundtrack", "Trimming, looping, fading, and mixing audio tracks…")
                durations = [probe_audio_duration(ffprobe, track.path) for track in self.audio_tracks]
                mix_command = build_soundtrack_command(
                    ffmpeg,
                    str(silent_temporary),
                    str(temporary),
                    self.audio_tracks,
                    durations,
                    duration,
                    self.settings.soundtrack_master_volume,
                )
                self._process = subprocess.Popen(
                    mix_command,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                )
                audio_started = time.monotonic()
                duration_units = max(1, round(duration * 1_000_000))
                while self._process.poll() is None:
                    if self._cancel_event.is_set():
                        self._abort_process()
                        temporary.unlink(missing_ok=True)
                        silent_temporary.unlink(missing_ok=True)
                        self.canceled.emit()
                        return
                    line = self._process.stdout.readline().strip() if self._process.stdout else ""
                    if line.startswith(("out_time_us=", "out_time_ms=")):
                        try:
                            completed_units = min(duration_units, max(0, int(line.split("=", 1)[1])))
                            elapsed = max(0.001, time.monotonic() - audio_started)
                            rate = completed_units / elapsed
                            eta = (duration_units - completed_units) / rate if rate > 0 else 0.0
                            self.progress_changed.emit(completed_units, duration_units, eta)
                        except ValueError:
                            pass
                self.progress_changed.emit(duration_units, duration_units, 0.0)
                error_text = self._read_ffmpeg_error()
                return_code = self._process.returncode
                self._process = None
                if return_code != 0:
                    raise FriendlyError(
                        "FFmpeg could not mix the soundtrack.",
                        "Check the audio track settings and file formats.",
                        error_text,
                    )
                silent_temporary.unlink(missing_ok=True)
            else:
                os.replace(silent_temporary, temporary)

            self.stage_changed.emit("Finalizing", "Moving the completed video into place…")
            os.replace(temporary, output)
            self.completed.emit(str(output))
        except FriendlyError as exc:
            self._abort_process()
            temporary.unlink(missing_ok=True)
            silent_temporary.unlink(missing_ok=True)
            self.failed.emit(exc.summary, exc.suggestion, exc.details)
        except Exception as exc:  # The UI still receives a readable boundary.
            self._abort_process()
            temporary.unlink(missing_ok=True)
            silent_temporary.unlink(missing_ok=True)
            self.failed.emit(
                "The export stopped unexpectedly.",
                "Your project data is safe. Try again, or inspect the technical detail below.",
                str(exc),
            )

    def _read_ffmpeg_error(self) -> str:
        if self._process is None or self._process.stderr is None:
            return ""
        try:
            content = self._process.stderr.read()
            if isinstance(content, bytes):
                return content.decode("utf-8", errors="replace").strip()
            return content.strip()
        except Exception:
            return ""

    def _abort_process(self) -> None:
        process = self._process
        self._process = None
        if process is None:
            return
        try:
            if process.stdin:
                process.stdin.close()
        except Exception:
            pass
        try:
            process.terminate()
            process.wait(timeout=2)
        except Exception:
            try:
                process.kill()
            except Exception:
                pass
