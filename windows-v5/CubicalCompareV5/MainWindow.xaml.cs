using Microsoft.Win32;
using OpenFileDialog = Microsoft.Win32.OpenFileDialog;
using SaveFileDialog = Microsoft.Win32.SaveFileDialog;
using Brush = System.Windows.Media.Brush;
using Color = System.Windows.Media.Color;
using ColorConverter = System.Windows.Media.ColorConverter;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Ink;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace CubicalCompareV5;

public partial class MainWindow : Window
{
    private const int FramesPerSecond = 30;
    private const int SecondsPerCard = 2;
    private readonly JsonSerializerOptions _json = new() { WriteIndented = true };
    private ObservableCollection<ComparisonCard> _cards = new();
    private ComparisonCard? _active;
    private bool _syncing = true;
    private bool _drawing;
    private bool _exporting;
    private string? _projectPath;
    private AppSettings _settings = new();
    private static readonly string SettingsFile = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "CubicalCompareV5", "settings.json");

    private static readonly string[] LanguageCodes = ["en", "pt", "es", "fr", "de", "ru", "ja", "zh", "it", "uk"];
    private static readonly string[] LanguageNames =
        ["English", "Português", "Español", "Français", "Deutsch", "Русский", "日本語", "中文", "Italiano", "Українська"];

    // Text resources are independent of Windows UI language and may be switched live.
    private static readonly Dictionary<string, string[]> Strings = new()
    {
        ["cards"] = ["Cards","Cartões","Tarjetas","Cartes","Karten","Карточки","カード","卡片","Schede","Картки"],
        ["preview"] = ["Live preview","Pré-visualização","Vista previa","Aperçu","Vorschau","Предпросмотр","プレビュー","实时预览","Anteprima","Перегляд"],
        ["editor"] = ["Card editor","Editor de cartão","Editor de tarjeta","Éditeur de carte","Karteneditor","Редактор карточки","カード編集","卡片编辑","Modifica scheda","Редактор картки"],
        ["new"] = ["New","Novo","Nuevo","Nouveau","Neu","Новый","新規","新建","Nuovo","Новий"],
        ["open"] = ["Open","Abrir","Abrir","Ouvrir","Öffnen","Открыть","開く","打开","Apri","Відкрити"],
        ["save"] = ["Save","Guardar","Guardar","Enregistrer","Speichern","Сохранить","保存","保存","Salva","Зберегти"],
        ["import"] = ["Import CSV","Importar CSV","Importar CSV","Importer CSV","CSV importieren","Импорт CSV","CSV読込","导入CSV","Importa CSV","Імпорт CSV"],
        ["export"] = ["Export MP4","Exportar MP4","Exportar MP4","Exporter MP4","MP4 exportieren","Экспорт MP4","MP4出力","导出MP4","Esporta MP4","Експорт MP4"],
        ["draw"] = ["Draw","Desenhar","Dibujar","Dessiner","Zeichnen","Рисовать","描画","绘画","Disegna","Малювати"],
        ["eraser"] = ["Eraser","Borracha","Borrador","Gomme","Radierer","Ластик","消しゴム","橡皮擦","Gomma","Гумка"],
        ["clear"] = ["Clear ink","Limpar","Borrar tinta","Effacer","Löschen","Очистить","消去","清除笔迹","Cancella","Очистити"],
        ["title"] = ["Title","Título","Título","Titre","Titel","Заголовок","タイトル","标题","Titolo","Назва"],
        ["header"] = ["Badge header","Título do selo","Encabezado","En-tête badge","Abzeichenkopf","Заголовок значка","バッジ見出し","徽章标题","Intestazione","Заголовок значка"],
        ["value"] = ["Badge value","Valor do selo","Valor","Valeur","Abzeichenwert","Значение","バッジ値","徽章数值","Valore","Значення"],
        ["unit"] = ["Badge unit","Unidade","Unidad","Unité","Einheit","Единица","単位","单位","Unità","Одиниця"],
        ["description"] = ["Description","Descrição","Descripción","Description","Beschreibung","Описание","説明","描述","Descrizione","Опис"],
        ["colour"] = ["Badge colour","Cor do selo","Color de insignia","Couleur du badge","Abzeichenfarbe","Цвет значка","バッジ色","徽章颜色","Colore badge","Колір значка"],
        ["pick"] = ["Choose badge colour","Escolher cor","Elegir color","Choisir une couleur","Farbe wählen","Выбрать цвет","色を選択","选择颜色","Scegli colore","Обрати колір"],
        ["art"] = ["Artwork","Ilustração","Ilustración","Illustration","Bildmaterial","Иллюстрация","画像","插画","Illustrazione","Зображення"],
        ["artimport"] = ["Import image...","Importar imagem...","Importar imagen...","Importer image...","Bild importieren...","Добавить изображение...","画像を追加...","导入图片...","Importa immagine...","Додати зображення..."],
        ["artremove"] = ["Remove image","Remover imagem","Eliminar imagen","Retirer l'image","Bild entfernen","Удалить изображение","画像を削除","移除图片","Rimuovi immagine","Прибрати зображення"],
        ["settings"] = ["Preferences","Preferências","Preferencias","Préférences","Einstellungen","Настройки","設定","偏好设置","Preferenze","Налаштування"],
        ["language"] = ["Language","Idioma","Idioma","Langue","Sprache","Язык","言語","语言","Lingua","Мова"],
        ["theme"] = ["Appearance","Aparência","Apariencia","Apparence","Darstellung","Тема","外観","外观","Aspetto","Вигляд"],
        ["render"] = ["Preview rendering","Renderização","Renderizado","Rendu aperçu","Vorschau-Rendering","Отрисовка","プレビュー描画","预览渲染","Rendering","Відображення"],
        ["cpu"] = ["Software rendering (old GPU compatibility)","Renderização por software (GPU antiga)","Renderizado por software (GPU antigua)","Rendu logiciel (ancien GPU)","Software-Rendering (alte GPU)","Программный рендеринг (старые GPU)","ソフトウェア描画 (旧GPU)","软件渲染（旧显卡）","Rendering software (vecchie GPU)","Програмне відтворення (старі GPU)"],
    };

    public MainWindow()
    {
        InitializeComponent();
        LoadSettings();
        // Start with CPU rendering on low-tier adapters unless the user already chose a mode.
        // Users may override the choice; their saved setting takes priority next launch.
        if (!File.Exists(SettingsFile) && (RenderCapability.Tier >> 16) < 2)
            _settings.SoftwareRendering = true;
        _cards = new ObservableCollection<ComparisonCard>();
        CardList.ItemsSource = _cards;
        LanguageCombo.ItemsSource = LanguageNames;
        ThemeCombo.ItemsSource = new[] { "System", "Dark", "Light" };
        LanguageCombo.SelectedIndex = Math.Max(0, Array.IndexOf(LanguageCodes, _settings.Language));
        ThemeCombo.SelectedItem = _settings.Theme;
        CpuRendering.IsChecked = _settings.SoftwareRendering;
        ApplyTheme();
        ApplyLanguage();
        SetSoftwareRendering(_settings.SoftwareRendering);
        Sketch.DefaultDrawingAttributes = new DrawingAttributes
        {
            Color = Colors.DarkSlateBlue, Width = 5.5, Height = 5.5, FitToCurve = true
        };
        AddCard(new ComparisonCard { Title = "First comparison", Badge = "1", BadgeHeader = "Rank", Description = "Start your comparison here." });
        _syncing = false;
        CardList.SelectedIndex = -1;
        CardList.SelectedIndex = 0;
        Loaded += (_, _) => ApplyWindowBackdrop();
        SystemEvents.UserPreferenceChanged += OnSystemPreferenceChanged;
        Closed += (_, _) => SystemEvents.UserPreferenceChanged -= OnSystemPreferenceChanged;
    }

    private string T(string key)
    {
        if (!Strings.TryGetValue(key, out var values)) return key;
        int index = Math.Max(0, Array.IndexOf(LanguageCodes, _settings.Language));
        return values[index];
    }

    private void ApplyLanguage()
    {
        CardsHeading.Text = T("cards");
        PreviewHeading.Text = T("preview");
        EditorHeading.Text = T("editor");
        NewButton.Content = T("new");
        OpenButton.Content = T("open");
        SaveButton.Content = T("save");
        ImportButton.Content = T("import");
        ExportButton.Content = T("export");
        DrawButton.Content = "✎ " + T("draw");
        EraseButton.Content = T("eraser");
        ClearButton.Content = T("clear");
        TitleLabel.Text = T("title");
        BadgeHeaderLabel.Text = T("header");
        BadgeValueLabel.Text = T("value");
        BadgeUnitLabel.Text = T("unit");
        DescriptionLabel.Text = T("description");
        BadgeColorLabel.Text = T("colour");
        ColorButton.Content = T("pick");
        ArtworkLabel.Text = T("art");
        ArtworkButton.Content = T("artimport");
        RemoveArtworkButton.Content = T("artremove");
        PreferencesHeading.Text = T("settings");
        LanguageLabel.Text = T("language");
        ThemeLabel.Text = T("theme");
        RenderLabel.Text = T("render");
        CpuRendering.Content = T("cpu");
    }

    private void SetSoftwareRendering(bool enabled)
    {
        RenderOptions.ProcessRenderMode = enabled ? RenderMode.SoftwareOnly : RenderMode.Default;
        // Keep drawing and video capture CPU-side even when WPF hardware acceleration is available.
    }

    private bool EffectiveDark()
    {
        if (_settings.Theme == "Dark") return true;
        if (_settings.Theme == "Light") return false;
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            return Convert.ToInt32(key?.GetValue("AppsUseLightTheme", 0), CultureInfo.InvariantCulture) == 0;
        }
        catch { return true; }
    }

    private void ApplyTheme()
    {
        bool dark = EffectiveDark();
        Resources["WindowBrush"] = BrushOf(dark ? "#EA121822" : "#EEF0F4F9");
        Resources["PanelBrush"] = BrushOf(dark ? "#F21D2632" : "#EDFDFEFF");
        Resources["FieldBrush"] = BrushOf(dark ? "#FF293443" : "#FFE1E9F2");
        Resources["TextBrush"] = BrushOf(dark ? "#FFF5F7FC" : "#FF172435");
        Resources["MutedBrush"] = BrushOf(dark ? "#FFAAB6C5" : "#FF536476");
        ApplyWindowBackdrop();
    }

    private static SolidColorBrush BrushOf(string value) =>
        new((Color)ColorConverter.ConvertFromString(value));

    [DllImport("dwmapi.dll")] private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);
    [DllImport("dwmapi.dll")] private static extern int DwmExtendFrameIntoClientArea(IntPtr hwnd, ref Margins margins);
    [StructLayout(LayoutKind.Sequential)]
    private struct Margins { public int Left, Right, Top, Bottom; }

    private void ApplyWindowBackdrop()
    {
        if (!IsLoaded) return;
        try
        {
            IntPtr hwnd = new WindowInteropHelper(this).Handle;
            int dark = EffectiveDark() ? 1 : 0;
            DwmSetWindowAttribute(hwnd, 20, ref dark, sizeof(int));
            if (Environment.OSVersion.Version.Build >= 22621)
            {
                int mica = 2; // DWMSBT_MAINWINDOW, supported from Windows 11 22H2.
                DwmSetWindowAttribute(hwnd, 38, ref mica, sizeof(int));
                var margins = new Margins { Left = -1, Right = -1, Top = -1, Bottom = -1 };
                DwmExtendFrameIntoClientArea(hwnd, ref margins);
            }
        }
        catch { /* Windows 10, older builds, high contrast, and unsupported DWM are okay. */ }
    }

    private void OnSystemPreferenceChanged(object sender, UserPreferenceChangedEventArgs e) =>
        Dispatcher.BeginInvoke(new Action(() => { if (_settings.Theme == "System") ApplyTheme(); }));

    private void Attach(ComparisonCard card)
    {
        card.PropertyChanged += (_, _) =>
        {
            if (!_syncing && ReferenceEquals(card, _active))
            {
                UpdatePreview(card);
                UpdateTitle();
            }
        };
    }

    private void AddCard(ComparisonCard card)
    {
        Attach(card);
        _cards.Add(card);
        CardList.SelectedItem = card;
    }

    private void PersistInk()
    {
        if (_active is null) return;
        using var data = new MemoryStream();
        Sketch.Strokes.Save(data);
        _active.StrokesBase64 = Convert.ToBase64String(data.ToArray());
    }

    private void LoadInk(ComparisonCard card)
    {
        try
        {
            if (!string.IsNullOrWhiteSpace(card.StrokesBase64))
            {
                using var data = new MemoryStream(Convert.FromBase64String(card.StrokesBase64));
                Sketch.Strokes = new StrokeCollection(data);
            }
            else Sketch.Strokes = new StrokeCollection();
        }
        catch { Sketch.Strokes = new StrokeCollection(); }
        Sketch.Strokes.StrokesChanged += (_, _) => { if (!_syncing) PersistInk(); };
    }

    private void CardList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncing || CardList.SelectedItem is not ComparisonCard card) return;
        PersistInk();
        _active = card;
        _syncing = true;
        TitleEdit.Text = card.Title;
        BadgeHeaderEdit.Text = card.BadgeHeader;
        BadgeEdit.Text = card.Badge;
        BadgeUnitEdit.Text = card.BadgeUnit;
        DescriptionEdit.Text = card.Description;
        LoadInk(card);
        _syncing = false;
        UpdatePreview(card);
    }

    private void EditorChanged(object sender, TextChangedEventArgs e)
    {
        if (_syncing || _active is null) return;
        _active.Title = TitleEdit.Text;
        _active.BadgeHeader = BadgeHeaderEdit.Text;
        _active.Badge = BadgeEdit.Text;
        _active.BadgeUnit = BadgeUnitEdit.Text;
        _active.Description = DescriptionEdit.Text;
        CardList.Items.Refresh();
    }

    private void UpdatePreview(ComparisonCard card)
    {
        PreviewTitle.Text = card.Title;
        PreviewBadgeHeader.Text = card.BadgeHeader;
        PreviewBadge.Text = (card.Badge + " " + card.BadgeUnit).Trim();
        PreviewDescription.Text = card.Description;
        try { BadgePanel.Background = BrushOf(card.BadgeColor); }
        catch { BadgePanel.Background = BrushOf("#EC394D"); }
        try
        {
            if (string.IsNullOrEmpty(card.ArtworkBase64))
            {
                ArtworkImage.Source = null;
                return;
            }
            using var stream = new MemoryStream(Convert.FromBase64String(card.ArtworkBase64));
            var image = new BitmapImage();
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.StreamSource = stream;
            image.EndInit();
            image.Freeze();
            ArtworkImage.Source = image;
        }
        catch { ArtworkImage.Source = null; }
    }

    private void UpdateTitle() =>
        Title = "Cubical Compare V5" + (string.IsNullOrEmpty(_projectPath) ? "" : " — " + Path.GetFileName(_projectPath));

    private void Add_Click(object sender, RoutedEventArgs e) =>
        AddCard(new ComparisonCard { Title = "Card " + (_cards.Count + 1), Badge = (_cards.Count + 1).ToString(CultureInfo.InvariantCulture) });

    private void Remove_Click(object sender, RoutedEventArgs e)
    {
        if (_active is null) return;
        var index = CardList.SelectedIndex;
        _syncing = true;
        _cards.Remove(_active);
        _active = null;
        _syncing = false;
        if (_cards.Count == 0) AddCard(new ComparisonCard());
        else CardList.SelectedIndex = Math.Min(index, _cards.Count - 1);
    }

    private void MoveUp_Click(object sender, RoutedEventArgs e) => MoveCard(-1);
    private void MoveDown_Click(object sender, RoutedEventArgs e) => MoveCard(1);
    private void MoveCard(int offset)
    {
        int index = CardList.SelectedIndex;
        int next = index + offset;
        if (index < 0 || next < 0 || next >= _cards.Count) return;
        _cards.Move(index, next);
        CardList.SelectedIndex = next;
    }

    private void Draw_Click(object sender, RoutedEventArgs e) => SetInkMode(InkCanvasEditingMode.Ink);
    private void Erase_Click(object sender, RoutedEventArgs e) => SetInkMode(InkCanvasEditingMode.EraseByStroke);
    private void SetInkMode(InkCanvasEditingMode mode)
    {
        _drawing = Sketch.EditingMode != mode;
        Sketch.EditingMode = _drawing ? mode : InkCanvasEditingMode.None;
        Sketch.IsHitTestVisible = _drawing;
        DrawButton.Background = _drawing && mode == InkCanvasEditingMode.Ink ? BrushOf("#166B78") : (Brush)Resources["FieldBrush"];
        EraseButton.Background = _drawing && mode == InkCanvasEditingMode.EraseByStroke ? BrushOf("#166B78") : (Brush)Resources["FieldBrush"];
    }
    private void Clear_Click(object sender, RoutedEventArgs e)
    {
        if (_active is null) return;
        Sketch.Strokes.Clear();
        PersistInk();
    }

    private void Artwork_Click(object sender, RoutedEventArgs e)
    {
        if (_active is null) return;
        var dialog = new OpenFileDialog { Filter = "Images|*.png;*.jpg;*.jpeg;*.bmp;*.gif;*.webp|All files|*.*" };
        if (dialog.ShowDialog(this) != true) return;
        try
        {
            var bytes = File.ReadAllBytes(dialog.FileName);
            if (bytes.Length > 20 * 1024 * 1024) throw new IOException("Image must be smaller than 20 MB.");
            _active.ArtworkBase64 = Convert.ToBase64String(bytes);
        }
        catch (Exception ex) { ShowError(ex); }
    }

    private void RemoveArtwork_Click(object sender, RoutedEventArgs e)
    {
        if (_active is not null) _active.ArtworkBase64 = "";
    }

    private void Color_Click(object sender, RoutedEventArgs e)
    {
        if (_active is null) return;
        using var picker = new System.Windows.Forms.ColorDialog { FullOpen = true, AnyColor = true };
        if (picker.ShowDialog() == System.Windows.Forms.DialogResult.OK)
        {
            var c = picker.Color;
            _active.BadgeColor = $"#{c.R:X2}{c.G:X2}{c.B:X2}";
        }
    }

    private void SaveSettings()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(SettingsFile)!);
            File.WriteAllText(SettingsFile, JsonSerializer.Serialize(_settings, _json));
        }
        catch { /* Preferences are optional. */ }
    }

    private void LoadSettings()
    {
        try
        {
            if (File.Exists(SettingsFile))
                _settings = JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(SettingsFile)) ?? new AppSettings();
        }
        catch { _settings = new AppSettings(); }
    }

    private void Language_Changed(object sender, SelectionChangedEventArgs e)
    {
        if (_syncing || LanguageCombo.SelectedIndex < 0) return;
        _settings.Language = LanguageCodes[LanguageCombo.SelectedIndex];
        ApplyLanguage();
        SaveSettings();
    }

    private void Theme_Changed(object sender, SelectionChangedEventArgs e)
    {
        if (_syncing || ThemeCombo.SelectedItem is not string selected) return;
        _settings.Theme = selected;
        ApplyTheme();
        SaveSettings();
    }

    private void Cpu_Checked(object sender, RoutedEventArgs e)
    {
        if (_syncing) return;
        _settings.SoftwareRendering = CpuRendering.IsChecked == true;
        SetSoftwareRendering(_settings.SoftwareRendering);
        SaveSettings();
    }

    private bool CheckUnsaved() // Saving is explicit. Avoid data loss when opening or replacing.
    {
        return System.Windows.MessageBox.Show(this, "Replace the current project? Save it first if needed.",
            "Cubical Compare V5", MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes;
    }

    private void New_Click(object sender, RoutedEventArgs e)
    {
        if (!CheckUnsaved()) return;
        ReplaceCards(new[] { new ComparisonCard { Title = "First comparison", BadgeHeader = "Rank", Badge = "1" } });
        _projectPath = null;
        UpdateTitle();
    }

    private void ReplaceCards(IEnumerable<ComparisonCard> cards)
    {
        _syncing = true;
        _cards = new ObservableCollection<ComparisonCard>(cards);
        foreach (var card in _cards) Attach(card);
        if (_cards.Count == 0) { var card = new ComparisonCard(); Attach(card); _cards.Add(card); }
        _active = null;
        CardList.ItemsSource = _cards;
        _syncing = false;
        CardList.SelectedIndex = 0;
    }

    private void Save_Click(object sender, RoutedEventArgs e)
    {
        PersistInk();
        string? path = _projectPath;
        if (path is null)
        {
            var dialog = new SaveFileDialog { Filter = "Cubical Compare V5|*.ccv5", DefaultExt = ".ccv5", AddExtension = true };
            if (dialog.ShowDialog(this) != true) return;
            path = dialog.FileName;
        }
        try
        {
            string temp = path + ".tmp";
            File.WriteAllText(temp, JsonSerializer.Serialize(new ComparisonProject { Cards = _cards }, _json));
            File.Move(temp, path, true);
            _projectPath = path;
            UpdateTitle();
            StatusLabel.Text = "Saved: " + Path.GetFileName(path);
        }
        catch (Exception ex) { ShowError(ex); }
    }

    private void Open_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog { Filter = "Cubical Compare V5|*.ccv5" };
        if (dialog.ShowDialog(this) != true || !CheckUnsaved()) return;
        try
        {
            var project = JsonSerializer.Deserialize<ComparisonProject>(File.ReadAllText(dialog.FileName));
            if (project is null || project.FormatVersion != 5) throw new InvalidDataException("Not a V5 project.");
            ReplaceCards(project.Cards ?? new ObservableCollection<ComparisonCard>());
            _projectPath = dialog.FileName;
            UpdateTitle();
            StatusLabel.Text = "Loaded: " + Path.GetFileName(dialog.FileName);
        }
        catch (Exception ex) { ShowError(ex); }
    }

    // RFC4180 quoted fields and embedded line breaks, with UTF-8 / BOM input.
    private static IEnumerable<string[]> ParseCsv(string input)
    {
        var rows = new List<string[]>();
        var row = new List<string>();
        var field = new StringBuilder();
        bool quoted = false;
        for (int i = 0; i < input.Length; i++)
        {
            char c = input[i];
            if (c == '"')
            {
                if (quoted && i + 1 < input.Length && input[i + 1] == '"') { field.Append('"'); i++; }
                else quoted = !quoted;
            }
            else if (c == ',' && !quoted) { row.Add(field.ToString()); field.Clear(); }
            else if ((c == '\n' || c == '\r') && !quoted)
            {
                if (c == '\r' && i + 1 < input.Length && input[i + 1] == '\n') i++;
                row.Add(field.ToString()); field.Clear();
                if (row.Any(v => v.Length > 0)) rows.Add(row.ToArray());
                row.Clear();
            }
            else field.Append(c);
        }
        if (quoted) throw new InvalidDataException("Unterminated quoted CSV field.");
        if (row.Count > 0 || field.Length > 0)
        {
            row.Add(field.ToString());
            if (row.Any(v => v.Length > 0)) rows.Add(row.ToArray());
        }
        return rows;
    }

    private void Import_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog { Filter = "CSV (*.csv)|*.csv" };
        if (dialog.ShowDialog(this) != true) return;
        try
        {
            var rows = ParseCsv(File.ReadAllText(dialog.FileName, Encoding.UTF8)).ToList();
            if (rows.Count == 0) throw new InvalidDataException("CSV is empty.");
            var header = rows[0].Select(x => x.Trim().TrimStart('\uFEFF')).ToList();
            int Col(string key) => header.FindIndex(x => x.Equals(key, StringComparison.OrdinalIgnoreCase));
            string Get(string[] row, string key)
            {
                int idx = Col(key);
                return idx < 0 || idx >= row.Length ? "" : row[idx];
            }
            if (Col("Title") < 0) throw new InvalidDataException("CSV requires a Title column.");
            var cards = new List<ComparisonCard>();
            foreach (var row in rows.Skip(1))
            {
                if (string.IsNullOrWhiteSpace(Get(row, "Title"))) continue;
                var card = new ComparisonCard
                {
                    Title = Get(row, "Title"),
                    BadgeHeader = Get(row, "Badge Header"),
                    Badge = Get(row, "Badge Value"),
                    BadgeUnit = Get(row, "Badge Unit"),
                    Description = Get(row, "Description")
                };
                var image = Get(row, "Image");
                if (!string.IsNullOrWhiteSpace(image))
                {
                    string candidate = Path.IsPathRooted(image) ? image : Path.Combine(Path.GetDirectoryName(dialog.FileName)!, image);
                    if (File.Exists(candidate)) card.ArtworkBase64 = Convert.ToBase64String(File.ReadAllBytes(candidate));
                }
                cards.Add(card);
            }
            if (cards.Count == 0) throw new InvalidDataException("No cards in CSV.");
            if (!CheckUnsaved()) return;
            ReplaceCards(cards);
            _projectPath = null;
            UpdateTitle();
            StatusLabel.Text = $"Imported {cards.Count} cards.";
        }
        catch (Exception ex) { ShowError(ex); }
    }

    private byte[] CaptureCard(ComparisonCard card)
    {
        UpdatePreview(card);
        LoadInk(card);
        Stage.UpdateLayout();
        var bitmap = new RenderTargetBitmap(1920, 1080, 192, 192, PixelFormats.Pbgra32);
        bitmap.Render(Stage);
        bitmap.Freeze();
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var stream = new MemoryStream();
        encoder.Save(stream);
        return stream.ToArray();
    }

    private static string? FindFfmpeg()
    {
        var local = Path.Combine(AppContext.BaseDirectory, "ffmpeg.exe");
        if (File.Exists(local)) return local;
        foreach (var folder in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator))
        {
            try
            {
                var path = Path.Combine(folder.Trim('"'), "ffmpeg.exe");
                if (File.Exists(path)) return path;
            }
            catch { /* Ignore malformed PATH entries. */ }
        }
        return null;
    }

    private async void Export_Click(object sender, RoutedEventArgs e)
    {
        if (_exporting) return;
        PersistInk();
        string? ffmpeg = FindFfmpeg();
        if (ffmpeg is null)
        {
            System.Windows.MessageBox.Show(this,
                "Export requires ffmpeg.exe in the application folder or on PATH. Install FFmpeg, then try again.",
                "FFmpeg not found", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }
        var dialog = new SaveFileDialog { Filter = "MP4 video|*.mp4", AddExtension = true, DefaultExt = ".mp4" };
        if (dialog.ShowDialog(this) != true) return;
        _exporting = true;
        ExportButton.IsEnabled = false;
        ExportProgress.Visibility = Visibility.Visible;
        ExportProgress.Value = 0;
        ComparisonCard? previous = _active;
        try
        {
            // WPF visuals must be captured on the UI dispatcher, then stream to FFmpeg off-thread.
            var images = new List<byte[]>();
            foreach (var card in _cards)
            {
                images.Add(CaptureCard(card));
                await Dispatcher.Yield(DispatcherPriority.Background);
            }
            if (previous is not null) { UpdatePreview(previous); LoadInk(previous); }
            await Task.Run(async () =>
            {
                using var process = new Process();
                process.StartInfo = new ProcessStartInfo
                {
                    FileName = ffmpeg,
                    Arguments = $"-hide_banner -loglevel error -y -f image2pipe -framerate {FramesPerSecond} -vcodec png -i pipe:0 -c:v libx264 -preset veryfast -pix_fmt yuv420p -movflags +faststart \"{dialog.FileName}\"",
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardInput = true,
                    RedirectStandardError = true
                };
                if (!process.Start()) throw new IOException("Could not start FFmpeg.");
                var errorTask = process.StandardError.ReadToEndAsync();
                int total = images.Count * FramesPerSecond * SecondsPerCard;
                int completed = 0;
                foreach (var image in images)
                {
                    for (int i = 0; i < FramesPerSecond * SecondsPerCard; i++)
                    {
                        await process.StandardInput.BaseStream.WriteAsync(image);
                        completed++;
                    }
                    int progress = completed * 100 / total;
                    Dispatcher.Invoke(() => { ExportProgress.Value = progress; StatusLabel.Text = $"Exporting... {progress}%"; });
                }
                await process.StandardInput.BaseStream.FlushAsync();
                process.StandardInput.Close();
                await process.WaitForExitAsync();
                var error = await errorTask;
                if (process.ExitCode != 0) throw new IOException("FFmpeg export failed: " + error);
            });
            StatusLabel.Text = "Video exported: " + Path.GetFileName(dialog.FileName);
        }
        catch (Exception ex) { ShowError(ex); }
        finally
        {
            if (previous is not null) { UpdatePreview(previous); LoadInk(previous); }
            ExportProgress.Visibility = Visibility.Collapsed;
            ExportButton.IsEnabled = true;
            _exporting = false;
        }
    }

    private void ShowError(Exception ex)
    {
        StatusLabel.Text = ex.Message;
        System.Windows.MessageBox.Show(this, ex.Message, "Cubical Compare V5", MessageBoxButton.OK, MessageBoxImage.Error);
    }
}
