from ccengine.models import Project
from ccengine.renderer import FrameRenderer


def test_new_project_has_no_untitled_placeholder():
    assert Project().name == ""


def test_font_resolver_accepts_empty_and_paths(tmp_path):
    renderer = FrameRenderer()
    assert renderer._resolve_font("") == ""
    fake = tmp_path / "font.ttf"
    fake.write_bytes(b"not-a-real-font")
    assert renderer._resolve_font(str(fake)) == str(fake)
