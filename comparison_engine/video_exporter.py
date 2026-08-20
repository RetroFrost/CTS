"""High Performance Video & Preview Exporter using FFmpeg."""

import os
import subprocess
import time
from typing import Optional, Callable
from PIL import Image
from .timeline_renderer import TimelineCompositor
from .models import TimelineProject

class VideoExporter:
    def __init__(self, compositor: TimelineCompositor):
        self.compositor = compositor
        self.config = compositor.config

    def export_preview_image(self, timestamp_sec: float, output_path: str = "output/preview.png") -> str:
        """Render a single frame preview as a PNG."""
        os.makedirs(os.path.dirname(output_path), exist_ok=True)
        frame = self.compositor.render_frame(timestamp_sec)
        frame.save(output_path, "PNG")
        return output_path

    def export_video(
        self,
        output_path: Optional[str] = None,
        progress_callback: Optional[Callable[[int, int, float], None]] = None
    ) -> str:
        """
        Exports the full video via FFmpeg pipe.
        """
        out_file = output_path or self.config.output_path
        os.makedirs(os.path.dirname(out_file) or ".", exist_ok=True)
        
        width = self.config.width
        height = self.config.height
        fps = self.config.fps
        total_frames = self.compositor.total_frames
        
        cmd = [
            "ffmpeg",
            "-y",
            "-f", "rawvideo",
            "-vcodec", "rawvideo",
            "-s", f"{width}x{height}",
            "-pix_fmt", "rgba",
            "-r", str(fps),
            "-i", "-",
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-crf", "18",
            "-pix_fmt", "yuv420p",
            out_file
        ]
        
        proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stderr=subprocess.PIPE)
        start_time = time.time()
        
        try:
            for f in range(total_frames):
                t = f / fps
                frame = self.compositor.render_frame(t)
                proc.stdin.write(frame.tobytes())
                
                if progress_callback and (f % (fps * 2) == 0 or f == total_frames - 1):
                    elapsed = time.time() - start_time
                    progress_callback(f + 1, total_frames, elapsed)
                    
            proc.stdin.close()
            proc.wait()
        except Exception as e:
            proc.kill()
            raise RuntimeError(f"FFmpeg encoding failed: {e}")

        return out_file
