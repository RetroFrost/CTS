from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one source match, found {count}")
    return text.replace(old, new, 1)


def patch_engine() -> None:
    path = Path("windows-dotnet/RendererEngine.cs")
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '    private readonly Dictionary<string, SKBitmap> _imageCache = new(StringComparer.OrdinalIgnoreCase);\n',
        '    private readonly Dictionary<string, SKBitmap> _imageCache = new(StringComparer.OrdinalIgnoreCase);\n'
        '    private readonly InfiniteTimelineRenderer _infinite = new();\n'
        '    private readonly RelationshipsRenderer _relationships = new();\n',
        "renderer fields",
    )
    text = replace_once(
        text,
        '''    public SKBitmap Render(StudioProject project, RendererSpec spec, int frame, int width, int height)\n    {\n        width = Math.Max(2, width);\n        height = Math.Max(2, height);\n        var bitmap = new SKBitmap(new SKImageInfo(width, height, SKColorType.Bgra8888, SKAlphaType.Premul));\n''',
        '''    public SKBitmap Render(StudioProject project, RendererSpec spec, int frame, int width, int height)\n    {\n        width = Math.Max(2, width);\n        height = Math.Max(2, height);\n        if (spec.Engine == "infinite-timeline-exact") return _infinite.Render(project, spec, Math.Max(0, frame), width, height);\n        if (spec.Engine == "relationships-exact") return _relationships.Render(project, spec, Math.Max(0, frame), width, height);\n        var bitmap = new SKBitmap(new SKImageInfo(width, height, SKColorType.Bgra8888, SKAlphaType.Premul));\n''',
        "renderer dispatch",
    )
    text = replace_once(
        text,
        '''    public int FrameCount(StudioProject project, RendererSpec spec)\n    {\n        if (!project.AutoLength) return Math.Max(1, (int)Math.Round(project.CustomLengthSeconds * spec.ReferenceFps));\n        if (spec.CanonicalFrameCount > 0) return spec.CanonicalFrameCount;\n        if (spec.Engine == "ribbon-exact")\n''',
        '''    public int FrameCount(StudioProject project, RendererSpec spec)\n    {\n        if (!project.AutoLength) return Math.Max(1, (int)Math.Round(project.CustomLengthSeconds * spec.ReferenceFps));\n        if (spec.Engine == "infinite-timeline-exact") return _infinite.FrameCount(project, spec);\n        if (spec.Engine == "relationships-exact") return _relationships.FrameCount(project, spec);\n        if (spec.Engine == "scene-v3" && spec.SceneV3 != null && spec.RequiredFeatures.Contains("project-card-data", StringComparer.Ordinal))\n        {\n            var lastIndex = Math.Max(0, project.Cards.Count - 1);\n            var lastCard = spec.SceneV3.Objects.FirstOrDefault(obj => obj.Kind == "card" && CardIndex(obj) == lastIndex);\n            if (lastCard != null) return Math.Clamp(lastCard.LifespanEnd + 1, 1, spec.SceneV3.Frames);\n        }\n        if (spec.CanonicalFrameCount > 0) return spec.CanonicalFrameCount;\n        if (spec.Engine == "ribbon-exact")\n''',
        "renderer frame count",
    )
    text = replace_once(
        text,
        '    public void Dispose() { foreach (var bitmap in _imageCache.Values.Distinct()) bitmap.Dispose(); _imageCache.Clear(); }\n',
        '    public void Dispose() { _infinite.Dispose(); _relationships.Dispose(); foreach (var bitmap in _imageCache.Values.Distinct()) bitmap.Dispose(); _imageCache.Clear(); }\n',
        "renderer dispose",
    )
    path.write_text(text, encoding="utf-8")


