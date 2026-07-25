from __future__ import annotations

import sys
from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one match in {path}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: alpha19-apply.py SOURCE_ROOT")
    root = Path(sys.argv[1])

    replace_once(root / "CMakeLists.txt", "project(CubicalCreate VERSION 0.2.2", "project(CubicalCreate VERSION 0.2.4")
    replace_once(root / "packaging/linux/control", "Version: 0.2.2~alpha17", "Version: 0.2.4~alpha19")
    replace_once(root / "engine/engine_cli.py", 'VERSION = "0.2.3-alpha.18"', 'VERSION = "0.2.4-alpha.19"')

    readme = root / "README.md"
    readme.write_text(readme.read_text(encoding="utf-8") + """

## Alpha 19

- Image-sheet import now runs in the background on GTK and Windows, so the editor remains responsive.
- 100-cell PNG sheets use lossless fast compression rather than Pillow's exhaustive optimiser.
- The reference-style default is now Arial/Helvetica-compatible, including the double-storey lowercase `g`.
""", encoding="utf-8")

    replace_once(
        root / "engine/ccengine/image_sheet_core.py",
        '        crop.save(destination, "PNG", optimize=True)\n',
        '        # Lossless PNG output, but avoid Pillow\'s very slow exhaustive optimiser.\n'
        '        # compress_level=3 preserves every pixel and makes 100-cell sheets import\n'
        '        # in seconds instead of making the native interface appear frozen.\n'
        '        crop.save(destination, "PNG", optimize=False, compress_level=3)\n',
    )

    renderer = root / "engine/ccengine/renderer.py"
    text = renderer.read_text(encoding="utf-8")
    start = text.index("        # The source video uses a tighter, more condensed display face")
    end = text.index("        for candidate in candidates:", start)
    replacement = '''        # The early reference video uses a conventional neo-grotesque sans with a
        # double-storey lowercase g. Arial/Helvetica-compatible faces are a much
        # closer match than Poppins or the former condensed fallback.
        if bold:
            candidates = [
                "C:/Windows/Fonts/arialbd.ttf",
                "C:/Windows/Fonts/Arialbd.ttf",
                "/usr/share/fonts/opentype/urw-base35/NimbusSans-Bold.otf",
                "/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf",
                "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
                "/usr/share/fonts/truetype/croscore/Arimo-Bold.ttf",
                "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
            ]
        else:
            candidates = [
                "C:/Windows/Fonts/arial.ttf",
                "C:/Windows/Fonts/Arial.ttf",
                "/usr/share/fonts/opentype/urw-base35/NimbusSans-Regular.otf",
                "/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf",
                "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
                "/usr/share/fonts/truetype/croscore/Arimo-Regular.ttf",
                "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            ]
'''
    renderer.write_text(text[:start] + replacement + text[end:], encoding="utf-8")

    linux = root / "native/linux-gtk/main.cpp"
    replace_once(linux, "    GtkWidget* sheet_start{};\n", "    GtkWidget* sheet_start{};\n    GtkWidget* sheet_button{};\n")
    replace_once(linux, "    bool preview_rendering{false};\n", "    bool preview_rendering{false};\n    bool sheet_importing{false};\n")
    preview_struct = '''struct PreviewResult {
    AppState* state{};
    fs::path image_path;
    fs::path project_snapshot;
    double time{0.0};
    std::string error;
};
'''
    replace_once(linux, preview_struct, preview_struct + '''
struct SheetImportResult {
    AppState* state{};
    fs::path output_path;
    fs::path project_snapshot;
    fs::path assets_path;
    int exit_code{-1};
    std::string message;
};
''')
    playback = '''static gboolean playback_tick(gpointer data) {
    auto* s = static_cast<AppState*>(data);
    if (!s->playing) return G_SOURCE_CONTINUE;
    const auto now = std::chrono::steady_clock::now();
    const double elapsed = std::chrono::duration<double>(now - s->play_anchor).count();
    s->current_time = s->play_anchor_time + elapsed;
    if (s->current_time >= s->duration) {
        s->current_time = s->duration;
        s->playing = false;
    }
    update_player_ui(s);
    start_preview_render(s, s->current_time);
    return G_SOURCE_CONTINUE;
}

'''
    finish = '''static gboolean finish_sheet_import(gpointer data) {
    std::unique_ptr<SheetImportResult> result(static_cast<SheetImportResult*>(data));
    auto* s = result->state;
    s->sheet_importing = false;
    if (s->sheet_button) gtk_widget_set_sensitive(s->sheet_button, TRUE);

    std::string load_error;
    const fs::path existing_project_path = s->project.project_path;
    if (result->exit_code == 0 && cubical::load_ccx(s->project, result->output_path, &load_error)) {
        s->project.project_path = existing_project_path;
        s->selected = 0;
        load_project_ui(s);
        reset_player(s);
        set_status(s, result->message.empty() ? "Image sheet imported." : result->message);
    } else {
        if (load_error.empty()) load_error = result->message;
        set_status(s, load_error.empty() ? "Image-sheet import failed." : load_error);
        std::error_code remove_error;
        fs::remove_all(result->assets_path, remove_error);
    }

    std::error_code ignored;
    fs::remove(result->output_path, ignored);
    fs::remove(result->project_snapshot, ignored);
    return G_SOURCE_REMOVE;
}

'''
    replace_once(linux, playback, playback + finish)
    old_linux_import = '''    } else if (ctx->action == PickAction::ImportSheet) {
        auto out = cubical::temporary_path("cubical-sheet", ".ccx");
        auto assets = fs::temp_directory_path() / "cubical-create-sheet-assets";
        int rows = gtk_spin_button_get_value_as_int(GTK_SPIN_BUTTON(s->sheet_rows));
        int columns = gtk_spin_button_get_value_as_int(GTK_SPIN_BUTTON(s->sheet_columns));
        int start = std::max(0, gtk_spin_button_get_value_as_int(GTK_SPIN_BUTTON(s->sheet_start)) - 1);
        std::vector<std::string> command = {"import-sheet", s->working_ccx.string(), path, out.string(), assets.string(), "--expected", std::to_string(s->project.cards.size()), "--start", std::to_string(start), "--fit", "cts_card"};
        if (rows > 0 && columns > 0) { command.insert(command.end(), {"--rows", std::to_string(rows), "--columns", std::to_string(columns)}); }
        auto result = cubical::run_engine(command);
        if (result.exit_code == 0 && cubical::load_ccx(s->project, out)) { s->selected = 0; load_project_ui(s); reset_player(s); set_status(s, result.output); }
        else set_status(s, result.output);
        std::error_code ec; fs::remove(out, ec);
'''
    new_linux_import = '''    } else if (ctx->action == PickAction::ImportSheet) {
        if (s->sheet_importing) { set_status(s, "An image sheet is already being imported."); return; }
        const auto out = cubical::temporary_path("cubical-sheet", ".ccx");
        const auto snapshot = cubical::temporary_path("cubical-sheet-project", ".ccx");
        const auto assets = cubical::temporary_path("cubical-create-sheet-assets", "");
        std::error_code copy_error;
        fs::copy_file(s->working_ccx, snapshot, fs::copy_options::overwrite_existing, copy_error);
        if (copy_error) { set_status(s, "Could not prepare image-sheet import: " + copy_error.message()); return; }

        const int rows = gtk_spin_button_get_value_as_int(GTK_SPIN_BUTTON(s->sheet_rows));
        const int columns = gtk_spin_button_get_value_as_int(GTK_SPIN_BUTTON(s->sheet_columns));
        const int start = std::max(0, gtk_spin_button_get_value_as_int(GTK_SPIN_BUTTON(s->sheet_start)) - 1);
        std::vector<std::string> command = {"import-sheet", snapshot.string(), path, out.string(), assets.string(), "--expected", std::to_string(s->project.cards.size()), "--start", std::to_string(start), "--fit", "cts_card"};
        if (rows > 0 && columns > 0) command.insert(command.end(), {"--rows", std::to_string(rows), "--columns", std::to_string(columns)});

        s->playing = false;
        s->sheet_importing = true;
        if (s->sheet_button) gtk_widget_set_sensitive(s->sheet_button, FALSE);
        set_status(s, "Importing image sheet in the background…");
        std::thread([s, command = std::move(command), out, snapshot, assets]() mutable {
            auto process = cubical::run_engine(command);
            auto* result = new SheetImportResult{s, out, snapshot, assets, process.exit_code, process.output};
            g_idle_add(finish_sheet_import, result);
        }).detach();
'''
    replace_once(linux, old_linux_import, new_linux_import)
    replace_once(
        linux,
        '    for (auto& b : buttons) { GtkWidget* w = gtk_button_new_with_label(b.label); g_signal_connect(w, "clicked", b.cb, s); gtk_box_append(GTK_BOX(toolbar), w); }\n',
        '    for (auto& b : buttons) {\n'
        '        GtkWidget* w = gtk_button_new_with_label(b.label);\n'
        '        if (std::string(b.label) == "Image Sheet") s->sheet_button = w;\n'
        '        g_signal_connect(w, "clicked", b.cb, s);\n'
        '        gtk_box_append(GTK_BOX(toolbar), w);\n'
        '    }\n',
    )

    windows = root / "native/windows/main.cpp"
    replace_once(windows, "constexpr UINT WM_PREVIEW_READY = WM_APP + 43;\n", "constexpr UINT WM_PREVIEW_READY = WM_APP + 43;\nconstexpr UINT WM_SHEET_DONE = WM_APP + 44;\n")
    replace_once(windows, "    bool preview_rendering{false};\n", "    bool preview_rendering{false};\n    bool sheet_importing{false};\n")
    replace_once(windows, preview_struct, preview_struct + '''
struct SheetImportResult {
    fs::path output_path;
    fs::path project_snapshot;
    fs::path assets_path;
    int exit_code{-1};
    std::string message;
};
''')
    old_windows_import = 'void choose_and_import_sheet(AppState* s){COMDLG_FILTERSPEC f[]={{L"Images",L"*.png;*.jpg;*.jpeg;*.webp;*.bmp"},{L"All files",L"*.*"}};auto p=choose_shell_file(s->window,false,L"Import image sheet",f,2);if(p.empty()||!write_working(s))return;auto out=cubical::temporary_path("cubical-sheet",".ccx");auto assets=fs::temp_directory_path()/"cubical-create-sheet-assets";int rows=to_int(s->sheet_rows,0),columns=to_int(s->sheet_columns,0),start=std::max(0,to_int(s->sheet_start,1)-1);std::vector<std::string> command={"import-sheet",s->working_ccx.string(),utf8(p),out.string(),assets.string(),"--expected",std::to_string(s->project.cards.size()),"--start",std::to_string(start),"--fit","cts_card"};if(rows>0&&columns>0){command.insert(command.end(),{"--rows",std::to_string(rows),"--columns",std::to_string(columns)});}auto r=cubical::run_engine(command);if(r.exit_code==0&&cubical::load_ccx(s->project,out)){s->selected=0;load_project(s);reset_player(s);set_status(s,r.output);}else set_status(s,r.output);std::error_code ec;fs::remove(out,ec);}\n'
    new_windows_import = '''void choose_and_import_sheet(AppState* s){
    if(s->sheet_importing){set_status(s,"An image sheet is already being imported.");return;}
    COMDLG_FILTERSPEC f[]={{L"Images",L"*.png;*.jpg;*.jpeg;*.webp;*.bmp"},{L"All files",L"*.*"}};
    auto p=choose_shell_file(s->window,false,L"Import image sheet",f,2);
    if(p.empty()||!write_working(s))return;
    const auto out=cubical::temporary_path("cubical-sheet",".ccx");
    const auto snapshot=cubical::temporary_path("cubical-sheet-project",".ccx");
    const auto assets=cubical::temporary_path("cubical-create-sheet-assets","");
    std::error_code copy_error;
    fs::copy_file(s->working_ccx,snapshot,fs::copy_options::overwrite_existing,copy_error);
    if(copy_error){set_status(s,"Could not prepare image-sheet import: "+copy_error.message());return;}
    int rows=to_int(s->sheet_rows,0),columns=to_int(s->sheet_columns,0),start=std::max(0,to_int(s->sheet_start,1)-1);
    std::vector<std::string> command={"import-sheet",snapshot.string(),utf8(p),out.string(),assets.string(),"--expected",std::to_string(s->project.cards.size()),"--start",std::to_string(start),"--fit","cts_card"};
    if(rows>0&&columns>0)command.insert(command.end(),{"--rows",std::to_string(rows),"--columns",std::to_string(columns)});
    s->playing=false;
    s->sheet_importing=true;
    EnableWindow(GetDlgItem(s->window,ID_SHEET),FALSE);
    set_status(s,"Importing image sheet in the background...");
    HWND window=s->window;
    std::thread([window,command=std::move(command),out,snapshot,assets]() mutable {
        auto process=cubical::run_engine(command);
        auto* payload=new SheetImportResult{out,snapshot,assets,process.exit_code,process.output};
        if(!IsWindow(window)||!PostMessageW(window,WM_SHEET_DONE,0,reinterpret_cast<LPARAM>(payload))){
            std::error_code ignored;fs::remove(out,ignored);fs::remove(snapshot,ignored);if(process.exit_code!=0)fs::remove_all(assets,ignored);delete payload;
        }
    }).detach();
}
'''
    replace_once(windows, old_windows_import, new_windows_import)
    replace_once(
        windows,
        'case WM_EXPORT_DONE:{std::unique_ptr<std::string> m(reinterpret_cast<std::string*>(lp));set_status(s,*m);return 0;}case WM_CLOSE:',
        '''case WM_SHEET_DONE:{
    std::unique_ptr<SheetImportResult> result(reinterpret_cast<SheetImportResult*>(lp));
    s->sheet_importing=false;
    EnableWindow(GetDlgItem(s->window,ID_SHEET),TRUE);
    std::string load_error;
    const fs::path existing_project_path=s->project.project_path;
    if(result->exit_code==0&&cubical::load_ccx(s->project,result->output_path,&load_error)){
        s->project.project_path=existing_project_path;s->selected=0;load_project(s);reset_player(s);set_status(s,result->message.empty()?"Image sheet imported.":result->message);
    }else{
        if(load_error.empty())load_error=result->message;set_status(s,load_error.empty()?"Image-sheet import failed.":load_error);std::error_code remove_error;fs::remove_all(result->assets_path,remove_error);
    }
    std::error_code ignored;fs::remove(result->output_path,ignored);fs::remove(result->project_snapshot,ignored);return 0;
}case WM_EXPORT_DONE:{std::unique_ptr<std::string> m(reinterpret_cast<std::string*>(lp));set_status(s,*m);return 0;}case WM_CLOSE:''',
    )

    (root / "tests/test_image_sheet_import.py").write_text('''from argparse import Namespace
from pathlib import Path

from PIL import Image, ImageDraw

from ccengine.models import Card, Project
from engine_cli import command_import_sheet, read_ccx, write_ccx


def make_sheet(path: Path, rows: int = 11, columns: int = 10) -> None:
    cell_w, cell_h, gutter = 24, 24, 2
    width = columns * cell_w + (columns - 1) * gutter
    height = rows * cell_h + (rows - 1) * gutter
    image = Image.new("RGB", (width, height), "black")
    draw = ImageDraw.Draw(image)
    index = 0
    for row in range(rows):
        for column in range(columns):
            left = column * (cell_w + gutter)
            top = row * (cell_h + gutter)
            colour = ((index * 17) % 255, (index * 31) % 255, (index * 47) % 255)
            draw.rectangle((left, top, left + cell_w - 1, top + cell_h - 1), fill=colour)
            index += 1
    image.save(path)


def test_image_sheet_import_assigns_only_available_cards_and_outputs_lossless_pngs(tmp_path):
    source_project = tmp_path / "project.ccx"
    sheet = tmp_path / "sheet.png"
    output_project = tmp_path / "imported.ccx"
    assets = tmp_path / "assets"
    write_ccx(Project(cards=[Card(title=f"Card {index + 1}") for index in range(101)]), source_project)
    make_sheet(sheet)
    args = Namespace(input=str(source_project), sheet=str(sheet), output=str(output_project), assets=str(assets), rows=11, columns=10, expected=101, start=0, margin=0, gutter_x=0, gutter_y=0, inset=0, trim_left=0, trim_top=0, trim_right=0, trim_bottom=0, create_extra=False, fit="cts_card", target_width=48, target_height=83)
    assert command_import_sheet(args) == 0
    imported = read_ccx(output_project)
    assigned = [Path(card.image) for card in imported.cards if card.image]
    assert len(imported.cards) == 101
    assert len(assigned) == 101
    crops = [path for path in assets.glob("*.png") if not path.name.startswith("_source")]
    assert len(crops) == 101
    with Image.open(assigned[0]) as first:
        assert first.size == (48, 83)
        assert first.format == "PNG"
''', encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
