using Microsoft.Win32;
using SkiaSharp;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace CubicalCompare.Windows;

public sealed class MainWindow : Window
{
    private readonly RendererStore _rendererStore = new();
    private readonly RendererEngine _engine = new();
    private readonly IntroVideoSource _introSource = new();
    private StudioProject _project;
    private RendererSpec _renderer;
    private string? _projectPath;
    private bool _loading;
    private readonly ListBox _cards = new();
    private readonly Image _preview = new() { Stretch = Stretch.Uniform, SnapsToDevicePixels = true };
    private readonly Border _verticalGuide = new() { Width = 1, Background = new SolidColorBrush(Color.FromRgb(100, 210, 255)), HorizontalAlignment = HorizontalAlignment.Center, Visibility = Visibility.Collapsed, IsHitTestVisible = false };
    private readonly Border _horizontalGuide = new() { Height = 1, Background = new SolidColorBrush(Color.FromRgb(100, 210, 255)), VerticalAlignment = VerticalAlignment.Center, Visibility = Visibility.Collapsed, IsHitTestVisible = false };
    private readonly Slider _timeline = new() { Minimum = 0, IsSnapToTickEnabled = true, TickFrequency = 1 };
    private readonly TextBlock _frameLabel = new();
    private readonly TextBlock _timeLabel = new() { Foreground = new SolidColorBrush(Color.FromRgb(175,175,175)) };
    private readonly TextBlock _accuracyLabel = new() { TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _outputLabel = new() { TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _encoderLabel = new() { TextWrapping = TextWrapping.Wrap };
    private readonly TextBox _projectName = new();
    private readonly TextBox _title = new();
    private readonly TextBox _value = new();
    private readonly TextBox _badgeHeader = new();
    private readonly TextBox _description = new() { AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
    private readonly TextBox _image = new();
    private readonly TextBox _introVideo = new();
    private readonly TextBox _soundtrack = new();
    private readonly TextBox _fontFile = new();
    private readonly CheckBox _badges = new() { Content = "Show badges" };
    private readonly CheckBox _credits = new() { Content = "Credits" };
    private readonly CheckBox _autoLength = new() { Content = "Automatic length" };
    private readonly CheckBox _soundtrackLoop = new() { Content = "Loop soundtrack" };
    private readonly TextBox _length = new() { Text = "01:30.000" };
    private readonly ComboBox _introMode = new();
    private readonly ComboBox _fontFamily = new();
    private readonly ComboBox _codec = new();
    private readonly ComboBox _imageLayer = new();
    private readonly ComboBox _speed = new();
    private readonly Slider _soundtrackVolume = new() { Minimum = 0, Maximum = 1, TickFrequency = .05, IsSnapToTickEnabled = false };
    private readonly Slider _imageScale = Slider(.05, 12, .01);
    private readonly Slider _imageX = Slider(-2400, 2400, 1);
    private readonly Slider _imageY = Slider(-2400, 2400, 1);
    private readonly Slider _imageRotation = Slider(-180, 180, 1);
    private readonly Slider _cropLeft = Slider(0, .95, .01);
    private readonly Slider _cropTop = Slider(0, .95, .01);
    private readonly Slider _cropRight = Slider(0, .95, .01);
    private readonly Slider _cropBottom = Slider(0, .95, .01);
    private readonly CheckBox _directTransform = new() { Content = "Transform directly in preview" };
    private readonly TextBlock _rendererLabel = new();
    private readonly TextBlock _status = new() { Text = "Ready", TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly TextBlock _saveState = new() { Text = "Saved", Foreground = new SolidColorBrush(Color.FromRgb(170,170,170)) };
    private readonly ProgressBar _progress = new() { Minimum = 0, Maximum = 1, Height = 8, Visibility = Visibility.Collapsed };
    private readonly Button _exportButton;
    private readonly Button _cancelExport = new() { Content = "Cancel export", Visibility = Visibility.Collapsed };
    private readonly DispatcherTimer _playTimer = new();
    private readonly DispatcherTimer _previewDebounce = new() { Interval = TimeSpan.FromMilliseconds(55) };
    private readonly DispatcherTimer _autosaveDebounce = new() { Interval = TimeSpan.FromMilliseconds(900) };
    private CancellationTokenSource? _exportCts;
    private int _selectedIndex;
    private bool _playing;
    private double _playbackSpeed = 1;
    private Point? _dragStart;
    private StudioCard? _dragOrigin;

    public MainWindow()
    {
        _renderer = _rendererStore.Active();
        _project = ProjectAutosave.Load() ?? new StudioProject();
        Title = "Cubical Compare 3.0.300 — Windows (.NET)";
        Width = 1440; Height = 900; MinWidth = 760; MinHeight = 500;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Background = new SolidColorBrush(Color.FromRgb(24,24,25)); Foreground = Brushes.White;

        var root = new DockPanel(); Content = root;
        var toolbar = new WrapPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(10,8,10,6) };
        DockPanel.SetDock(toolbar, Dock.Top); root.Children.Add(toolbar);
        toolbar.Children.Add(ActionButton("New", NewProject));
        toolbar.Children.Add(ActionButton("Open", OpenProject));
        toolbar.Children.Add(ActionButton("Save", SaveProject));
        toolbar.Children.Add(ActionButton("Save as", SaveProjectAs));
        toolbar.Children.Add(Separator());
        toolbar.Children.Add(ActionButton("Spreadsheet", ImportData));
        toolbar.Children.Add(ActionButton("MegaPack", ImportMegaPack));
        toolbar.Children.Add(Separator());
        toolbar.Children.Add(ActionButton("Import renderer", ImportRenderer));
        toolbar.Children.Add(ActionButton("Renderer library", OpenRendererLibrary));
        toolbar.Children.Add(Separator());
        _exportButton = ActionButton("Export MP4", ExportVideo); toolbar.Children.Add(_exportButton);
        _cancelExport.Margin = new Thickness(6,0,0,0); _cancelExport.Padding = new Thickness(10,5,10,5); _cancelExport.Click += (_, _) => _exportCts?.Cancel(); toolbar.Children.Add(_cancelExport);

        var footer = new StackPanel { Margin = new Thickness(10,3,10,9) }; DockPanel.SetDock(footer, Dock.Bottom); root.Children.Add(footer);
        footer.Children.Add(_progress);
        var footerRow = new DockPanel { Margin = new Thickness(0,5,0,0) }; footer.Children.Add(footerRow);
        _rendererLabel.Foreground = new SolidColorBrush(Color.FromRgb(160,210,255)); _rendererLabel.TextTrimming = TextTrimming.CharacterEllipsis; DockPanel.SetDock(_rendererLabel, Dock.Right); footerRow.Children.Add(_rendererLabel);
        DockPanel.SetDock(_saveState, Dock.Right); _saveState.Margin = new Thickness(8,0,10,0); footerRow.Children.Add(_saveState);
        _status.Foreground = new SolidColorBrush(Color.FromRgb(195,195,195)); footerRow.Children.Add(_status);

        var main = new Grid { Margin = new Thickness(10,4,10,4) };
        main.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(240) });
        main.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        main.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(360) }); root.Children.Add(main);
        BuildCardPane(main); BuildPreviewPane(main); BuildEditorPane(main);
        WireEvents();
        RefreshAll();
        AdaptiveLayout.Attach(this);
    }

    private void BuildCardPane(Grid main)
    {
        var pane = new DockPanel { Margin = new Thickness(0,0,10,0), LastChildFill = true }; Grid.SetColumn(pane,0); main.Children.Add(pane);
        var header = new TextBlock { Text = "Cards", FontSize = 18, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0,0,0,7) }; DockPanel.SetDock(header,Dock.Top); pane.Children.Add(header);
        var actions = new WrapPanel { Margin = new Thickness(0,7,0,0) }; DockPanel.SetDock(actions,Dock.Bottom); pane.Children.Add(actions);
        actions.Children.Add(SmallButton("Add", AddCard)); actions.Children.Add(SmallButton("Duplicate", DuplicateCard)); actions.Children.Add(SmallButton("Delete", DeleteCard));
        _cards.Background = new SolidColorBrush(Color.FromRgb(35,35,37)); _cards.Foreground = Brushes.White; _cards.BorderBrush = new SolidColorBrush(Color.FromRgb(70,70,72));
        _cards.HorizontalContentAlignment = HorizontalAlignment.Stretch; pane.Children.Add(_cards);
    }

    private void BuildPreviewPane(Grid main)
    {
        var pane = new Grid { Margin = new Thickness(4,0,10,0) };
        pane.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); pane.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1,GridUnitType.Star) }); pane.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        Grid.SetColumn(pane,1); main.Children.Add(pane);
        var header = new DockPanel { Margin = new Thickness(0,0,0,7) }; pane.Children.Add(header);
        var previewTitle = new TextBlock { Text = "Preview", FontSize = 18, FontWeight = FontWeights.SemiBold }; header.Children.Add(previewTitle);
        _accuracyLabel.HorizontalAlignment = HorizontalAlignment.Right; DockPanel.SetDock(_accuracyLabel,Dock.Right); header.Children.Add(_accuracyLabel);
        var previewGrid = new Grid { Background = Brushes.Black, ClipToBounds = true }; previewGrid.Children.Add(_preview); previewGrid.Children.Add(_verticalGuide); previewGrid.Children.Add(_horizontalGuide);
        var previewBox = new Border { Background = Brushes.Black, BorderBrush = new SolidColorBrush(Color.FromRgb(70,70,72)), BorderThickness = new Thickness(1), Child = previewGrid }; Grid.SetRow(previewBox,1); pane.Children.Add(previewBox);

        var controls = new StackPanel { Margin = new Thickness(0,8,0,0) }; Grid.SetRow(controls,2); pane.Children.Add(controls);
        var row = new Grid(); row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1,GridUnitType.Star) }); row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); controls.Children.Add(row);
        var play = SmallButton("▶", TogglePlay); Grid.SetColumn(play,0); row.Children.Add(play);
        var previous = SmallButton("−1", () => StepFrame(-1)); Grid.SetColumn(previous,1); row.Children.Add(previous);
        _timeline.Margin = new Thickness(6,0,6,0); Grid.SetColumn(_timeline,2); row.Children.Add(_timeline);
        var next = SmallButton("+1", () => StepFrame(1)); Grid.SetColumn(next,3); row.Children.Add(next);
        _speed.Width = 68; _speed.Margin = new Thickness(4,0,0,0); _speed.Items.Add("0.5×"); _speed.Items.Add("1×"); _speed.Items.Add("2×"); _speed.SelectedIndex = 1; Grid.SetColumn(_speed,4); row.Children.Add(_speed);
        var detail = new DockPanel { Margin = new Thickness(0,5,0,0) }; controls.Children.Add(detail); DockPanel.SetDock(_timeLabel,Dock.Right); detail.Children.Add(_timeLabel); detail.Children.Add(_frameLabel);
        _directTransform.Margin = new Thickness(0,6,0,0); controls.Children.Add(_directTransform);
    }

    private void BuildEditorPane(Grid main)
    {
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, Margin = new Thickness(5,0,0,0) }; Grid.SetColumn(scroll,2); main.Children.Add(scroll);
        var pane = new StackPanel(); scroll.Content = pane;
        Section(pane, "Card data");
        AddField(pane,"Title",_title); AddField(pane,"Badge header",_badgeHeader); AddField(pane,"Value",_value); AddField(pane,"Description",_description,105);
        var imageRow = AddField(pane,"Artwork",_image); imageRow.Children.Add(SmallButton("Browse",ChooseImage)); imageRow.Children.Add(SmallButton("Remove",RemoveImage));
        var layer = AddLabeledControl(pane,"Artwork layer",_imageLayer); _imageLayer.Items.Add("Behind badge"); _imageLayer.Items.Add("In front"); _imageLayer.SelectedIndex = 0;
        pane.Children.Add(_directTransformCloneNote());
        AddTransformControl(pane,"Scale",_imageScale,() => $"{_imageScale.Value:0.00}×");
        AddTransformControl(pane,"Horizontal",_imageX,() => $"{_imageX.Value:0} px");
        AddTransformControl(pane,"Vertical",_imageY,() => $"{_imageY.Value:0} px");
        AddTransformControl(pane,"Rotation",_imageRotation,() => $"{_imageRotation.Value:0}°");
        AddTransformControl(pane,"Crop left",_cropLeft,() => $"{_cropLeft.Value*100:0}%");
        AddTransformControl(pane,"Crop right",_cropRight,() => $"{_cropRight.Value*100:0}%");
        AddTransformControl(pane,"Crop top",_cropTop,() => $"{_cropTop.Value*100:0}%");
        AddTransformControl(pane,"Crop bottom",_cropBottom,() => $"{_cropBottom.Value*100:0}%");
        var transformActions = new WrapPanel { Margin = new Thickness(0,6,0,0) }; pane.Children.Add(transformActions); transformActions.Children.Add(SmallButton("Reset transform",ResetTransform)); transformActions.Children.Add(SmallButton("Apply onward",ApplyTransformOnward));

        Section(pane,"Project"); AddField(pane,"Project name",_projectName);
        _outputLabel.Margin = new Thickness(0,5,0,0); pane.Children.Add(_outputLabel);
        Section(pane,"Intro"); AddLabeledControl(pane,"Mode",_introMode); _introMode.Items.Add("Renderer default"); _introMode.Items.Add("Custom MP4"); _introMode.Items.Add("Disabled"); _introMode.SelectedIndex = 0;
        var introRow = AddField(pane,"Custom MP4",_introVideo); introRow.Children.Add(SmallButton("Browse",ChooseIntro)); introRow.Children.Add(SmallButton("Clear",() => _introVideo.Text = ""));
        Section(pane,"Duration"); _autoLength.Margin = new Thickness(0,4,0,4); pane.Children.Add(_autoLength); AddField(pane,"MM:SS.sss / seconds",_length);
        Section(pane,"Typography"); AddLabeledControl(pane,"Font",_fontFamily); foreach (var item in new[] { "Renderer default", "Sans", "Condensed", "Serif", "Mono" }) _fontFamily.Items.Add(item); _fontFamily.SelectedIndex = 0;
        var fontRow = AddField(pane,"Custom TTF / OTF",_fontFile); fontRow.Children.Add(SmallButton("Browse",ChooseFont)); fontRow.Children.Add(SmallButton("Default",() => { _fontFile.Text=""; _fontFamily.SelectedIndex=0; }));
        Section(pane,"Audio"); var soundRow = AddField(pane,"Soundtrack",_soundtrack); soundRow.Children.Add(SmallButton("Browse",ChooseSoundtrack)); soundRow.Children.Add(SmallButton("Clear",() => _soundtrack.Text=""));
        AddTransformControl(pane,"Volume",_soundtrackVolume,() => $"{_soundtrackVolume.Value*100:0}%"); _soundtrackLoop.Margin = new Thickness(0,4,0,0); pane.Children.Add(_soundtrackLoop);
        Section(pane,"Video"); _badges.Margin = new Thickness(0,3,0,0); pane.Children.Add(_badges); _credits.Margin = new Thickness(0,5,0,0); pane.Children.Add(_credits); AddLabeledControl(pane,"Codec",_codec); foreach (var item in new[] { "Auto", "H.264", "H.265" }) _codec.Items.Add(item); _codec.SelectedIndex = 0;
        _encoderLabel.Margin = new Thickness(0,5,0,14); _encoderLabel.Foreground = new SolidColorBrush(Color.FromRgb(175,175,175)); pane.Children.Add(_encoderLabel);
    }

    private TextBlock _directTransformCloneNote() => new() { Text = "Preview transform: enable the checkbox below the video, click artwork, then drag. Mouse wheel scales; Alt+wheel rotates. Fine-tune controls below use the same project data.", TextWrapping = TextWrapping.Wrap, FontSize = 11, Foreground = new SolidColorBrush(Color.FromRgb(165,165,165)), Margin = new Thickness(0,5,0,3) };

    private void WireEvents()
    {
        _timeline.ValueChanged += (_,_) => { if (!_loading) RenderPreview((int)Math.Round(_timeline.Value)); };
        _cards.SelectionChanged += (_,_) => { if (_loading || _cards.SelectedIndex < 0) return; CommitFields(); _selectedIndex = _cards.SelectedIndex; LoadFields(); RenderPreview((int)_timeline.Value); };
        _speed.SelectionChanged += (_,_) => { _playbackSpeed = _speed.SelectedIndex switch { 0 => .5, 2 => 2, _ => 1 }; UpdatePlayTimer(); };
        _playTimer.Tick += (_,_) => { if (!_playing) return; var next=(int)_timeline.Value+1; if(next>_timeline.Maximum){_playing=false;_playTimer.Stop();next=0;} _timeline.Value=next; };
        _previewDebounce.Tick += (_,_) => { _previewDebounce.Stop(); RefreshPreview(); };
        _autosaveDebounce.Tick += (_,_) => { _autosaveDebounce.Stop(); try { ProjectAutosave.Save(_project); _saveState.Text="Saved"; } catch { _saveState.Text="Save failed"; } };
        foreach (var box in new[] { _projectName,_title,_value,_badgeHeader,_description,_image,_introVideo,_soundtrack,_fontFile,_length }) box.TextChanged += (_,_) => Changed();
        foreach (var check in new[] { _badges,_credits,_autoLength,_soundtrackLoop }) { check.Checked += (_,_)=>Changed(true); check.Unchecked += (_,_)=>Changed(true); }
        foreach (var combo in new[] { _introMode,_fontFamily,_codec,_imageLayer }) combo.SelectionChanged += (_,_) => Changed(true);
        foreach (var slider in new[] { _soundtrackVolume,_imageScale,_imageX,_imageY,_imageRotation,_cropLeft,_cropTop,_cropRight,_cropBottom }) slider.ValueChanged += (_,_) => Changed(false);
        _preview.MouseLeftButtonDown += OnPreviewMouseDown; _preview.MouseMove += OnPreviewMouseMove; _preview.MouseLeftButtonUp += OnPreviewMouseUp; _preview.MouseWheel += OnPreviewMouseWheel;
        Closing += (_,_) => { try { CommitFields(); ProjectAutosave.Save(_project); } catch { } _exportCts?.Cancel(); _introSource.Dispose(); _engine.Dispose(); };
    }

    private void Changed(bool refreshTimeline = false)
    {
        if (_loading) return; CommitFields(); if (refreshTimeline) RefreshTimeline(); SchedulePreview(); ScheduleAutosave(); if (_codec.IsDropDownOpen || Keyboard.FocusedElement == _codec) _ = UpdateEncoderLabelAsync();
    }

    private void AddCard() { CommitFields(); _project.Cards.Add(new StudioCard { Title=$"Card {_project.Cards.Count+1}", Value=(_project.Cards.Count+1).ToString(CultureInfo.InvariantCulture) }); _selectedIndex=_project.Cards.Count-1; RefreshAfterCardMutation(); }
    private void DuplicateCard() { if(_project.Cards.Count==0)return; CommitFields(); var c=_project.Cards[Math.Clamp(_selectedIndex,0,_project.Cards.Count-1)]; _project.Cards.Insert(_selectedIndex+1,CloneCard(c,true)); _selectedIndex++; RefreshAfterCardMutation(); }
    private void DeleteCard() { if(_project.Cards.Count<=1)return; _project.Cards.RemoveAt(Math.Clamp(_selectedIndex,0,_project.Cards.Count-1)); _selectedIndex=Math.Clamp(_selectedIndex,0,_project.Cards.Count-1); RefreshAfterCardMutation(); }
    private void RefreshAfterCardMutation() { RefreshCardList(); RefreshTimeline(); LoadFields(); SchedulePreview(); ScheduleAutosave(); }

    private static StudioCard CloneCard(StudioCard c, bool newId) => new()
    {
        Id=newId?Guid.NewGuid().ToString("N"):c.Id, Title=c.Title, Value=c.Value, BadgeHeader=c.BadgeHeader, Description=c.Description, Image=c.Image,
        ImageX=c.ImageX,ImageY=c.ImageY,ImageScale=c.ImageScale,ImageRotation=c.ImageRotation,ImageCropLeft=c.ImageCropLeft,ImageCropTop=c.ImageCropTop,ImageCropRight=c.ImageCropRight,ImageCropBottom=c.ImageCropBottom,ImageLayer=c.ImageLayer,
    };

    private void NewProject() { CommitFields(); _project=new StudioProject(); _projectPath=null; _selectedIndex=0; RefreshAll(); ScheduleAutosave(); _status.Text="New project"; }
    private void OpenProject()
    {
        var dlg=new OpenFileDialog{Filter="Cubical Compare project|*.json;*.ccproject|JSON|*.json|All files|*.*"}; if(dlg.ShowDialog(this)!=true)return;
        try { _project=StudioProject.Load(dlg.FileName); _projectPath=dlg.FileName; _selectedIndex=0; RefreshAll(); ScheduleAutosave(); _status.Text="Opened "+Path.GetFileName(dlg.FileName); } catch(Exception ex){Error("Open project",ex);}
    }
    private void SaveProject() { if(_projectPath==null){SaveProjectAs();return;} CommitFields(); try{_project.Save(_projectPath);_status.Text="Saved "+Path.GetFileName(_projectPath);_saveState.Text="Saved";}catch(Exception ex){Error("Save project",ex);} }
    private void SaveProjectAs() { var dlg=new SaveFileDialog{Filter="Cubical Compare project|*.json",FileName=SafeFileName(_project.Name)+".json"}; if(dlg.ShowDialog(this)!=true)return; _projectPath=dlg.FileName;SaveProject(); }

    private void ImportData()
    {
        var dlg=new OpenFileDialog{Filter="Data files|*.csv;*.tsv;*.txt;*.xlsx;*.xlsm|CSV / TSV|*.csv;*.tsv;*.txt|Excel workbook|*.xlsx;*.xlsm|All files|*.*"}; if(dlg.ShowDialog(this)!=true)return;
        try { CommitFields(); _project=NativeImporters.ImportData(_project,dlg.FileName);_selectedIndex=0;RefreshAll();ScheduleAutosave();_status.Text=$"Imported {_project.Cards.Count} cards from {Path.GetFileName(dlg.FileName)}";}catch(Exception ex){Error("Import data",ex);}
    }

    private void ImportMegaPack()
    {
        var dlg=new OpenFileDialog{Filter="Cubical Compare MegaPack|*.zip;*.megapack|ZIP|*.zip|All files|*.*"}; if(dlg.ShowDialog(this)!=true)return;
        try
        {
            CommitFields(); var previous=_project; var root=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"CubicalCompare","megapacks",Guid.NewGuid().ToString("N")); var next=NativeImporters.ImportMegaPack(dlg.FileName,root);
            next.EncoderPreference=previous.EncoderPreference;next.IntroMode=previous.IntroMode;next.IntroVideo=previous.IntroVideo;next.FontFamily=previous.FontFamily;next.FontFile=previous.FontFile;
            _project=next;_selectedIndex=0;RefreshAll();ScheduleAutosave();_status.Text=$"Imported MegaPack with {_project.Cards.Count} cards";
        } catch(Exception ex){Error("Import MegaPack",ex);}
    }

    private void ImportRenderer()
    {
        var dlg=new OpenFileDialog{Filter="Cubical Compare renderers|*.renderer;*.renderer3;*.zip|All files|*.*"};if(dlg.ShowDialog(this)!=true)return;
        try{var candidate=RendererBundleReader.Inspect(dlg.FileName);var dialog=new RendererImportDialog(candidate,dlg.FileName,_rendererStore){Owner=this};if(dialog.ShowDialog()==true&&dialog.RendererActivated){ReloadRenderer();_status.Text="Renderer active: "+_renderer.Name;}}catch(Exception ex){Error("Import renderer",ex);}
    }
    private void OpenRendererLibrary(){var window=new RendererLibraryWindow(ReloadRenderer){Owner=this};window.ShowDialog();ReloadRenderer();}
    private void ReloadRenderer(){_renderer=_rendererStore.Active();RefreshRendererLabel();RefreshTimeline();RefreshPreview();UpdateProjectInfo();}

    private async void ExportVideo()
    {
        CommitFields(); if(_project.IntroMode==IntroMode.Custom&&!File.Exists(_project.IntroVideo)){Error("Export",new InvalidOperationException("Choose a custom MP4 intro or switch the intro mode."));return;}
        var dlg=new SaveFileDialog{Filter="MP4 video|*.mp4",FileName="Cubical-Compare-"+SafeFileName(_project.Name)+".mp4"};if(dlg.ShowDialog(this)!=true)return;
        _exportCts?.Dispose();_exportCts=new CancellationTokenSource();_progress.Value=0;_progress.Visibility=Visibility.Visible;_cancelExport.Visibility=Visibility.Visible;_exportButton.IsEnabled=false;
        try{var progress=new Progress<(double Progress,string Status)>(v=>{_progress.Value=v.Progress;_status.Text=v.Status;});await new VideoExporter().ExportAsync(_project,_renderer,dlg.FileName,progress,_exportCts.Token);_status.Text="Exported "+Path.GetFileName(dlg.FileName);MessageBox.Show(this,"Video export completed.","Cubical Compare",MessageBoxButton.OK,MessageBoxImage.Information);}
        catch(OperationCanceledException){_status.Text="Export cancelled";}catch(Exception ex){Error("Export",ex);}finally{_progress.Visibility=Visibility.Collapsed;_cancelExport.Visibility=Visibility.Collapsed;_exportButton.IsEnabled=true;}
    }

    private void ChooseImage(){var dlg=new OpenFileDialog{Filter="Images|*.png;*.jpg;*.jpeg;*.webp;*.bmp|All files|*.*"};if(dlg.ShowDialog(this)==true)_image.Text=dlg.FileName;}
    private void RemoveImage(){_image.Text="";ResetTransform();}
    private void ChooseIntro(){var dlg=new OpenFileDialog{Filter="MP4 video|*.mp4|Video files|*.mp4;*.mov;*.mkv;*.webm|All files|*.*"};if(dlg.ShowDialog(this)==true){_introVideo.Text=dlg.FileName;_introMode.SelectedIndex=1;}}
    private void ChooseSoundtrack(){var dlg=new OpenFileDialog{Filter="Audio|*.mp3;*.wav;*.m4a;*.aac;*.flac;*.ogg|All files|*.*"};if(dlg.ShowDialog(this)==true)_soundtrack.Text=dlg.FileName;}
    private void ChooseFont(){var dlg=new OpenFileDialog{Filter="Fonts|*.ttf;*.otf|All files|*.*"};if(dlg.ShowDialog(this)!=true)return;try{using var typeface=SKTypeface.FromFile(dlg.FileName);if(typeface==null)throw new InvalidDataException("The selected file is not a readable TTF/OTF font.");_fontFile.Text=dlg.FileName;}catch(Exception ex){Error("Choose font",ex);}}

    private void ResetTransform(){if(_project.Cards.Count==0)return;_loading=true;try{_imageScale.Value=1;_imageX.Value=0;_imageY.Value=0;_imageRotation.Value=0;_cropLeft.Value=0;_cropTop.Value=0;_cropRight.Value=0;_cropBottom.Value=0;}finally{_loading=false;}CommitFields();SchedulePreview();ScheduleAutosave();}
    private void ApplyTransformOnward()
    {
        if(_selectedIndex<0||_selectedIndex>=_project.Cards.Count)return;CommitFields();var source=_project.Cards[_selectedIndex];for(var i=_selectedIndex+1;i<_project.Cards.Count;i++){var target=_project.Cards[i];target.ImageX=source.ImageX;target.ImageY=source.ImageY;target.ImageScale=source.ImageScale;target.ImageRotation=source.ImageRotation;target.ImageCropLeft=source.ImageCropLeft;target.ImageCropTop=source.ImageCropTop;target.ImageCropRight=source.ImageCropRight;target.ImageCropBottom=source.ImageCropBottom;target.ImageLayer=source.ImageLayer;}ScheduleAutosave();SchedulePreview();_status.Text=$"Transform applied from card {_selectedIndex+1} onward";
    }

    private void OnPreviewMouseDown(object sender,MouseButtonEventArgs e)
    {
        if(_directTransform.IsChecked!=true||e.ChangedButton!=MouseButton.Left)return;var point=ReferencePoint(e.GetPosition(_preview));if(point==null)return;var hit=HitTestCard((int)_timeline.Value,point.Value.X,point.Value.Y);if(hit>=0&&hit<_project.Cards.Count&&!string.IsNullOrWhiteSpace(_project.Cards[hit].Image)){CommitFields();_selectedIndex=hit;_cards.SelectedIndex=hit;LoadFields();}
        if(_selectedIndex<0||_selectedIndex>=_project.Cards.Count||string.IsNullOrWhiteSpace(_project.Cards[_selectedIndex].Image))return;_dragStart=e.GetPosition(_preview);_dragOrigin=CloneCard(_project.Cards[_selectedIndex],false);_preview.CaptureMouse();e.Handled=true;
    }
    private void OnPreviewMouseMove(object sender,MouseEventArgs e)
    {
        if(_dragStart==null||_dragOrigin==null||e.LeftButton!=MouseButtonState.Pressed)return;var current=e.GetPosition(_preview);var rw=Math.Max(1,_renderer.ReferenceWidth);var rh=Math.Max(1,_renderer.ReferenceHeight);var displayScale=Math.Max(.000001,Math.Min(_preview.ActualWidth/rw,_preview.ActualHeight/rh));var dx=(current.X-_dragStart.Value.X)/displayScale;var dy=(current.Y-_dragStart.Value.Y)/displayScale;var card=_project.Cards[_selectedIndex];var x=Math.Clamp(_dragOrigin.ImageX+dx,-2400,2400);var y=Math.Clamp(_dragOrigin.ImageY+dy,-2400,2400);var snap=14.0;_verticalGuide.Visibility=Math.Abs(x)<=snap?Visibility.Visible:Visibility.Collapsed;_horizontalGuide.Visibility=Math.Abs(y)<=snap?Visibility.Visible:Visibility.Collapsed;if(Math.Abs(x)<=snap)x=0;if(Math.Abs(y)<=snap)y=0;card.ImageX=x;card.ImageY=y;SyncTransformControlsFromCard(card);RenderPreview((int)_timeline.Value);_saveState.Text="Saving";
    }
    private void OnPreviewMouseUp(object sender,MouseButtonEventArgs e){if(_dragStart==null)return;_dragStart=null;_dragOrigin=null;_preview.ReleaseMouseCapture();_verticalGuide.Visibility=Visibility.Collapsed;_horizontalGuide.Visibility=Visibility.Collapsed;ScheduleAutosave();e.Handled=true;}
    private void OnPreviewMouseWheel(object sender,MouseWheelEventArgs e)
    {
        if(_directTransform.IsChecked!=true||_selectedIndex<0||_selectedIndex>=_project.Cards.Count)return;var card=_project.Cards[_selectedIndex];if(string.IsNullOrWhiteSpace(card.Image))return;if((Keyboard.Modifiers&ModifierKeys.Alt)!=0){card.ImageRotation=Math.Clamp(card.ImageRotation+(e.Delta>0?2:-2),-180,180);}else{var factor=e.Delta>0?1.06:1/1.06;card.ImageScale=Math.Clamp(card.ImageScale*factor,.05,12);}SyncTransformControlsFromCard(card);RenderPreview((int)_timeline.Value);ScheduleAutosave();e.Handled=true;
    }

    private Point? ReferencePoint(Point viewPoint)
    {
        var vw=_preview.ActualWidth;var vh=_preview.ActualHeight;if(vw<=0||vh<=0)return null;var rw=Math.Max(1,_renderer.ReferenceWidth);var rh=Math.Max(1,_renderer.ReferenceHeight);var scale=Math.Min(vw/rw,vh/rh);var dw=rw*scale;var dh=rh*scale;var ox=(vw-dw)/2;var oy=(vh-dh)/2;if(viewPoint.X<ox||viewPoint.X>ox+dw||viewPoint.Y<oy||viewPoint.Y>oy+dh)return null;return new Point((viewPoint.X-ox)/scale,(viewPoint.Y-oy)/scale);
    }

    private int HitTestCard(int frame,double x,double y)
    {
        if(_project.Cards.Count==0)return -1;var mapped=TimelineToEngineFrame(frame);if(mapped==null)return -1;frame=mapped.Value;
        if(_renderer.Engine=="scene-v3"&&_renderer.SceneV3!=null)
        {
            foreach(var obj in _renderer.SceneV3.Objects.AsEnumerable().Reverse())
            {
                if(frame<obj.LifespanStart||frame>obj.LifespanEnd||obj.Kind is not ("openingCard" or "card"))continue;var index=V3CardIndex(obj);if(index<0||index>=_project.Cards.Count)continue;var props=V3Evaluator.Properties(_renderer.SceneV3,obj,frame);_renderer.SceneV3.Resources.TryGetValue(obj.Resource??"",out var res);var w=JsonNumber(props,"width",res.Double("width",470));var h=JsonNumber(props,"height",res.Double("height",1080));var px=JsonNumber(props,"x",res.Double("x",0))+JsonNumber(props,"transform.translateX",0);var py=JsonNumber(props,"y",res.Double("y",0))+JsonNumber(props,"transform.translateY",0);if(x>=px&&x<=px+w&&y>=py&&y<=py+h)return index;
            }
        }
        var positions=LegacyPositions(frame);foreach(var pair in positions){var left=pair.Value+_renderer.BodyInset;if(x>=left&&x<=left+_renderer.BodyWidth&&y>=0&&y<=_renderer.ReferenceHeight)return pair.Key;}return _selectedIndex;
    }
    private Dictionary<int,double> LegacyPositions(int frame)
    {
        var result=new Dictionary<int,double>();
        if(_renderer.Engine=="ribbon-exact")
        {
            if(frame>=_renderer.ContinuousStartFrame&&_project.Cards.Count>4){var segment=(frame-_renderer.ContinuousStartFrame)/512;var scroll=_renderer.Track($"ribbon.scroll.{segment}",frame)??((frame-_renderer.ContinuousStartFrame)/(double)Math.Max(1,_renderer.ContinuousStepFrames)*_renderer.SlotPitch);for(var i=0;i<_project.Cards.Count;i++){var px=i*_renderer.SlotPitch-scroll;if(px>-_renderer.SlotPitch&&px<_renderer.ReferenceWidth+_renderer.SlotPitch)result[i]=px;}return result;}
            var active=-1;for(var i=0;i<Math.Min(4,_project.Cards.Count);i++)if(frame>=_renderer.OpeningStarts.ElementAtOrDefault(i))active=i;if(active<0)return result;for(var i=0;i<=active;i++)result[i]=i*_renderer.SlotPitch;return result;
        }
        if(_renderer.Engine=="relationships-exact")
        {
            if(frame<_renderer.ContinuousStartFrame){for(var i=0;i<Math.Min(4,_project.Cards.Count);i++){var start=i<_renderer.OpeningStarts.Count?_renderer.OpeningStarts[i]:384+i*140;if(frame>=start)result[i]=i*_renderer.SlotPitch;}return result;}
            var segment=(frame-_renderer.ContinuousStartFrame)/4096;var scroll=_renderer.Track($"relationships.scroll.{segment}",frame)??((frame-_renderer.ContinuousStartFrame)*2.0);for(var i=0;i<_project.Cards.Count;i++){var px=i*_renderer.SlotPitch-scroll;if(px>-_renderer.SlotPitch*2&&px<_renderer.ReferenceWidth+_renderer.SlotPitch*2)result[i]=px;}return result;
        }
        if(_renderer.Engine=="infinite-timeline-exact")
        {
            if(frame<_renderer.ContinuousStartFrame){for(var i=0;i<Math.Min(4,_project.Cards.Count);i++){var start=i<_renderer.OpeningStarts.Count?_renderer.OpeningStarts[i]:new[]{187,261,329,398}[i];if(frame>=start)result[i]=i*480.0;}return result;}
            var scroll=_renderer.Track("infinite.scroll",frame)??InfinitePreviewScroll(frame,_renderer.ContinuousStartFrame);for(var i=0;i<_project.Cards.Count;i++){var px=i*483.0-scroll;if(px>-483&&px<_renderer.ReferenceWidth+483)result[i]=px;}return result;
        }
        var step=Math.Max(1,_renderer.ContinuousStepFrames);var standard=frame/(double)step*_renderer.SlotPitch;for(var i=0;i<_project.Cards.Count;i++){var px=i*_renderer.SlotPitch-standard;if(px>-_renderer.SlotPitch&&px<_renderer.ReferenceWidth+_renderer.SlotPitch)result[i]=px;}return result;
    }
    private static double InfinitePreviewScroll(int frame,int start){const double speed=3.2065854,fast=24;const int fastStart=5265;if(frame<=fastStart)return Math.Max(0,frame-start)*speed;var atFast=Math.Max(0,fastStart-start)*speed;return atFast+(frame-fastStart)*fast;}
    private static int V3CardIndex(RendererObjectV3 obj){if(obj.Raw.ValueKind==JsonValueKind.Object){if(obj.Raw.TryGetProperty("cardIndex",out var c)&&c.TryGetInt32(out var ci))return ci;if(obj.Raw.TryGetProperty("dataIndex",out var d)&&d.TryGetInt32(out var di))return di;}var at=obj.Id.LastIndexOf('@');return at>=0&&int.TryParse(obj.Id[(at+1)..],out var parsed)?parsed:-1;}
    private static double JsonNumber(Dictionary<string,object?> props,string key,double fallback){if(!props.TryGetValue(key,out var value)||value==null)return fallback;return value is IConvertible c?Convert.ToDouble(c,CultureInfo.InvariantCulture):fallback;}

    private void TogglePlay(){_playing=!_playing;if(_playing){UpdatePlayTimer();_playTimer.Start();}else _playTimer.Stop();}
    private void UpdatePlayTimer(){var fps=Math.Max(1,_renderer.PrecisionMode=="frame-exact"?_renderer.ReferenceFps:_project.Fps);_playTimer.Interval=TimeSpan.FromMilliseconds(Math.Max(2,1000.0/(fps*_playbackSpeed)));}
    private void StepFrame(int delta){_playing=false;_playTimer.Stop();_timeline.Value=Math.Clamp(_timeline.Value+delta,0,_timeline.Maximum);}

    private void RefreshAll(){_loading=true;try{RefreshCardList();LoadFields();RefreshRendererLabel();RefreshTimeline();}finally{_loading=false;}RefreshPreview();UpdateTitle();UpdateProjectInfo();_ = UpdateEncoderLabelAsync();}
    private void RefreshCardList(){_loading=true;try{_cards.ItemsSource=null;_cards.ItemsSource=_project.Cards;_selectedIndex=Math.Clamp(_selectedIndex,0,Math.Max(0,_project.Cards.Count-1));_cards.SelectedIndex=_selectedIndex;}finally{_loading=false;}}
    private void LoadFields()
    {
        _loading=true;try
        {
            var card=_project.Cards[Math.Clamp(_selectedIndex,0,_project.Cards.Count-1)];_projectName.Text=_project.Name;_title.Text=card.Title;_value.Text=card.Value;_badgeHeader.Text=card.BadgeHeader;_description.Text=card.Description;_image.Text=card.Image;SyncTransformControlsFromCard(card);
            _introMode.SelectedIndex=_project.IntroMode switch{IntroMode.Custom=>1,IntroMode.Disabled=>2,_=>0};_introVideo.Text=_project.IntroVideo;_soundtrack.Text=_project.Soundtrack;_soundtrackVolume.Value=_project.SoundtrackVolume;_soundtrackLoop.IsChecked=_project.SoundtrackLoop;_badges.IsChecked=_project.ShowBadges;_credits.IsChecked=_project.CreditsEnabled;_autoLength.IsChecked=_project.AutoLength;_length.Text=FormatDuration(_project.CustomLengthSeconds);_fontFamily.SelectedIndex=_project.FontFamily switch{"sans-serif"=>1,"sans-serif-condensed"=>2,"serif"=>3,"monospace"=>4,_=>0};_fontFile.Text=_project.FontFile;_codec.SelectedIndex=_project.EncoderPreference switch{EncoderPreference.H264=>1,EncoderPreference.H265=>2,_=>0};
        }finally{_loading=false;}UpdateProjectInfo();
    }
    private void SyncTransformControlsFromCard(StudioCard card){_loading=true;try{_imageScale.Value=Math.Clamp(card.ImageScale,_imageScale.Minimum,_imageScale.Maximum);_imageX.Value=Math.Clamp(card.ImageX,_imageX.Minimum,_imageX.Maximum);_imageY.Value=Math.Clamp(card.ImageY,_imageY.Minimum,_imageY.Maximum);_imageRotation.Value=Math.Clamp(card.ImageRotation,_imageRotation.Minimum,_imageRotation.Maximum);_cropLeft.Value=Math.Clamp(card.ImageCropLeft,0,.95);_cropTop.Value=Math.Clamp(card.ImageCropTop,0,.95);_cropRight.Value=Math.Clamp(card.ImageCropRight,0,.95);_cropBottom.Value=Math.Clamp(card.ImageCropBottom,0,.95);_imageLayer.SelectedIndex=card.ImageLayer.Equals("front",StringComparison.OrdinalIgnoreCase)?1:0;}finally{_loading=false;}}

    private void CommitFields()
    {
        if(_loading||_project.Cards.Count==0)return;var card=_project.Cards[Math.Clamp(_selectedIndex,0,_project.Cards.Count-1)];_project.Name=_projectName.Text;card.Title=_title.Text;card.Value=_value.Text;card.BadgeHeader=_badgeHeader.Text;card.Description=_description.Text;card.Image=_image.Text;card.ImageScale=_imageScale.Value;card.ImageX=_imageX.Value;card.ImageY=_imageY.Value;card.ImageRotation=_imageRotation.Value;card.ImageCropLeft=_cropLeft.Value;card.ImageCropTop=_cropTop.Value;card.ImageCropRight=_cropRight.Value;card.ImageCropBottom=_cropBottom.Value;card.ImageLayer=_imageLayer.SelectedIndex==1?"front":"behind";
        _project.IntroMode=_introMode.SelectedIndex switch{1=>IntroMode.Custom,2=>IntroMode.Disabled,_=>IntroMode.Renderer};_project.IntroVideo=_introVideo.Text;_project.Soundtrack=_soundtrack.Text;_project.SoundtrackVolume=(float)_soundtrackVolume.Value;_project.SoundtrackLoop=_soundtrackLoop.IsChecked==true;_project.ShowBadges=_badges.IsChecked==true;_project.CreditsEnabled=_credits.IsChecked==true;_project.AutoLength=_autoLength.IsChecked==true;if(TryParseDuration(_length.Text,out var seconds)&&seconds>0)_project.CustomLengthSeconds=Math.Max(15,seconds);_project.FontFamily=_fontFamily.SelectedIndex switch{1=>"sans-serif",2=>"sans-serif-condensed",3=>"serif",4=>"monospace",_=>""};_project.FontFile=_fontFile.Text;_project.EncoderPreference=_codec.SelectedIndex switch{1=>EncoderPreference.H264,2=>EncoderPreference.H265,_=>EncoderPreference.Auto};
        RefreshCardNames();UpdateTitle();UpdateProjectInfo();
    }
    private void RefreshCardNames(){if(_cards.Items.Count==_project.Cards.Count)_cards.Items.Refresh();}
    private void RefreshRendererLabel()=>_rendererLabel.Text=$"{_renderer.Name} • API {_renderer.RendererApi} • {_renderer.ReferenceWidth}×{_renderer.ReferenceHeight}@{_renderer.ReferenceFps}";
    private void RefreshTimeline(){if(!_loading)CommitFields();var count=Math.Max(1,FrameCount());_timeline.Maximum=count-1;if(_timeline.Value>_timeline.Maximum)_timeline.Value=_timeline.Maximum;UpdateFrameLabel();}
    private int FrameCount(){var baseFrames=Math.Max(1,_engine.FrameCount(_project,_renderer));var intro=Math.Min(RendererIntroFrames(),Math.Max(0,baseFrames-1));var fps=OutputFps();return _project.IntroMode switch{IntroMode.Renderer=>baseFrames,IntroMode.Disabled=>Math.Max(1,baseFrames-intro),IntroMode.Custom=>Math.Max(1,CustomIntroFrames(fps)+baseFrames-intro),_=>baseFrames};}
    private int RendererIntroFrames()=>_renderer.RendererApi>=3||_renderer.Engine=="scene-v3"?0:Math.Max(0,_renderer.OpeningStarts.FirstOrDefault());
    private int OutputFps()=>Math.Max(1,_renderer.PrecisionMode=="frame-exact"?_renderer.ReferenceFps:_project.Fps);
    private int CustomIntroFrames(int fps)=>_project.IntroMode==IntroMode.Custom&&!string.IsNullOrWhiteSpace(_project.IntroVideo)?_introSource.FrameCount(_project.IntroVideo,fps):0;
    private int? TimelineToEngineFrame(int frame){var intro=RendererIntroFrames();if(_project.IntroMode==IntroMode.Renderer)return Math.Max(0,frame);if(_project.IntroMode==IntroMode.Disabled)return Math.Max(0,frame+intro);var custom=CustomIntroFrames(OutputFps());if(frame<custom)return null;return Math.Max(0,frame-custom+intro);}
    private void SchedulePreview(){_previewDebounce.Stop();_previewDebounce.Start();}
    private void ScheduleAutosave(){_saveState.Text="Saving";_autosaveDebounce.Stop();_autosaveDebounce.Start();}
    private void RefreshPreview()=>RenderPreview((int)Math.Round(_timeline.Value));
    private void RenderPreview(int frame)
    {
        if(_project.Cards.Count==0)return;try{var width=Math.Clamp((int)Math.Round(Math.Max(640,_preview.ActualWidth*1.4)),640,1280);var height=Math.Max(2,(int)Math.Round(width*_renderer.ReferenceHeight/(double)Math.Max(1,_renderer.ReferenceWidth)));var engineFrame=TimelineToEngineFrame(frame);using var bitmap=engineFrame==null?_introSource.Render(_project.IntroVideo,frame,OutputFps(),width,height):_engine.Render(_project,_renderer,engineFrame.Value,width,height);_preview.Source=ToBitmapSource(bitmap);UpdateFrameLabel();}catch(Exception ex){_status.Text="Preview: "+ex.Message;}
    }
    private void UpdateFrameLabel(){var fps=OutputFps();var frame=(int)Math.Round(_timeline.Value);_frameLabel.Text=$"Frame {frame+1:N0} / {(int)_timeline.Maximum+1:N0}";_timeLabel.Text=$"{FormatDuration(frame/(double)fps)} / {FormatDuration(((int)_timeline.Maximum+1)/(double)fps)} · {fps} FPS";}
    private void UpdateTitle()=>Title=$"{_project.Name} — Cubical Compare 3.0.300 Windows (.NET)";
    private void UpdateProjectInfo(){var outW=_renderer.PrecisionMode=="frame-exact"?_renderer.ReferenceWidth:_project.Width;var outH=_renderer.PrecisionMode=="frame-exact"?_renderer.ReferenceHeight:_project.Height;var fps=_renderer.PrecisionMode=="frame-exact"?_renderer.ReferenceFps:_project.Fps;_outputLabel.Text=$"Output: {outW}×{outH} · {fps} FPS · {FrameCount():N0} frames";var issues=new List<string>();if(_renderer.PrecisionMode=="frame-exact"){if(_project.Width!=_renderer.ReferenceWidth||_project.Height!=_renderer.ReferenceHeight)issues.Add("resolution");if(_project.Fps!=_renderer.ReferenceFps)issues.Add("frame rate");if(_renderer.CanonicalCardCount>0&&_project.Cards.Count!=_renderer.CanonicalCardCount)issues.Add("card count");if(!_project.AutoLength)issues.Add("duration");if(_project.FontFamily.Length>0||_project.FontFile.Length>0)issues.Add("font");}_accuracyLabel.Text=_renderer.PrecisionMode!="frame-exact"?"Adaptive":issues.Count==0?(_project.IntroMode==IntroMode.Renderer?"Pixel exact":"Comparison exact"):"Modified: "+string.Join(", ",issues);}
    private async Task UpdateEncoderLabelAsync(){var preference=_codec.SelectedIndex switch{1=>EncoderPreference.H264,2=>EncoderPreference.H265,_=>EncoderPreference.Auto};try{var description=await Task.Run(()=>HardwareEncoderSelector.Describe(preference));if(!Dispatcher.HasShutdownStarted)_encoderLabel.Text="Encoder: "+description;}catch(Exception ex){_encoderLabel.Text="Encoder: "+ex.Message;}}

    private static Slider Slider(double min,double max,double tick)=>new(){Minimum=min,Maximum=max,TickFrequency=tick,IsSnapToTickEnabled=false};
    private static void Section(StackPanel parent,string title){parent.Children.Add(new Separator{Margin=new Thickness(0,12,0,10)});parent.Children.Add(new TextBlock{Text=title,FontSize=18,FontWeight=FontWeights.SemiBold,Margin=new Thickness(0,0,0,4)});}
    private static StackPanel AddField(StackPanel parent,string label,TextBox box,double height=double.NaN){parent.Children.Add(Label(label));var row=new StackPanel{Orientation=Orientation.Horizontal};parent.Children.Add(row);box.MinWidth=180;box.HorizontalAlignment=HorizontalAlignment.Stretch;box.Background=new SolidColorBrush(Color.FromRgb(38,38,40));box.Foreground=Brushes.White;box.BorderBrush=new SolidColorBrush(Color.FromRgb(80,80,82));box.Padding=new Thickness(7,5,7,5);if(!double.IsNaN(height))box.Height=height;row.Children.Add(box);return row;}
    private static FrameworkElement AddLabeledControl(StackPanel parent,string label,Control control){parent.Children.Add(Label(label));control.MinWidth=180;control.Margin=new Thickness(0,0,0,3);parent.Children.Add(control);return control;}
    private static void AddTransformControl(StackPanel parent,string label,Slider slider,Func<string> display){var head=new DockPanel{Margin=new Thickness(0,5,0,0)};var value=new TextBlock{Foreground=new SolidColorBrush(Color.FromRgb(175,175,175))};DockPanel.SetDock(value,Dock.Right);head.Children.Add(value);head.Children.Add(Label(label));parent.Children.Add(head);parent.Children.Add(slider);void Update()=>value.Text=display();slider.ValueChanged+=(_,_)=>Update();Update();}
    private static TextBlock Label(string text)=>new(){Text=text,Foreground=new SolidColorBrush(Color.FromRgb(190,190,190)),Margin=new Thickness(0,6,0,3)};
    private static Button ActionButton(string text,Action action){var button=new Button{Content=text,Margin=new Thickness(0,0,6,4),Padding=new Thickness(11,5,11,5),MinWidth=58};button.Click+=(_,_)=>action();return button;}
    private static Button SmallButton(string text,Action action){var button=new Button{Content=text,Margin=new Thickness(0,0,6,3),Padding=new Thickness(8,4,8,4)};button.Click+=(_,_)=>action();return button;}
    private static Separator Separator()=>new(){Width=1,Height=25,Margin=new Thickness(4,0,10,4),Background=new SolidColorBrush(Color.FromRgb(75,75,78))};
    private static BitmapSource ToBitmapSource(SKBitmap bitmap){using var pixmap=bitmap.PeekPixels();var size=checked(pixmap.RowBytes*pixmap.Height);var pixels=new byte[size];Marshal.Copy(pixmap.GetPixels(),pixels,0,size);var source=BitmapSource.Create(bitmap.Width,bitmap.Height,96,96,PixelFormats.Bgra32,null,pixels,pixmap.RowBytes);source.Freeze();return source;}
    private static string SafeFileName(string name){var invalid=Path.GetInvalidFileNameChars();var safe=new string((string.IsNullOrWhiteSpace(name)?"Untitled":name).Select(c=>invalid.Contains(c)?'_':c).ToArray());return safe.Trim();}
    private static string FormatDuration(double seconds){seconds=Math.Max(0,seconds);var minutes=(int)(seconds/60);var remain=seconds-minutes*60;return $"{minutes:00}:{remain:00.000}";}
    private static bool TryParseDuration(string text,out double seconds){seconds=0;var parts=text.Trim().Split(':');if(parts.Length==1)return double.TryParse(parts[0],NumberStyles.Float,CultureInfo.InvariantCulture,out seconds)&&seconds>0;if(parts.Length==2&&int.TryParse(parts[0],NumberStyles.Integer,CultureInfo.InvariantCulture,out var minutes)&&double.TryParse(parts[1],NumberStyles.Float,CultureInfo.InvariantCulture,out var remain)&&minutes>=0&&remain>=0&&remain<60){seconds=minutes*60+remain;return seconds>0;}return false;}
    private void Error(string title,Exception ex){_status.Text=title+" failed";MessageBox.Show(this,ex.Message,title,MessageBoxButton.OK,MessageBoxImage.Error);}
}