def patch_main_window() -> None:
    path = Path("windows-dotnet/MainWindow.cs")
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '    private readonly RendererEngine _engine = new();\n',
        '    private readonly RendererEngine _engine = new();\n    private readonly IntroVideoSource _introSource = new();\n',
        "intro source field",
    )
    text = text.replace('_preview.MouseLeftButtonDown += PreviewMouseDown; _preview.MouseMove += PreviewMouseMove; _preview.MouseLeftButtonUp += PreviewMouseUp; _preview.MouseWheel += PreviewMouseWheel;',
                        '_preview.MouseLeftButtonDown += OnPreviewMouseDown; _preview.MouseMove += OnPreviewMouseMove; _preview.MouseLeftButtonUp += OnPreviewMouseUp; _preview.MouseWheel += OnPreviewMouseWheel;')
    text = text.replace('private void PreviewMouseDown(object sender,MouseButtonEventArgs e)', 'private void OnPreviewMouseDown(object sender,MouseButtonEventArgs e)')
    text = text.replace('private void PreviewMouseMove(object sender,MouseEventArgs e)', 'private void OnPreviewMouseMove(object sender,MouseEventArgs e)')
    text = text.replace('private void PreviewMouseUp(object sender,MouseButtonEventArgs e)', 'private void OnPreviewMouseUp(object sender,MouseButtonEventArgs e)')
    text = text.replace('private void PreviewMouseWheel(object sender,MouseWheelEventArgs e)', 'private void OnPreviewMouseWheel(object sender,MouseWheelEventArgs e)')
    text = replace_once(
        text,
        'Closing += (_,_) => { try { CommitFields(); ProjectAutosave.Save(_project); } catch { } _exportCts?.Cancel(); _engine.Dispose(); };',
        'Closing += (_,_) => { try { CommitFields(); ProjectAutosave.Save(_project); } catch { } _exportCts?.Cancel(); _introSource.Dispose(); _engine.Dispose(); };',
        "window dispose",
    )
    text = replace_once(
        text,
        'private int FrameCount(){var baseFrames=Math.Max(1,_engine.FrameCount(_project,_renderer));var intro=RendererIntroFrames();return _project.IntroMode switch{IntroMode.Renderer=>baseFrames,IntroMode.Disabled=>Math.Max(1,baseFrames-intro),IntroMode.Custom=>Math.Max(1,baseFrames-intro),_=>baseFrames};}',
        'private int FrameCount(){var baseFrames=Math.Max(1,_engine.FrameCount(_project,_renderer));var intro=Math.Min(RendererIntroFrames(),Math.Max(0,baseFrames-1));var fps=OutputFps();return _project.IntroMode switch{IntroMode.Renderer=>baseFrames,IntroMode.Disabled=>Math.Max(1,baseFrames-intro),IntroMode.Custom=>Math.Max(1,CustomIntroFrames(fps)+baseFrames-intro),_=>baseFrames};}',
        "timeline frame count",
    )
    text = replace_once(
        text,
        'private int RendererIntroFrames()=>_renderer.RendererApi>=3||_renderer.Engine=="scene-v3"?0:Math.Max(0,_renderer.OpeningStarts.FirstOrDefault());',
        'private int RendererIntroFrames()=>_renderer.RendererApi>=3||_renderer.Engine=="scene-v3"?0:Math.Max(0,_renderer.OpeningStarts.FirstOrDefault());\n    private int OutputFps()=>Math.Max(1,_renderer.PrecisionMode=="frame-exact"?_renderer.ReferenceFps:_project.Fps);\n    private int CustomIntroFrames(int fps)=>_project.IntroMode==IntroMode.Custom&&!string.IsNullOrWhiteSpace(_project.IntroVideo)?_introSource.FrameCount(_project.IntroVideo,fps):0;\n    private int? TimelineToEngineFrame(int frame){var intro=RendererIntroFrames();if(_project.IntroMode==IntroMode.Renderer)return Math.Max(0,frame);if(_project.IntroMode==IntroMode.Disabled)return Math.Max(0,frame+intro);var custom=CustomIntroFrames(OutputFps());if(frame<custom)return null;return Math.Max(0,frame-custom+intro);}',
        "timeline mapping",
    )
    text = replace_once(
        text,
        'if(_project.Cards.Count==0)return;try{var engineFrame=frame+(_project.IntroMode==IntroMode.Renderer?0:RendererIntroFrames());var width=Math.Clamp((int)Math.Round(Math.Max(640,_preview.ActualWidth*1.4)),640,1280);var height=Math.Max(2,(int)Math.Round(width*_renderer.ReferenceHeight/(double)Math.Max(1,_renderer.ReferenceWidth)));using var bitmap=_engine.Render(_project,_renderer,engineFrame,width,height);_preview.Source=ToBitmapSource(bitmap);UpdateFrameLabel();}catch(Exception ex){_status.Text="Preview: "+ex.Message;}',
        'if(_project.Cards.Count==0)return;try{var width=Math.Clamp((int)Math.Round(Math.Max(640,_preview.ActualWidth*1.4)),640,1280);var height=Math.Max(2,(int)Math.Round(width*_renderer.ReferenceHeight/(double)Math.Max(1,_renderer.ReferenceWidth)));var engineFrame=TimelineToEngineFrame(frame);using var bitmap=engineFrame==null?_introSource.Render(_project.IntroVideo,frame,OutputFps(),width,height):_engine.Render(_project,_renderer,engineFrame.Value,width,height);_preview.Source=ToBitmapSource(bitmap);UpdateFrameLabel();}catch(Exception ex){_status.Text="Preview: "+ex.Message;}',
        "custom intro preview",
    )
    text = replace_once(
        text,
        'private int HitTestCard(int frame,double x,double y)\n    {\n        if(_project.Cards.Count==0)return -1;',
        'private int HitTestCard(int frame,double x,double y)\n    {\n        if(_project.Cards.Count==0)return -1;var mapped=TimelineToEngineFrame(frame);if(mapped==null)return -1;frame=mapped.Value;',
        "preview hit-test timeline mapping",
    )
    text = text.replace('var fps=Math.Max(1,_renderer.PrecisionMode=="frame-exact"?_renderer.ReferenceFps:_project.Fps);var frame=(int)Math.Round(_timeline.Value);',
                        'var fps=OutputFps();var frame=(int)Math.Round(_timeline.Value);')
    path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    patch_engine()
    patch_main_window()
    print("Windows mobile parity source patch verified/applied")
