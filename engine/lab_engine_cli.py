from __future__ import annotations

import ccengine.exporter as exporter_module
import engine_cli as stable_cli

from ccengine.lab_renderer import LAB_VERSION, LabFrameRenderer


# Keep the stable CLI/project/import code intact, but replace only the renderer
# factory used by preview, self-test and the parallel export workers.
exporter_module.FrameRenderer = LabFrameRenderer


class LabVideoExporter(exporter_module.VideoExporter):
    def export(self, project, *args, **kwargs):
        # The Lab contract is explicitly baked at a fixed 60 FPS render tick.
        project.settings.fps = 60
        return super().export(project, *args, **kwargs)


stable_cli.FrameRenderer = LabFrameRenderer
stable_cli.VideoExporter = LabVideoExporter
stable_cli.VERSION = LAB_VERSION


def main() -> int:
    return stable_cli.main()


if __name__ == "__main__":
    raise SystemExit(main())
