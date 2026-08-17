from __future__ import annotations

import cts_android_bridge as stable
from ccengine.lab_renderer import LabFrameRenderer


# Reuse the memory-bounded import/metadata implementation from the release app
# and swap only the rendering object for this Lab package.
stable._renderer = LabFrameRenderer()

metadata = stable.metadata
render_rgba = stable.render_rgba
import_data = stable.import_data
import_pack = stable.import_pack
