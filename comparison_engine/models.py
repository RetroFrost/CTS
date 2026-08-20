"""Data structures for Comparison Timeline Engine.

The legacy data model remains for project compatibility. Official rendering is
performed by the shared ccengine strict reference renderer; these defaults are
kept aligned with the measured 1920x1080/60 source contract so fallback tools
do not silently drift to the old approximate timing.
"""

from dataclasses import dataclass, field
from typing import List, Optional, Tuple


@dataclass
class ComparisonItem:
    badge_value: str
    badge_unit: str
    title: str
    description: str
    image_path: Optional[str] = None
    badge_color: Tuple[int, int, int] = (211, 7, 13)
    custom_bg_color: Optional[Tuple[int, int, int]] = None


@dataclass
class CreditsInfo:
    intro_explanation: str = "The values presented are the years in which various developmental milestones in languages occurred."
    lead_research: str = "Ahmed"
    fact_check: str = "Alex Lambert"
    lead_designer: str = "Jack H"
    edit_post: str = "Alex Pacheco"
    thumbnail_designer: str = "Diego Garcia"
    video_idea: str = "Ideaguys.ca"


@dataclass
class VideoConfig:
    width: int = 1920
    height: int = 1080
    fps: int = 60
    columns_visible: int = 4
    # Measured source slot pitch. Four card bodies plus their separator/inset
    # geometry occupy the 1920 px frame; this is not the old rounded 480 px
    # approximation used by the first Python compositor.
    column_width: int = 476
    # Canonical steady conveyor velocity: 476 px every 214 source frames.
    scroll_speed_px_per_sec: float = 476.0 * 60.0 / 214.0
    # First four source starts: f0, f120, f240, f360. Continuous conveyor starts
    # at f528 (8.8 s).
    intro_duration_sec: float = 528.0 / 60.0
    # Source outro: 43 wipe + 11 rise + 268 hold + 79 fade + 8 black frames.
    outro_duration_sec: float = 409.0 / 60.0
    enable_badge_dynamic_shine: bool = True
    output_path: str = "output/comparison_video.mp4"


@dataclass
class TimelineProject:
    title: str
    items: List[ComparisonItem] = field(default_factory=list)
    credits: CreditsInfo = field(default_factory=CreditsInfo)
    config: VideoConfig = field(default_factory=VideoConfig)
