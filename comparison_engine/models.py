"""Data structures for Comparison Timeline Engine."""

from dataclasses import dataclass, field
from typing import List, Optional, Tuple

@dataclass
class ComparisonItem:
    badge_value: str
    badge_unit: str
    title: str
    description: str
    image_path: Optional[str] = None
    badge_color: Tuple[int, int, int] = (200, 16, 46)  # Default WatchData crimson red
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
    column_width: int = 480
    scroll_speed_px_per_sec: float = 80.0  # Speed during main scroll
    intro_duration_sec: float = 3.5
    outro_duration_sec: float = 4.0
    enable_badge_dynamic_shine: bool = True
    output_path: str = "output/comparison_video.mp4"

@dataclass
class TimelineProject:
    title: str
    items: List[ComparisonItem] = field(default_factory=list)
    credits: CreditsInfo = field(default_factory=CreditsInfo)
    config: VideoConfig = field(default_factory=VideoConfig)
