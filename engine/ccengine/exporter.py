from __future__ import annotations

from concurrent.futures import Future, ThreadPoolExecutor
from pathlib import Path
from typing import Callable
import math
import os
import shutil
import subprocess
import sys
import threading
import time

from PIL import Image

from .models import Project
from .renderer import FrameRenderer
from .timing import locate_segment, total_duration
from .validation import normalize_project, validate_encoder_preset


ProgressCallback = Callable[[int, int], None]
CancelCheck = Callable[[], bool]


class ExportCancelled(RuntimeError):
    pass


class VideoExporter:
    """Render frames with a bounded parallel pipeline and atomically publish MP4."""

    def __init__(self, renderer: FrameRenderer | None = None) -> None:
        self.renderer = renderer or FrameRenderer()
        self._custom_renderer = renderer is not None
        self.cancelled = False
        self._process: subprocess.Popen[bytes] | None = None

    def cancel(self) -> None:
        self.cancelled = True
        process = self._process
        if process is not None and process.poll() is None:
            try:
                process.terminate()
            except OSError:
                pass

    @staticmethod
    def ffmpeg_path() -> str:
        configured = os.environ.get("CUBICAL_COMPARE_FFMPEG", "").strip()
        if configured:
            candidate = Path(configured).expanduser()
            if not candidate.is_file():
                raise RuntimeError(
                    "CUBICAL_COMPARE_FFMPEG points to a missing file: "
                    f"{candidate}"
                )
            return str(candidate.resolve())

        executable = "ffmpeg.exe" if os.name == "nt" else "ffmpeg"
        candidates: list[Path] = []
        if getattr(sys, "frozen", False):
            candidates.append(Path(sys.executable).resolve().parent / executable)
        bundle_root = getattr(sys, "_MEIPASS", "")
        if bundle_root:
            candidates.append(Path(bundle_root) / executable)
        candidates.append(Path(__file__).resolve().parent.parent / executable)
        for candidate in candidates:
            if candidate.is_file():
                return str(candidate.resolve())
        found = shutil.which(executable)
        if not found:
            raise RuntimeError(
                "FFmpeg was not found. Install FFmpeg, set CUBICAL_COMPARE_FFMPEG, "
                "or place FFmpeg inside the private Cubical Compare engine bundle."
            )
        return found

    @staticmethod
    def _audio_filter(project: Project, duration: float) -> str:
        settings = project.settings
        filters = [f"volume={max(0.0, min(1.0, settings.soundtrack_volume)):.6f}"]
        if not settings.soundtrack_loop:
            filters.append(f"apad=pad_dur={duration:.6f}")
        filters.append(f"atrim=duration={duration:.6f}")
        fade = max(0.0, min(float(settings.soundtrack_fade_out_seconds), duration))
        if fade > 0.0:
            filters.append(f"afade=t=out:st={max(0.0, duration - fade):.6f}:d={fade:.6f}")
        filters.append("asetpts=N/SR/TB")
        return ",".join(filters)

    @staticmethod
    def _frame_bytes(frame: Image.Image, width: int, height: int) -> bytes:
        if frame.size != (width, height):
            raise RuntimeError(
                f"Renderer returned {frame.width}x{frame.height}; expected {width}x{height}."
            )
        rgb = frame if frame.mode == "RGB" else frame.convert("RGB")
        payload = rgb.tobytes("raw", "RGB")
        expected = width * height * 3
        if len(payload) != expected:
            raise RuntimeError(
                f"Renderer returned {len(payload)} frame bytes; expected {expected}."
            )
        return payload

    @staticmethod
    def _verify_mp4(path: Path) -> None:
        if not path.is_file():
            raise RuntimeError("FFmpeg exited without creating the MP4 file.")
        size = path.stat().st_size
        if size < 256:
            raise RuntimeError(f"FFmpeg created an unusable MP4 ({size} bytes).")
        with path.open("rb") as handle:
            header = handle.read(64)
        if b"ftyp" not in header:
            raise RuntimeError("The exported file does not contain a valid MP4 header.")

    @staticmethod
    def _write_payload(
        process: subprocess.Popen[bytes], payload: bytes, cancel_check: CancelCheck | None
    ) -> None:
        assert process.stdin is not None
        view = memoryview(payload)
        chunk_size = 1024 * 1024
        for offset in range(0, len(view), chunk_size):
            if cancel_check is not None and cancel_check():
                raise ExportCancelled("Export cancelled.")
            process.stdin.write(view[offset : offset + chunk_size])

    def export(
        self,
        project: Project,
        output_path: str | Path,
        progress: ProgressCallback | None = None,
        cancel_check: CancelCheck | None = None,
    ) -> None:
        normalize_project(project)
        if not project.cards:
            raise ValueError("Insert at least one card before exporting.")

        ffmpeg = self.ffmpeg_path()
        output = Path(output_path).expanduser().resolve()
        if output.suffix.lower() != ".mp4":
            output = output.with_suffix(".mp4")
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = output.with_name(f".{output.stem}.cubical-part-{os.getpid()}.mp4")
        temporary.unlink(missing_ok=True)

        fps = int(project.settings.fps)
        width = int(project.settings.width)
        height = int(project.settings.height)
        duration = max(1.0 / fps, float(total_duration(project)))
        frame_count = max(1, math.ceil(duration * fps))
        self.cancelled = False

        soundtrack = Path(project.settings.soundtrack).expanduser() if project.settings.soundtrack else None
        if soundtrack is not None and not soundtrack.is_file():
            raise FileNotFoundError(f"The selected soundtrack does not exist: {soundtrack}")
        has_soundtrack = soundtrack is not None

        command = [
            ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{width}x{height}",
            "-r", str(fps), "-i", "pipe:0",
        ]

        if has_soundtrack and soundtrack is not None:
            if project.settings.soundtrack_loop:
                command += ["-stream_loop", "-1"]
            offset = max(0.0, float(project.settings.soundtrack_offset_seconds))
            if offset > 0.0:
                command += ["-ss", f"{offset:.6f}"]
            command += ["-i", str(soundtrack)]
            command += [
                "-filter_complex", f"[1:a:0]{self._audio_filter(project, duration)}[mixed_audio]",
                "-map", "0:v:0", "-map", "[mixed_audio]",
            ]
        else:
            command += ["-map", "0:v:0", "-an"]

        command += [
            "-c:v", "libx264",
            "-preset", validate_encoder_preset(project.settings.encoder_preset),
            "-crf", str(int(project.settings.encoder_crf)),
            "-pix_fmt", "yuv420p", "-threads", "0",
        ]
        if has_soundtrack:
            command += ["-c:a", "aac", "-b:a", "192k"]
        command += ["-t", f"{duration:.6f}", "-movflags", "+faststart", str(temporary)]

        process = subprocess.Popen(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            bufsize=0,
        )
        self._process = process
        stderr_parts: list[bytes] = []
        stderr_done = threading.Event()
        cancel_stop = threading.Event()

        def drain_stderr() -> None:
            stream = process.stderr
            if stream is None:
                stderr_done.set()
                return
            try:
                while True:
                    try:
                        chunk = stream.read(4096)
                    except TypeError:  # lightweight fake streams in unit tests
                        chunk = stream.read()
                    if not chunk:
                        break
                    stderr_parts.append(chunk)
            finally:
                stderr_done.set()

        def monitor_cancel() -> None:
            while not cancel_stop.wait(0.05):
                if self.cancelled or (cancel_check is not None and cancel_check()):
                    self.cancelled = True
                    if process.poll() is None:
                        try:
                            process.terminate()
                        except OSError:
                            pass
                    return

        stderr_thread = threading.Thread(target=drain_stderr, daemon=True)
        cancel_thread = threading.Thread(target=monitor_cancel, daemon=True)
        stderr_thread.start()
        cancel_thread.start()

        try:
            static_hold_payload: bytes | None = None

            def serial_payload(frame_number: int) -> bytes:
                nonlocal static_hold_payload
                seconds = frame_number / fps
                segment, _, _ = locate_segment(project, seconds)
                if segment is not None and segment.kind == "end_hold" and static_hold_payload is not None:
                    return static_hold_payload
                frame = self.renderer.render_output_frame(project, seconds)
                payload = self._frame_bytes(frame, width, height)
                if segment is not None and segment.kind == "end_hold":
                    static_hold_payload = payload
                return payload

            if self._custom_renderer or frame_count < 8:
                for frame_number in range(frame_count):
                    if self.cancelled or (cancel_check is not None and cancel_check()):
                        raise ExportCancelled("Export cancelled.")
                    self._write_payload(process, serial_payload(frame_number), cancel_check)
                    if progress:
                        progress(frame_number + 1, frame_count)
            else:
                workers = max(2, min(6, (os.cpu_count() or 2)))
                local = threading.local()

                def render_number(frame_number: int) -> bytes:
                    if not hasattr(local, "renderer"):
                        local.renderer = FrameRenderer()
                    seconds = frame_number / fps
                    frame = local.renderer.render_output_frame(project, seconds)
                    return self._frame_bytes(frame, width, height)

                pending: dict[int, Future[bytes]] = {}
                next_submit = 0
                next_write = 0
                max_pending = workers * 2
                with ThreadPoolExecutor(max_workers=workers) as pool:
                    while next_write < frame_count:
                        while next_submit < frame_count and len(pending) < max_pending:
                            pending[next_submit] = pool.submit(render_number, next_submit)
                            next_submit += 1
                        if self.cancelled or (cancel_check is not None and cancel_check()):
                            raise ExportCancelled("Export cancelled.")
                        payload = pending.pop(next_write).result()
                        self._write_payload(process, payload, cancel_check)
                        next_write += 1
                        if progress:
                            progress(next_write, frame_count)

            if process.stdin is not None:
                process.stdin.close()
            return_code = process.wait()
            stderr_done.wait(timeout=5.0)
            stderr_text = b"".join(stderr_parts).decode("utf-8", errors="replace").strip()
            if self.cancelled or (cancel_check is not None and cancel_check()):
                raise ExportCancelled("Export cancelled.")
            if return_code != 0:
                raise RuntimeError(
                    f"FFmpeg exited with code {return_code}."
                    + (f"\n{stderr_text}" if stderr_text else "")
                )
            self._verify_mp4(temporary)
            os.replace(temporary, output)
        except (BrokenPipeError, OSError) as exc:
            if process.poll() is None:
                process.terminate()
            return_code = process.wait()
            stderr_done.wait(timeout=5.0)
            stderr_text = b"".join(stderr_parts).decode("utf-8", errors="replace").strip()
            if self.cancelled or (cancel_check is not None and cancel_check()):
                raise ExportCancelled("Export cancelled.") from exc
            raise RuntimeError(
                f"FFmpeg stopped while receiving frames (exit code {return_code})."
                + (f"\n{stderr_text}" if stderr_text else "")
            ) from exc
        finally:
            cancel_stop.set()
            cancel_thread.join(timeout=1.0)
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5.0)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            close_stdin = getattr(process.stdin, "close", None)
            if callable(close_stdin) and not getattr(process.stdin, "closed", False):
                close_stdin()
            close_stderr = getattr(process.stderr, "close", None)
            if callable(close_stderr):
                close_stderr()
            stderr_thread.join(timeout=1.0)
            self._process = None
            temporary.unlink(missing_ok=True)
