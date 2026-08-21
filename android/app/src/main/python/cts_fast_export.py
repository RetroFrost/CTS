from __future__ import annotations

import gc
import json

from PIL import Image

from ccengine.models import Project
from ccengine.renderer import FrameRenderer
from ccengine.timing import total_duration, total_frame_count

# Keep export-only state outside cts_android_bridge so previews/imports can keep
# using the established bridge unchanged while a foreground export is running.
_renderer = FrameRenderer()
_export_project: Project | None = None

# These are the same limited-range BT.601 coefficients used by the old Kotlin
# argbToYuv loop. Pillow applies these transforms in native code instead of
# making Kotlin walk ~2.1 million pixels several times for every 1080p frame.
_Y_MATRIX = (66.0 / 256.0, 129.0 / 256.0, 25.0 / 256.0, 16.0)
_U_MATRIX = (-38.0 / 256.0, -74.0 / 256.0, 112.0 / 256.0, 128.0)
_V_MATRIX = (112.0 / 256.0, -94.0 / 256.0, -18.0 / 256.0, 128.0)


def begin_export(project_json: str) -> str:
    """Parse the project once and hold it for the whole video export."""
    global _export_project
    _export_project = Project.from_dict(json.loads(project_json))
    return json.dumps(
        {
            "frame_count": total_frame_count(_export_project),
            "duration": total_duration(_export_project),
            "fps": _export_project.settings.fps,
        }
    )


def render_yuv420(frame_index: int, width: int, height: int, semi_planar: bool) -> bytes:
    """Render one frame directly to encoder-ready I420 or NV12 bytes.

    The previous Android exporter crossed the Python/JVM boundary with an RGBA
    frame, converted that to an Android Bitmap, copied the pixels back to an
    IntArray, then converted RGB to YUV in Kotlin. This function keeps the
    exact shared renderer but performs RGB->YUV and 4:2:0 downsampling inside
    Pillow's native implementation, crossing the bridge only once with the
    final 1.5-byte-per-pixel encoder buffer.
    """
    project = _export_project
    if project is None:
        raise RuntimeError("Video export render session has not been started.")

    w = max(2, int(width))
    h = max(2, int(height))
    if w & 1:
        w -= 1
    if h & 1:
        h -= 1

    seconds = max(0, int(frame_index)) / max(1, project.settings.fps)
    source = _renderer.render(project, seconds, (w, h))
    rgb = source.convert("RGB")
    y_plane = None
    u_full = None
    v_full = None
    u_plane = None
    v_plane = None
    try:
        # convert(..., matrix) and BOX resizing execute in Pillow's C core.
        y_plane = rgb.convert("L", _Y_MATRIX)
        u_full = rgb.convert("L", _U_MATRIX)
        v_full = rgb.convert("L", _V_MATRIX)
        chroma_size = (w // 2, h // 2)
        u_plane = u_full.resize(chroma_size, Image.Resampling.BOX)
        v_plane = v_full.resize(chroma_size, Image.Resampling.BOX)

        y_bytes = y_plane.tobytes()
        u_bytes = u_plane.tobytes()
        v_bytes = v_plane.tobytes()

        if semi_planar:
            # Android COLOR_FormatYUV420SemiPlanar is fed as NV12, matching the
            # old exporter: Y followed by interleaved U,V chroma samples.
            chroma = bytearray(len(u_bytes) * 2)
            chroma[0::2] = u_bytes
            chroma[1::2] = v_bytes
            return y_bytes + bytes(chroma)

        # Planar and Flexible follow the old exporter's I420 layout.
        return y_bytes + u_bytes + v_bytes
    finally:
        for image in (v_plane, u_plane, v_full, u_full, y_plane, rgb, source):
            if image is not None:
                try:
                    image.close()
                except Exception:
                    pass


def end_export() -> None:
    global _export_project
    _export_project = None
    gc.collect()
