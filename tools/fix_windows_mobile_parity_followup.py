from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one source match, found {count}")
    return text.replace(old, new, 1)


def patch_infinite() -> None:
    path = Path("windows-dotnet/InfiniteTimelineRenderer.cs")
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        'SKShader.CreateLinearGradient(new SKPoint(0,10),new SKPoint(0,445),[new SKColor(239,29,23),new SKColor(204,11,11)],null,SKShaderTileMode.Clamp)',
        'SKShader.CreateLinearGradient(new SKPoint(0,10),new SKPoint(0,445),new SKColor[]{new SKColor(239,29,23),new SKColor(204,11,11)},new float[]{0f,1f},SKShaderTileMode.Clamp)',
        "infinite badge gradient overload",
    )
    path.write_text(text, encoding="utf-8")


def patch_main_window() -> None:
    path = Path("windows-dotnet/MainWindow.cs")
    text = path.read_text(encoding="utf-8")

    old_drag = 'var current=e.GetPosition(_preview);var dx=(current.X-_dragStart.Value.X)/Math.Max(1,_preview.ActualWidth)*_renderer.ReferenceWidth;var dy=(current.Y-_dragStart.Value.Y)/Math.Max(1,_preview.ActualHeight)*_renderer.ReferenceHeight;'
    new_drag = 'var current=e.GetPosition(_preview);var rw=Math.Max(1,_renderer.ReferenceWidth);var rh=Math.Max(1,_renderer.ReferenceHeight);var displayScale=Math.Max(.000001,Math.Min(_preview.ActualWidth/rw,_preview.ActualHeight/rh));var dx=(current.X-_dragStart.Value.X)/displayScale;var dy=(current.Y-_dragStart.Value.Y)/displayScale;'
    text = replace_once(text, old_drag, new_drag, "letterboxed preview drag")

    old_legacy = '''    private Dictionary<int,double> LegacyPositions(int frame)\n    {\n        var result=new Dictionary<int,double>();if(_renderer.Engine=="ribbon-exact")\n        {\n            if(frame>=_renderer.ContinuousStartFrame&&_project.Cards.Count>4){var segment=(frame-_renderer.ContinuousStartFrame)/512;var scroll=_renderer.Track($"ribbon.scroll.{segment}",frame)??((frame-_renderer.ContinuousStartFrame)/(double)Math.Max(1,_renderer.ContinuousStepFrames)*_renderer.SlotPitch);for(var i=0;i<_project.Cards.Count;i++){var px=i*_renderer.SlotPitch-scroll;if(px>-_renderer.SlotPitch&&px<_renderer.ReferenceWidth+_renderer.SlotPitch)result[i]=px;}return result;}\n            var active=-1;for(var i=0;i<Math.Min(4,_project.Cards.Count);i++)if(frame>=_renderer.OpeningStarts.ElementAtOrDefault(i))active=i;if(active<0)return result;for(var i=0;i<=active;i++)result[i]=i*_renderer.SlotPitch;return result;\n        }\n        var step=Math.Max(1,_renderer.ContinuousStepFrames);var standard=frame/(double)step*_renderer.SlotPitch;for(var i=0;i<_project.Cards.Count;i++){var px=i*_renderer.SlotPitch-standard;if(px>-_renderer.SlotPitch&&px<_renderer.ReferenceWidth+_renderer.SlotPitch)result[i]=px;}return result;\n    }'''
    new_legacy = '''    private Dictionary<int,double> LegacyPositions(int frame)\n    {\n        var result=new Dictionary<int,double>();\n        if(_renderer.Engine=="ribbon-exact")\n        {\n            if(frame>=_renderer.ContinuousStartFrame&&_project.Cards.Count>4){var segment=(frame-_renderer.ContinuousStartFrame)/512;var scroll=_renderer.Track($"ribbon.scroll.{segment}",frame)??((frame-_renderer.ContinuousStartFrame)/(double)Math.Max(1,_renderer.ContinuousStepFrames)*_renderer.SlotPitch);for(var i=0;i<_project.Cards.Count;i++){var px=i*_renderer.SlotPitch-scroll;if(px>-_renderer.SlotPitch&&px<_renderer.ReferenceWidth+_renderer.SlotPitch)result[i]=px;}return result;}\n            var active=-1;for(var i=0;i<Math.Min(4,_project.Cards.Count);i++)if(frame>=_renderer.OpeningStarts.ElementAtOrDefault(i))active=i;if(active<0)return result;for(var i=0;i<=active;i++)result[i]=i*_renderer.SlotPitch;return result;\n        }\n        if(_renderer.Engine=="relationships-exact")\n        {\n            if(frame<_renderer.ContinuousStartFrame){for(var i=0;i<Math.Min(4,_project.Cards.Count);i++){var start=i<_renderer.OpeningStarts.Count?_renderer.OpeningStarts[i]:384+i*140;if(frame>=start)result[i]=i*_renderer.SlotPitch;}return result;}\n            var segment=(frame-_renderer.ContinuousStartFrame)/4096;var scroll=_renderer.Track($"relationships.scroll.{segment}",frame)??((frame-_renderer.ContinuousStartFrame)*2.0);for(var i=0;i<_project.Cards.Count;i++){var px=i*_renderer.SlotPitch-scroll;if(px>-_renderer.SlotPitch*2&&px<_renderer.ReferenceWidth+_renderer.SlotPitch*2)result[i]=px;}return result;\n        }\n        if(_renderer.Engine=="infinite-timeline-exact")\n        {\n            if(frame<_renderer.ContinuousStartFrame){for(var i=0;i<Math.Min(4,_project.Cards.Count);i++){var start=i<_renderer.OpeningStarts.Count?_renderer.OpeningStarts[i]:new[]{187,261,329,398}[i];if(frame>=start)result[i]=i*480.0;}return result;}\n            var scroll=_renderer.Track("infinite.scroll",frame)??InfinitePreviewScroll(frame,_renderer.ContinuousStartFrame);for(var i=0;i<_project.Cards.Count;i++){var px=i*483.0-scroll;if(px>-483&&px<_renderer.ReferenceWidth+483)result[i]=px;}return result;\n        }\n        var step=Math.Max(1,_renderer.ContinuousStepFrames);var standard=frame/(double)step*_renderer.SlotPitch;for(var i=0;i<_project.Cards.Count;i++){var px=i*_renderer.SlotPitch-standard;if(px>-_renderer.SlotPitch&&px<_renderer.ReferenceWidth+_renderer.SlotPitch)result[i]=px;}return result;\n    }\n    private static double InfinitePreviewScroll(int frame,int start){const double speed=3.2065854,fast=24;const int fastStart=5265;if(frame<=fastStart)return Math.Max(0,frame-start)*speed;var atFast=Math.Max(0,fastStart-start)*speed;return atFast+(frame-fastStart)*fast;}'''
    text = replace_once(text, old_legacy, new_legacy, "renderer-aware preview hit test")
    path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    patch_infinite()
    patch_main_window()
    print("Windows parity follow-up patch verified/applied")
