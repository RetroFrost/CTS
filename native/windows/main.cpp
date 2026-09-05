#define UNICODE
#define _UNICODE
#include <windows.h>
#include <commctrl.h>
#include <commdlg.h>
#include <shellapi.h>
#include <shlobj.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <memory>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include "cubical/process.hpp"
#include "cubical/project.hpp"

#pragma comment(lib, "comctl32.lib")
#pragma comment(lib, "comdlg32.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "ole32.lib")

namespace fs = std::filesystem;
namespace {

constexpr wchar_t kWindowClass[] = L"CubicalCompare30300Cpp";
constexpr wchar_t kTitle[] = L"Cubical Compare 3.0.300 — C++ for Windows";
constexpr UINT WM_PREVIEW_DONE = WM_APP + 10;
constexpr UINT WM_TASK_DONE = WM_APP + 11;
constexpr UINT kProgressTimer = 20;
constexpr UINT kPlaybackTimer = 21;
constexpr UINT kAutosaveTimer = 22;

enum class Page { Cards, Preview, Project, More };

enum ControlId {
    ID_TAB_CARDS = 100, ID_TAB_PREVIEW, ID_TAB_PROJECT, ID_TAB_MORE,
    ID_CARD_LIST, ID_ADD_CARD, ID_DUP_CARD, ID_DELETE_CARD,
    ID_TITLE, ID_BADGE_HEADER, ID_VALUE, ID_DESCRIPTION,
    ID_IMAGE, ID_CHOOSE_IMAGE, ID_REMOVE_IMAGE,
    ID_IMAGE_X, ID_IMAGE_Y, ID_IMAGE_SCALE, ID_IMAGE_ROTATION,
    ID_CROP_LEFT, ID_CROP_TOP, ID_CROP_RIGHT, ID_CROP_BOTTOM, ID_IMAGE_LAYER, ID_RESET_TRANSFORM,
    ID_PREVIEW, ID_TIMELINE, ID_FRAME_LABEL, ID_PLAY,
    ID_PROJECT_NAME, ID_WIDTH, ID_HEIGHT, ID_FPS,
    ID_BADGES, ID_CREDITS, ID_AUTO_LENGTH, ID_CUSTOM_LENGTH,
    ID_INTRO_MODE, ID_INTRO_VIDEO, ID_CHOOSE_INTRO,
    ID_SOUNDTRACK, ID_CHOOSE_SOUNDTRACK, ID_SOUNDTRACK_VOLUME, ID_SOUNDTRACK_LOOP,
    ID_ENCODER, ID_FONT_FAMILY, ID_FONT_FILE, ID_CHOOSE_FONT,
    ID_NEW, ID_OPEN, ID_SAVE, ID_IMPORT_DATA, ID_IMPORT_PACK,
    ID_IMPORT_RENDERER, ID_RENDERER_LIBRARY, ID_EXPORT, ID_CANCEL,
    ID_RUNTIME_INFO, ID_PROGRESS, ID_STATUS,
};

std::string utf8(const std::wstring& value) {
    if (value.empty()) return {};
    const int count = WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0, nullptr, nullptr);
    std::string result(static_cast<std::size_t>(std::max(count, 0)), '\0');
    if (count > 0) WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), result.data(), count, nullptr, nullptr);
    return result;
}
std::wstring wide(const std::string& value) {
    if (value.empty()) return {};
    const int count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(), static_cast<int>(value.size()), nullptr, 0);
    if (count <= 0) return std::wstring(value.begin(), value.end());
    std::wstring result(static_cast<std::size_t>(count), L'\0');
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(), static_cast<int>(value.size()), result.data(), count);
    return result;
}
std::string narrow_path(const fs::path& value) { return utf8(value.wstring()); }

std::wstring control_text(HWND control) {
    if (!control) return {};
    const int length = GetWindowTextLengthW(control);
    std::wstring result(static_cast<std::size_t>(length) + 1, L'\0');
    GetWindowTextW(control, result.data(), length + 1);
    result.resize(static_cast<std::size_t>(length));
    return result;
}
void set_control_text(HWND control, const std::string& value) { if (control) SetWindowTextW(control, wide(value).c_str()); }
void set_control_text(HWND control, const std::wstring& value) { if (control) SetWindowTextW(control, value.c_str()); }

double parse_double(HWND control, double fallback) {
    try { return std::stod(control_text(control)); } catch (...) { return fallback; }
}
int parse_int(HWND control, int fallback) {
    try { return std::stoi(control_text(control)); } catch (...) { return fallback; }
}
std::wstring format_number(double value, int precision = 3) {
    std::wostringstream out; out << std::fixed << std::setprecision(precision) << value;
    std::wstring s = out.str();
    while (s.size() > 1 && s.back() == L'0') s.pop_back();
    if (!s.empty() && s.back() == L'.') s.pop_back();
    return s;
}

struct App {
    HINSTANCE instance{};
    HWND window{};
    HFONT font{};
    HBITMAP preview_bitmap{};
    Page page{Page::Cards};
    cubical::Project project;
    fs::path project_path;
    fs::path working_ccx;
    fs::path progress_file;
    fs::path cancel_file;
    int selected{0};
    int current_frame{0};
    int total_frames{1};
    bool loading{false};
    bool dirty{false};
    bool playing{false};
    bool pending_close{false};
    std::atomic<unsigned long> preview_generation{0};
    std::atomic<bool> busy{false};
    std::chrono::steady_clock::time_point play_started{};
    int play_start_frame{};
};

struct PreviewResult { unsigned long generation{}; fs::path bitmap; std::string error; };
struct TaskResult {
    enum class Kind { ImportData, ImportPack, Export } kind{Kind::Export};
    cubical::ProcessResult process;
    fs::path output;
};

HWND ctl(App* app, int id) { return GetDlgItem(app->window, id); }
HWND make_control(App* app, const wchar_t* cls, const wchar_t* text, DWORD style, int id, DWORD ex = WS_EX_NOPARENTNOTIFY) {
    HWND h = CreateWindowExW(ex, cls, text, WS_CHILD | WS_VISIBLE | style, 0, 0, 10, 10, app->window,
        reinterpret_cast<HMENU>(static_cast<INT_PTR>(id)), app->instance, nullptr);
    if (h && app->font) SendMessageW(h, WM_SETFONT, reinterpret_cast<WPARAM>(app->font), TRUE);
    return h;
}
HWND make_label(App* app, const wchar_t* text, int id) { return make_control(app, L"STATIC", text, SS_LEFT, id); }
void status(App* app, const std::string& value) { set_control_text(ctl(app, ID_STATUS), value); }

void combo_add(HWND combo, const wchar_t* label, const wchar_t* data = nullptr) {
    const LRESULT index = SendMessageW(combo, CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(label));
    if (index >= 0 && data) {
        auto* copy = new std::wstring(data);
        SendMessageW(combo, CB_SETITEMDATA, static_cast<WPARAM>(index), reinterpret_cast<LPARAM>(copy));
    }
}
std::wstring combo_value(HWND combo) {
    const int index = static_cast<int>(SendMessageW(combo, CB_GETCURSEL, 0, 0));
    if (index < 0) return {};
    const auto raw = SendMessageW(combo, CB_GETITEMDATA, index, 0);
    if (raw != CB_ERR && raw != 0) return *reinterpret_cast<std::wstring*>(raw);
    wchar_t buffer[256]{}; SendMessageW(combo, CB_GETLBTEXT, index, reinterpret_cast<LPARAM>(buffer)); return buffer;
}
void combo_select_value(HWND combo, const std::wstring& value) {
    const int count = static_cast<int>(SendMessageW(combo, CB_GETCOUNT, 0, 0));
    for (int i = 0; i < count; ++i) {
        const auto raw = SendMessageW(combo, CB_GETITEMDATA, i, 0);
        std::wstring current;
        if (raw != CB_ERR && raw != 0) current = *reinterpret_cast<std::wstring*>(raw);
        else { wchar_t buffer[256]{}; SendMessageW(combo, CB_GETLBTEXT, i, reinterpret_cast<LPARAM>(buffer)); current = buffer; }
        if (current == value) { SendMessageW(combo, CB_SETCURSEL, i, 0); return; }
    }
    if (count > 0) SendMessageW(combo, CB_SETCURSEL, 0, 0);
}
void free_combo_data(HWND combo) {
    if (!combo) return;
    const int count = static_cast<int>(SendMessageW(combo, CB_GETCOUNT, 0, 0));
    for (int i=0;i<count;++i) {
        const auto raw = SendMessageW(combo, CB_GETITEMDATA, i, 0);
        if (raw != CB_ERR && raw != 0) delete reinterpret_cast<std::wstring*>(raw);
    }
}

std::wstring choose_file(HWND owner, bool save, const wchar_t* filter, const wchar_t* extension = nullptr) {
    wchar_t path[32768]{};
    OPENFILENAMEW dialog{}; dialog.lStructSize = sizeof(dialog); dialog.hwndOwner = owner; dialog.lpstrFile = path;
    dialog.nMaxFile = static_cast<DWORD>(std::size(path)); dialog.lpstrFilter = filter; dialog.lpstrDefExt = extension;
    dialog.Flags = OFN_EXPLORER | OFN_PATHMUSTEXIST | (save ? OFN_OVERWRITEPROMPT : OFN_FILEMUSTEXIST);
    return (save ? GetSaveFileNameW(&dialog) : GetOpenFileNameW(&dialog)) ? std::wstring(path) : std::wstring();
}

fs::path app_data_dir() {
    PWSTR raw = nullptr;
    fs::path out = fs::temp_directory_path() / "Cubical Compare";
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_LocalAppData, KF_FLAG_CREATE, nullptr, &raw)) && raw) {
        out = fs::path(raw) / "Cubical Compare"; CoTaskMemFree(raw);
    }
    std::error_code ec; fs::create_directories(out, ec); return out;
}
fs::path renderer_dir() { auto p = app_data_dir() / "renderers"; std::error_code ec; fs::create_directories(p, ec); return p; }
fs::path autosave_path() { return app_data_dir() / "autosave.json"; }

void replace_bitmap(App* app, HBITMAP bitmap) {
    if (app->preview_bitmap) DeleteObject(app->preview_bitmap);
    app->preview_bitmap = bitmap;
    SendMessageW(ctl(app, ID_PREVIEW), STM_SETIMAGE, IMAGE_BITMAP, reinterpret_cast<LPARAM>(bitmap));
}

void mark_dirty(App* app) { if (!app->loading) app->dirty = true; }

void commit_card(App* app) {
    if (app->loading || app->selected < 0 || app->selected >= static_cast<int>(app->project.cards.size())) return;
    auto& c = app->project.cards[static_cast<std::size_t>(app->selected)];
    c.title = utf8(control_text(ctl(app, ID_TITLE)));
    c.badge_header = utf8(control_text(ctl(app, ID_BADGE_HEADER)));
    c.value = utf8(control_text(ctl(app, ID_VALUE)));
    c.description = utf8(control_text(ctl(app, ID_DESCRIPTION)));
    c.image = utf8(control_text(ctl(app, ID_IMAGE)));
    c.image_x = parse_double(ctl(app, ID_IMAGE_X), c.image_x);
    c.image_y = parse_double(ctl(app, ID_IMAGE_Y), c.image_y);
    c.image_scale = std::clamp(parse_double(ctl(app, ID_IMAGE_SCALE), c.image_scale), 0.05, 8.0);
    c.image_rotation = parse_double(ctl(app, ID_IMAGE_ROTATION), c.image_rotation);
    c.image_crop_left = std::clamp(parse_double(ctl(app, ID_CROP_LEFT), c.image_crop_left), 0.0, 0.49);
    c.image_crop_top = std::clamp(parse_double(ctl(app, ID_CROP_TOP), c.image_crop_top), 0.0, 0.49);
    c.image_crop_right = std::clamp(parse_double(ctl(app, ID_CROP_RIGHT), c.image_crop_right), 0.0, 0.49);
    c.image_crop_bottom = std::clamp(parse_double(ctl(app, ID_CROP_BOTTOM), c.image_crop_bottom), 0.0, 0.49);
    c.image_layer = utf8(combo_value(ctl(app, ID_IMAGE_LAYER)));
    mark_dirty(app);
}

void commit_project(App* app) {
    if (app->loading) return;
    commit_card(app);
    auto& s = app->project.settings;
    app->project.name = utf8(control_text(ctl(app, ID_PROJECT_NAME)));
    s.width = static_cast<std::uint32_t>(std::max(64, parse_int(ctl(app, ID_WIDTH), static_cast<int>(s.width))));
    s.height = static_cast<std::uint32_t>(std::max(64, parse_int(ctl(app, ID_HEIGHT), static_cast<int>(s.height))));
    s.fps = static_cast<std::uint32_t>(std::clamp(parse_int(ctl(app, ID_FPS), static_cast<int>(s.fps)), 1, 240));
    s.show_badges = SendMessageW(ctl(app, ID_BADGES), BM_GETCHECK, 0, 0) == BST_CHECKED;
    s.credits_enabled = SendMessageW(ctl(app, ID_CREDITS), BM_GETCHECK, 0, 0) == BST_CHECKED;
    s.auto_length = SendMessageW(ctl(app, ID_AUTO_LENGTH), BM_GETCHECK, 0, 0) == BST_CHECKED;
    s.custom_length_seconds = std::max(0.1, parse_double(ctl(app, ID_CUSTOM_LENGTH), s.custom_length_seconds));
    s.intro_mode = utf8(combo_value(ctl(app, ID_INTRO_MODE)));
    s.intro_video = utf8(control_text(ctl(app, ID_INTRO_VIDEO)));
    s.soundtrack = utf8(control_text(ctl(app, ID_SOUNDTRACK)));
    s.soundtrack_volume = std::clamp(parse_double(ctl(app, ID_SOUNDTRACK_VOLUME), s.soundtrack_volume), 0.0, 1.0);
    s.soundtrack_loop = SendMessageW(ctl(app, ID_SOUNDTRACK_LOOP), BM_GETCHECK, 0, 0) == BST_CHECKED;
    s.encoder_preference = utf8(combo_value(ctl(app, ID_ENCODER)));
    s.font_family = utf8(combo_value(ctl(app, ID_FONT_FAMILY)));
    s.font_file = utf8(control_text(ctl(app, ID_FONT_FILE)));
    mark_dirty(app);
}

void rebuild_card_list(App* app) {
    HWND list = ctl(app, ID_CARD_LIST); SendMessageW(list, LB_RESETCONTENT, 0, 0);
    for (std::size_t i=0;i<app->project.cards.size();++i) {
        const auto& c = app->project.cards[i];
        std::wstring label = std::to_wstring(i + 1) + L"  " + wide(c.title.empty() ? "Untitled" : c.title);
        SendMessageW(list, LB_ADDSTRING, 0, reinterpret_cast<LPARAM>(label.c_str()));
    }
    if (!app->project.cards.empty()) {
        app->selected = std::clamp(app->selected, 0, static_cast<int>(app->project.cards.size()) - 1);
        SendMessageW(list, LB_SETCURSEL, app->selected, 0);
    } else app->selected = -1;
}

void load_card(App* app) {
    app->loading = true;
    if (app->selected >= 0 && app->selected < static_cast<int>(app->project.cards.size())) {
        const auto& c=app->project.cards[static_cast<std::size_t>(app->selected)];
        set_control_text(ctl(app,ID_TITLE),c.title); set_control_text(ctl(app,ID_BADGE_HEADER),c.badge_header); set_control_text(ctl(app,ID_VALUE),c.value); set_control_text(ctl(app,ID_DESCRIPTION),c.description); set_control_text(ctl(app,ID_IMAGE),c.image);
        set_control_text(ctl(app,ID_IMAGE_X),format_number(c.image_x)); set_control_text(ctl(app,ID_IMAGE_Y),format_number(c.image_y)); set_control_text(ctl(app,ID_IMAGE_SCALE),format_number(c.image_scale)); set_control_text(ctl(app,ID_IMAGE_ROTATION),format_number(c.image_rotation));
        set_control_text(ctl(app,ID_CROP_LEFT),format_number(c.image_crop_left)); set_control_text(ctl(app,ID_CROP_TOP),format_number(c.image_crop_top)); set_control_text(ctl(app,ID_CROP_RIGHT),format_number(c.image_crop_right)); set_control_text(ctl(app,ID_CROP_BOTTOM),format_number(c.image_crop_bottom)); combo_select_value(ctl(app,ID_IMAGE_LAYER),wide(c.image_layer));
    }
    app->loading = false;
}

void load_project_controls(App* app) {
    app->loading = true; const auto& s=app->project.settings;
    set_control_text(ctl(app,ID_PROJECT_NAME),app->project.name); set_control_text(ctl(app,ID_WIDTH),std::to_wstring(s.width)); set_control_text(ctl(app,ID_HEIGHT),std::to_wstring(s.height)); set_control_text(ctl(app,ID_FPS),std::to_wstring(s.fps));
    SendMessageW(ctl(app,ID_BADGES),BM_SETCHECK,s.show_badges?BST_CHECKED:BST_UNCHECKED,0); SendMessageW(ctl(app,ID_CREDITS),BM_SETCHECK,s.credits_enabled?BST_CHECKED:BST_UNCHECKED,0); SendMessageW(ctl(app,ID_AUTO_LENGTH),BM_SETCHECK,s.auto_length?BST_CHECKED:BST_UNCHECKED,0);
    set_control_text(ctl(app,ID_CUSTOM_LENGTH),format_number(s.custom_length_seconds)); combo_select_value(ctl(app,ID_INTRO_MODE),wide(s.intro_mode)); set_control_text(ctl(app,ID_INTRO_VIDEO),s.intro_video); set_control_text(ctl(app,ID_SOUNDTRACK),s.soundtrack); set_control_text(ctl(app,ID_SOUNDTRACK_VOLUME),format_number(s.soundtrack_volume)); SendMessageW(ctl(app,ID_SOUNDTRACK_LOOP),BM_SETCHECK,s.soundtrack_loop?BST_CHECKED:BST_UNCHECKED,0); combo_select_value(ctl(app,ID_ENCODER),wide(s.encoder_preference)); combo_select_value(ctl(app,ID_FONT_FAMILY),wide(s.font_family)); set_control_text(ctl(app,ID_FONT_FILE),s.font_file);
    app->loading = false;
}

bool snapshot(App* app) {
    commit_project(app); std::string error;
    if (!cubical::save_ccx(app->project, app->working_ccx, &error)) { status(app,"Snapshot failed: "+error); return false; }
    return true;
}

void update_frame_label(App* app) {
    std::wostringstream text; text << L"Frame " << app->current_frame << L" / " << std::max(0,app->total_frames-1) << L"    " << std::fixed << std::setprecision(3) << (double(app->current_frame)/std::max(1u,app->project.settings.fps)) << L" s";
    SetWindowTextW(ctl(app,ID_FRAME_LABEL),text.str().c_str());
}
void read_metadata(App* app) {
    if(!snapshot(app)) return; auto result=cubical::run_engine({"validate",narrow_path(app->working_ccx)}); if(result.exit_code!=0) return;
    const std::string needle="\"frame_count\":"; const auto pos=result.output.find(needle); if(pos!=std::string::npos){std::size_t start=pos+needle.size();app->total_frames=std::max(1,std::atoi(result.output.c_str()+start));}
    app->current_frame=std::clamp(app->current_frame,0,app->total_frames-1); SendMessageW(ctl(app,ID_TIMELINE),TBM_SETRANGEMAX,TRUE,app->total_frames-1); SendMessageW(ctl(app,ID_TIMELINE),TBM_SETPOS,TRUE,app->current_frame); update_frame_label(app);
}
void request_preview(App* app) {
    if (!snapshot(app)) return; const auto generation=++app->preview_generation; const fs::path input=app->working_ccx; const fs::path output=cubical::temporary_path("cts30300-preview-"+std::to_string(generation),".bmp"); const int frame=app->current_frame; const HWND window=app->window;
    status(app,"Rendering preview…");
    std::thread([generation,input,output,frame,window](){auto* r=new PreviewResult; r->generation=generation;r->bitmap=output;auto p=cubical::run_engine({"render-preview",narrow_path(input),narrow_path(output),"--frame",std::to_string(frame),"--width","960","--height","540"});if(p.exit_code!=0)r->error=p.output.empty()?"Preview failed.":p.output;PostMessageW(window,WM_PREVIEW_DONE,0,reinterpret_cast<LPARAM>(r));}).detach();
}

void set_busy(App* app,bool busy,const std::string& text){app->busy=busy;EnableWindow(ctl(app,ID_EXPORT),!busy);EnableWindow(ctl(app,ID_CANCEL),busy);if(!busy)SendMessageW(ctl(app,ID_PROGRESS),PBM_SETPOS,0,0);status(app,text);}
void begin_import(App* app,TaskResult::Kind kind,const fs::path& source){if(app->busy||!snapshot(app))return;const fs::path output=cubical::temporary_path(kind==TaskResult::Kind::ImportData?"import-data":"import-pack",".ccx");const fs::path assets=app_data_dir()/"megapacks"/std::to_string(std::chrono::high_resolution_clock::now().time_since_epoch().count());std::error_code ec;if(kind==TaskResult::Kind::ImportPack)fs::create_directories(assets,ec);set_busy(app,true,kind==TaskResult::Kind::ImportData?"Importing data…":"Importing MegaPack…");const HWND window=app->window;const fs::path input=app->working_ccx;std::thread([kind,source,output,assets,input,window](){auto* t=new TaskResult;t->kind=kind;t->output=output;if(kind==TaskResult::Kind::ImportData)t->process=cubical::run_engine({"import-data",narrow_path(input),narrow_path(source),narrow_path(output)});else t->process=cubical::run_engine({"import-megapack",narrow_path(source),narrow_path(output),narrow_path(assets)});PostMessageW(window,WM_TASK_DONE,0,reinterpret_cast<LPARAM>(t));}).detach();}
void begin_export(App* app,const fs::path& output){if(app->busy||!snapshot(app))return;app->progress_file=cubical::temporary_path("cts30300-export-progress",".txt");app->cancel_file=cubical::temporary_path("cts30300-export-cancel",".flag");std::error_code ec;fs::remove(app->progress_file,ec);fs::remove(app->cancel_file,ec);set_busy(app,true,"Exporting MP4…");SetTimer(app->window,kProgressTimer,120,nullptr);const HWND window=app->window;const fs::path input=app->working_ccx,progress=app->progress_file,cancel=app->cancel_file;std::thread([input,output,progress,cancel,window](){auto* t=new TaskResult;t->kind=TaskResult::Kind::Export;t->output=output;t->process=cubical::run_engine({"export",narrow_path(input),narrow_path(output),"--progress-file",narrow_path(progress),"--cancel-file",narrow_path(cancel)});PostMessageW(window,WM_TASK_DONE,0,reinterpret_cast<LPARAM>(t));}).detach();}
void cancel_task(App* app){if(!app->busy)return;std::ofstream(app->cancel_file,std::ios::binary)<<"cancel\n";status(app,"Cancel requested…");}

void autosave(App* app) {
    if (!app->dirty || app->busy) return; commit_project(app); std::string error;
    if (cubical::save_project_json(app->project, autosave_path(), &error)) app->dirty=false;
}
void save_project(App* app,bool choose){commit_project(app);fs::path path=app->project_path;if(choose||path.empty()||path.extension()==".ccx"){const auto selected=choose_file(app->window,true,L"Cubical Compare 3.0.300 project (*.json)\0*.json\0All files\0*.*\0\0",L"json");if(selected.empty())return;path=selected;}std::string error;if(!cubical::save_project_json(app->project,path,&error)){MessageBoxW(app->window,wide(error).c_str(),L"Save failed",MB_ICONERROR);return;}app->project_path=path;app->project.project_path=path;app->dirty=false;status(app,"Saved "+narrow_path(path));}
void open_project(App* app){if(app->busy)return;const auto selected=choose_file(app->window,false,L"Cubical Compare projects (*.json;*.ccx)\0*.json;*.ccx\0JSON (*.json)\0*.json\0CCX (*.ccx)\0*.ccx\0All files\0*.*\0\0");if(selected.empty())return;cubical::Project p;std::string error;if(!cubical::load_project_auto(p,selected,&error)){MessageBoxW(app->window,wide(error).c_str(),L"Open failed",MB_ICONERROR);return;}app->project=std::move(p);app->project_path=selected;app->selected=0;app->current_frame=0;app->dirty=false;rebuild_card_list(app);load_card(app);load_project_controls(app);read_metadata(app);request_preview(app);status(app,"Opened "+narrow_path(selected));}
void new_project(App* app){if(app->busy)return;app->project=cubical::Project{};app->project_path.clear();app->selected=0;app->current_frame=0;app->dirty=true;rebuild_card_list(app);load_card(app);load_project_controls(app);read_metadata(app);request_preview(app);status(app,"New CTS 3.0.300 project");}

void import_renderer(App* app) {
    const auto chosen=choose_file(app->window,false,L"Cubical renderer (*.renderer;*.renderer3)\0*.renderer;*.renderer3\0All files\0*.*\0\0");if(chosen.empty())return;
    try { fs::path src(chosen), dst=renderer_dir()/src.filename(); std::error_code ec; fs::copy_file(src,dst,fs::copy_options::overwrite_existing,ec); if(ec)throw std::runtime_error(ec.message()); std::ofstream(renderer_dir()/"active-renderer.txt",std::ios::binary)<<narrow_path(dst.filename()); _wputenv_s(L"CUBICAL_COMPARE_RENDERER",dst.wstring().c_str()); status(app,"Renderer installed and activated: "+narrow_path(dst.filename())); request_preview(app); }
    catch(const std::exception& ex){MessageBoxW(app->window,wide(ex.what()).c_str(),L"Renderer import failed",MB_ICONERROR);}
}
void open_renderer_library(){const auto dir=renderer_dir();ShellExecuteW(nullptr,L"open",dir.wstring().c_str(),nullptr,nullptr,SW_SHOWNORMAL);}

void set_page(App* app,Page page);

void create_ui(App* app) {
    NONCLIENTMETRICSW metrics{sizeof(metrics)}; SystemParametersInfoW(SPI_GETNONCLIENTMETRICS,sizeof(metrics),&metrics,0); app->font=CreateFontIndirectW(&metrics.lfMessageFont);
    auto button=[&](int id,const wchar_t* text){return make_control(app,L"BUTTON",text,BS_PUSHBUTTON,id);};
    button(ID_TAB_CARDS,L"Cards");button(ID_TAB_PREVIEW,L"Preview");button(ID_TAB_PROJECT,L"Project");button(ID_TAB_MORE,L"More");

    make_control(app,L"LISTBOX",L"",LBS_NOTIFY|WS_BORDER|WS_VSCROLL,ID_CARD_LIST);button(ID_ADD_CARD,L"Add");button(ID_DUP_CARD,L"Duplicate");button(ID_DELETE_CARD,L"Delete");
    make_label(app,L"Title",9001);make_control(app,L"EDIT",L"",WS_BORDER|ES_AUTOHSCROLL,ID_TITLE);make_label(app,L"Badge header",9002);make_control(app,L"EDIT",L"",WS_BORDER|ES_AUTOHSCROLL,ID_BADGE_HEADER);make_label(app,L"Value",9003);make_control(app,L"EDIT",L"",WS_BORDER|ES_AUTOHSCROLL,ID_VALUE);make_label(app,L"Description",9004);make_control(app,L"EDIT",L"",WS_BORDER|ES_MULTILINE|ES_AUTOVSCROLL|WS_VSCROLL,ID_DESCRIPTION);make_label(app,L"Artwork",9005);make_control(app,L"EDIT",L"",WS_BORDER|ES_AUTOHSCROLL,ID_IMAGE);button(ID_CHOOSE_IMAGE,L"Choose…");button(ID_REMOVE_IMAGE,L"Remove");
    const struct {int label;int edit;const wchar_t* text;} transforms[]={{9010,ID_IMAGE_X,L"X"},{9011,ID_IMAGE_Y,L"Y"},{9012,ID_IMAGE_SCALE,L"Scale"},{9013,ID_IMAGE_ROTATION,L"Rotation"},{9014,ID_CROP_LEFT,L"Crop L"},{9015,ID_CROP_TOP,L"Crop T"},{9016,ID_CROP_RIGHT,L"Crop R"},{9017,ID_CROP_BOTTOM,L"Crop B"}};
    for(auto& t:transforms){make_label(app,t.text,t.label);make_control(app,L"EDIT",L"0",WS_BORDER|ES_AUTOHSCROLL,t.edit);}make_label(app,L"Layer",9018);HWND layer=make_control(app,WC_COMBOBOXW,L"",CBS_DROPDOWNLIST|WS_VSCROLL,ID_IMAGE_LAYER);combo_add(layer,L"Behind badge",L"behind");combo_add(layer,L"In front",L"front");button(ID_RESET_TRANSFORM,L"Reset transform");

    make_control(app,L"STATIC",L"",SS_BITMAP|SS_CENTERIMAGE|WS_BORDER,ID_PREVIEW);make_control(app,TRACKBAR_CLASSW,L"",TBS_HORZ|TBS_NOTICKS,ID_TIMELINE);make_label(app,L"Frame 0",ID_FRAME_LABEL);button(ID_PLAY,L"Play");

    make_label(app,L"Project name",9101);make_control(app,L"EDIT",L"",WS_BORDER|ES_AUTOHSCROLL,ID_PROJECT_NAME);make_label(app,L"Width",9102);make_control(app,L"EDIT",L"1920",WS_BORDER|ES_NUMBER,ID_WIDTH);make_label(app,L"Height",9103);make_control(app,L"EDIT",L"1080",WS_BORDER|ES_NUMBER,ID_HEIGHT);make_label(app,L"FPS",9104);make_control(app,L"EDIT",L"60",WS_BORDER|ES_NUMBER,ID_FPS);make_control(app,L"BUTTON",L"Show badges",BS_AUTOCHECKBOX,ID_BADGES);make_control(app,L"BUTTON",L"Credits",BS_AUTOCHECKBOX,ID_CREDITS);make_control(app,L"BUTTON",L"Automatic duration",BS_AUTOCHECKBOX,ID_AUTO_LENGTH);make_label(app,L"Custom duration (seconds)",9105);make_control(app,L"EDIT",L"90",WS_BORDER|ES_AUTOHSCROLL,ID_CUSTOM_LENGTH);
    make_label(app,L"Intro",9106);HWND intro=make_control(app,WC_COMBOBOXW,L"",CBS_DROPDOWNLIST|WS_VSCROLL,ID_INTRO_MODE);combo_add(intro,L"Renderer default",L"renderer");combo_add(intro,L"Custom MP4",L"custom");combo_add(intro,L"Disabled",L"disabled");make_control(app,L"EDIT",L"",WS_BORDER|ES_AUTOHSCROLL,ID_INTRO_VIDEO);button(ID_CHOOSE_INTRO,L"Choose intro…");
    make_label(app,L"Soundtrack",9107);make_control(app,L"EDIT",L"",WS_BORDER|ES_AUTOHSCROLL,ID_SOUNDTRACK);button(ID_CHOOSE_SOUNDTRACK,L"Choose audio…");make_label(app,L"Volume 0–1",9108);make_control(app,L"EDIT",L"0.75",WS_BORDER|ES_AUTOHSCROLL,ID_SOUNDTRACK_VOLUME);make_control(app,L"BUTTON",L"Loop soundtrack",BS_AUTOCHECKBOX,ID_SOUNDTRACK_LOOP);
    make_label(app,L"Encoder",9109);HWND encoder=make_control(app,WC_COMBOBOXW,L"",CBS_DROPDOWNLIST|WS_VSCROLL,ID_ENCODER);combo_add(encoder,L"Auto",L"auto");combo_add(encoder,L"H.264",L"h264");combo_add(encoder,L"H.265",L"h265");make_label(app,L"Font family",9110);HWND font=make_control(app,WC_COMBOBOXW,L"",CBS_DROPDOWNLIST|WS_VSCROLL,ID_FONT_FAMILY);combo_add(font,L"Renderer default",L"");combo_add(font,L"Sans",L"sans-serif");combo_add(font,L"Condensed",L"sans-serif-condensed");combo_add(font,L"Serif",L"serif");combo_add(font,L"Mono",L"monospace");make_label(app,L"Font file",9111);make_control(app,L"EDIT",L"",WS_BORDER|ES_AUTOHSCROLL,ID_FONT_FILE);button(ID_CHOOSE_FONT,L"Choose font…");

    button(ID_NEW,L"New project");button(ID_OPEN,L"Open project");button(ID_SAVE,L"Save project");button(ID_IMPORT_DATA,L"Import data");button(ID_IMPORT_PACK,L"Import MegaPack");button(ID_IMPORT_RENDERER,L"Import renderer");button(ID_RENDERER_LIBRARY,L"Renderer library");button(ID_EXPORT,L"Export MP4");button(ID_CANCEL,L"Cancel export");EnableWindow(ctl(app,ID_CANCEL),FALSE);
    make_control(app,L"STATIC",L"Native C++ UI · CTS 3.0.300 project schema v6 · Renderer API v3 packages supported by the installed runtime",SS_LEFT|SS_NOPREFIX,ID_RUNTIME_INFO);
    make_control(app,PROGRESS_CLASSW,L"",PBS_SMOOTH,ID_PROGRESS);SendMessageW(ctl(app,ID_PROGRESS),PBM_SETRANGE,0,MAKELPARAM(0,100));make_label(app,L"Ready",ID_STATUS);
}

bool is_page_control(int id,Page page){
    if(id>=9001&&id<=9018)return page==Page::Cards;if(id>=9101&&id<=9111)return page==Page::Project;
    switch(page){
        case Page::Cards:return id==ID_CARD_LIST||id==ID_ADD_CARD||id==ID_DUP_CARD||id==ID_DELETE_CARD||id==ID_TITLE||id==ID_BADGE_HEADER||id==ID_VALUE||id==ID_DESCRIPTION||id==ID_IMAGE||id==ID_CHOOSE_IMAGE||id==ID_REMOVE_IMAGE||id==ID_IMAGE_X||id==ID_IMAGE_Y||id==ID_IMAGE_SCALE||id==ID_IMAGE_ROTATION||id==ID_CROP_LEFT||id==ID_CROP_TOP||id==ID_CROP_RIGHT||id==ID_CROP_BOTTOM||id==ID_IMAGE_LAYER||id==ID_RESET_TRANSFORM;
        case Page::Preview:return id==ID_PREVIEW||id==ID_TIMELINE||id==ID_FRAME_LABEL||id==ID_PLAY;
        case Page::Project:return id==ID_PROJECT_NAME||id==ID_WIDTH||id==ID_HEIGHT||id==ID_FPS||id==ID_BADGES||id==ID_CREDITS||id==ID_AUTO_LENGTH||id==ID_CUSTOM_LENGTH||id==ID_INTRO_MODE||id==ID_INTRO_VIDEO||id==ID_CHOOSE_INTRO||id==ID_SOUNDTRACK||id==ID_CHOOSE_SOUNDTRACK||id==ID_SOUNDTRACK_VOLUME||id==ID_SOUNDTRACK_LOOP||id==ID_ENCODER||id==ID_FONT_FAMILY||id==ID_FONT_FILE||id==ID_CHOOSE_FONT;
        case Page::More:return id==ID_NEW||id==ID_OPEN||id==ID_SAVE||id==ID_IMPORT_DATA||id==ID_IMPORT_PACK||id==ID_IMPORT_RENDERER||id==ID_RENDERER_LIBRARY||id==ID_EXPORT||id==ID_CANCEL||id==ID_RUNTIME_INFO;
    }return false;
}
void set_page(App* app,Page page){commit_project(app);app->page=page;for(int id=ID_CARD_LIST;id<=ID_RUNTIME_INFO;++id){HWND h=ctl(app,id);if(h)ShowWindow(h,is_page_control(id,page)?SW_SHOW:SW_HIDE);}for(int id=9001;id<=9018;++id){HWND h=ctl(app,id);if(h)ShowWindow(h,page==Page::Cards?SW_SHOW:SW_HIDE);}for(int id=9101;id<=9111;++id){HWND h=ctl(app,id);if(h)ShowWindow(h,page==Page::Project?SW_SHOW:SW_HIDE);}CheckDlgButton(app->window,ID_TAB_CARDS,page==Page::Cards?BST_CHECKED:BST_UNCHECKED);CheckDlgButton(app->window,ID_TAB_PREVIEW,page==Page::Preview?BST_CHECKED:BST_UNCHECKED);CheckDlgButton(app->window,ID_TAB_PROJECT,page==Page::Project?BST_CHECKED:BST_UNCHECKED);CheckDlgButton(app->window,ID_TAB_MORE,page==Page::More?BST_CHECKED:BST_UNCHECKED);if(page==Page::Preview){read_metadata(app);request_preview(app);}if(page==Page::Project)load_project_controls(app);}

void layout(App* app,int width,int height){const int m=12,tabh=36,statush=24,progressh=7,top=m+tabh+10,bottom=height-m-statush-progressh-8,bodyh=std::max(200,bottom-top);int tx=m;for(int id:{ID_TAB_CARDS,ID_TAB_PREVIEW,ID_TAB_PROJECT,ID_TAB_MORE}){MoveWindow(ctl(app,id),tx,m,100,tabh,TRUE);tx+=106;}MoveWindow(ctl(app,ID_PROGRESS),m,height-m-statush-progressh-2,width-2*m,progressh,TRUE);MoveWindow(ctl(app,ID_STATUS),m,height-m-statush,width-2*m,statush,TRUE);
    if(app->page==Page::Cards){const int left=std::clamp(width/4,240,330);MoveWindow(ctl(app,ID_CARD_LIST),m,top,left,bodyh-42,TRUE);MoveWindow(ctl(app,ID_ADD_CARD),m,bottom-34,(left-10)/3,34,TRUE);MoveWindow(ctl(app,ID_DUP_CARD),m+(left-10)/3+5,bottom-34,(left-10)/3,34,TRUE);MoveWindow(ctl(app,ID_DELETE_CARD),m+2*((left-10)/3+5),bottom-34,(left-10)/3,34,TRUE);const int x=m+left+16,w=std::max(300,width-x-m),labelw=104,fieldx=x+labelw,fieldw=w-labelw;int y=top;auto row=[&](int lid,int eid,int h=30){MoveWindow(ctl(app,lid),x,y,labelw-8,24,TRUE);MoveWindow(ctl(app,eid),fieldx,y,fieldw,h,TRUE);y+=h+8;};row(9001,ID_TITLE);row(9002,ID_BADGE_HEADER);row(9003,ID_VALUE);row(9004,ID_DESCRIPTION,80);row(9005,ID_IMAGE);MoveWindow(ctl(app,ID_CHOOSE_IMAGE),fieldx,y,120,30,TRUE);MoveWindow(ctl(app,ID_REMOVE_IMAGE),fieldx+126,y,100,30,TRUE);y+=40;const int cell=(w-12)/4;const int labels[]={9010,9011,9012,9013,9014,9015,9016,9017};const int edits[]={ID_IMAGE_X,ID_IMAGE_Y,ID_IMAGE_SCALE,ID_IMAGE_ROTATION,ID_CROP_LEFT,ID_CROP_TOP,ID_CROP_RIGHT,ID_CROP_BOTTOM};for(int r=0;r<2;++r){for(int c=0;c<4;++c){int i=r*4+c,xx=x+c*(cell+4);MoveWindow(ctl(app,labels[i]),xx,y,cell,20,TRUE);MoveWindow(ctl(app,edits[i]),xx,y+20,cell,28,TRUE);}y+=54;}MoveWindow(ctl(app,9018),x,y,80,20,TRUE);MoveWindow(ctl(app,ID_IMAGE_LAYER),x+80,y,170,300,TRUE);MoveWindow(ctl(app,ID_RESET_TRANSFORM),x+260,y,140,30,TRUE);}
    else if(app->page==Page::Preview){int pw=std::min(width-2*m,960),ph=std::min(bodyh-80,pw*9/16);pw=std::min(pw,ph*16/9);ph=pw*9/16;const int x=(width-pw)/2,y=top;MoveWindow(ctl(app,ID_PREVIEW),x,y,pw,ph,TRUE);MoveWindow(ctl(app,ID_TIMELINE),x,y+ph+10,pw-90,32,TRUE);MoveWindow(ctl(app,ID_PLAY),x+pw-82,y+ph+10,82,32,TRUE);MoveWindow(ctl(app,ID_FRAME_LABEL),x,y+ph+48,pw,26,TRUE);}
    else if(app->page==Page::Project){const int colw=(width-3*m)/2,x1=m,x2=2*m+colw;int y1=top,y2=top;auto place=[&](int x,int& y,int lid,int eid,int h=30){MoveWindow(ctl(app,lid),x,y,150,24,TRUE);MoveWindow(ctl(app,eid),x+155,y,colw-155,h,TRUE);y+=h+10;};place(x1,y1,9101,ID_PROJECT_NAME);place(x1,y1,9102,ID_WIDTH);place(x1,y1,9103,ID_HEIGHT);place(x1,y1,9104,ID_FPS);MoveWindow(ctl(app,ID_BADGES),x1,y1,colw/2,28,TRUE);MoveWindow(ctl(app,ID_CREDITS),x1+colw/2,y1,colw/2,28,TRUE);y1+=38;MoveWindow(ctl(app,ID_AUTO_LENGTH),x1,y1,colw,28,TRUE);y1+=34;place(x1,y1,9105,ID_CUSTOM_LENGTH);place(x1,y1,9109,ID_ENCODER);place(x1,y1,9110,ID_FONT_FAMILY);place(x1,y1,9111,ID_FONT_FILE);MoveWindow(ctl(app,ID_CHOOSE_FONT),x1+155,y1,colw-155,30,TRUE);
        place(x2,y2,9106,ID_INTRO_MODE);MoveWindow(ctl(app,ID_INTRO_VIDEO),x2,y2,colw-130,30,TRUE);MoveWindow(ctl(app,ID_CHOOSE_INTRO),x2+colw-124,y2,124,30,TRUE);y2+=40;MoveWindow(ctl(app,9107),x2,y2,150,24,TRUE);y2+=24;MoveWindow(ctl(app,ID_SOUNDTRACK),x2,y2,colw-130,30,TRUE);MoveWindow(ctl(app,ID_CHOOSE_SOUNDTRACK),x2+colw-124,y2,124,30,TRUE);y2+=40;place(x2,y2,9108,ID_SOUNDTRACK_VOLUME);MoveWindow(ctl(app,ID_SOUNDTRACK_LOOP),x2,y2,colw,28,TRUE);}
    else {const int bw=220,bh=42,gap=12,x=m,y=top;const int ids[]={ID_NEW,ID_OPEN,ID_SAVE,ID_IMPORT_DATA,ID_IMPORT_PACK,ID_IMPORT_RENDERER,ID_RENDERER_LIBRARY,ID_EXPORT,ID_CANCEL};for(int i=0;i<9;++i){MoveWindow(ctl(app,ids[i]),x+(i%3)*(bw+gap),y+(i/3)*(bh+gap),bw,bh,TRUE);}MoveWindow(ctl(app,ID_RUNTIME_INFO),x,y+3*(bh+gap)+14,std::min(width-2*m,760),70,TRUE);}
}

bool run_self_test(){try{cubical::Project p;p.name="Windows C++ 3.0.300 self-test";p.cards={cubical::Card{"Card 1","1","",""},cubical::Card{"Card 2","2","",""}};p.cards[0].badge_header="Before";const auto json=cubical::temporary_path("cts30300-selftest",".json"),ccx=cubical::temporary_path("cts30300-selftest",".ccx"),bmp=cubical::temporary_path("cts30300-selftest",".bmp");std::string error;if(!cubical::save_project_json(p,json,&error))return false;cubical::Project loaded;if(!cubical::load_project_json(loaded,json,&error)||loaded.cards[0].badge_header!="Before")return false;if(!cubical::save_ccx(loaded,ccx,&error))return false;auto r=cubical::run_engine({"render-preview",narrow_path(ccx),narrow_path(bmp),"--frame","100","--width","960","--height","540"});std::error_code ec;const bool ok=r.exit_code==0&&fs::is_regular_file(bmp,ec)&&fs::file_size(bmp,ec)>4096;fs::remove(json,ec);fs::remove(ccx,ec);fs::remove(bmp,ec);return ok;}catch(...){return false;}}

LRESULT CALLBACK window_proc(HWND window,UINT message,WPARAM wparam,LPARAM lparam){auto* app=reinterpret_cast<App*>(GetWindowLongPtrW(window,GWLP_USERDATA));if(message==WM_NCCREATE){auto* create=reinterpret_cast<CREATESTRUCTW*>(lparam);app=reinterpret_cast<App*>(create->lpCreateParams);SetWindowLongPtrW(window,GWLP_USERDATA,reinterpret_cast<LONG_PTR>(app));app->window=window;}if(!app)return DefWindowProcW(window,message,wparam,lparam);
    switch(message){
        case WM_CREATE:{create_ui(app);app->working_ccx=cubical::temporary_path("cts30300-working",".ccx");SetTimer(window,kAutosaveTimer,1000,nullptr);if(fs::exists(autosave_path())){cubical::Project recovered;std::string e;if(cubical::load_project_json(recovered,autosave_path(),&e))app->project=std::move(recovered);}rebuild_card_list(app);load_card(app);load_project_controls(app);set_page(app,Page::Cards);read_metadata(app);return 0;}
        case WM_SIZE:layout(app,LOWORD(lparam),HIWORD(lparam));return 0;
        case WM_COMMAND:{const int id=LOWORD(wparam),code=HIWORD(wparam);if(id==ID_TAB_CARDS){set_page(app,Page::Cards);RECT r;GetClientRect(window,&r);layout(app,r.right,r.bottom);return 0;}if(id==ID_TAB_PREVIEW){set_page(app,Page::Preview);RECT r;GetClientRect(window,&r);layout(app,r.right,r.bottom);return 0;}if(id==ID_TAB_PROJECT){set_page(app,Page::Project);RECT r;GetClientRect(window,&r);layout(app,r.right,r.bottom);return 0;}if(id==ID_TAB_MORE){set_page(app,Page::More);RECT r;GetClientRect(window,&r);layout(app,r.right,r.bottom);return 0;}
            if(id==ID_CARD_LIST&&code==LBN_SELCHANGE){commit_card(app);const int sel=static_cast<int>(SendMessageW(ctl(app,ID_CARD_LIST),LB_GETCURSEL,0,0));if(sel>=0){app->selected=sel;load_card(app);}return 0;}
            if(code==EN_KILLFOCUS||code==CBN_SELCHANGE||(code==BN_CLICKED&&(id==ID_BADGES||id==ID_CREDITS||id==ID_AUTO_LENGTH||id==ID_SOUNDTRACK_LOOP))){commit_project(app);rebuild_card_list(app);if(app->page==Page::Preview)request_preview(app);return 0;}
            switch(id){
                case ID_ADD_CARD:{commit_card(app);cubical::Card c;c.title="Card "+std::to_string(app->project.cards.size()+1);app->project.cards.push_back(std::move(c));app->selected=static_cast<int>(app->project.cards.size())-1;app->dirty=true;rebuild_card_list(app);load_card(app);read_metadata(app);return 0;}
                case ID_DUP_CARD:{if(app->selected>=0&&app->selected<(int)app->project.cards.size()){commit_card(app);auto c=app->project.cards[app->selected];c.id=cubical::new_card_id();app->project.cards.insert(app->project.cards.begin()+app->selected+1,std::move(c));++app->selected;app->dirty=true;rebuild_card_list(app);load_card(app);read_metadata(app);}return 0;}
                case ID_DELETE_CARD:{if(app->project.cards.size()>1&&app->selected>=0){app->project.cards.erase(app->project.cards.begin()+app->selected);app->selected=std::min(app->selected,(int)app->project.cards.size()-1);app->dirty=true;rebuild_card_list(app);load_card(app);read_metadata(app);}return 0;}
                case ID_CHOOSE_IMAGE:{const auto f=choose_file(window,false,L"Images\0*.png;*.jpg;*.jpeg;*.webp;*.bmp\0All files\0*.*\0\0");if(!f.empty()){SetWindowTextW(ctl(app,ID_IMAGE),f.c_str());commit_card(app);request_preview(app);}return 0;}
                case ID_REMOVE_IMAGE:SetWindowTextW(ctl(app,ID_IMAGE),L"");commit_card(app);request_preview(app);return 0;
                case ID_RESET_TRANSFORM:{for(int x:{ID_IMAGE_X,ID_IMAGE_Y,ID_IMAGE_ROTATION,ID_CROP_LEFT,ID_CROP_TOP,ID_CROP_RIGHT,ID_CROP_BOTTOM})SetWindowTextW(ctl(app,x),L"0");SetWindowTextW(ctl(app,ID_IMAGE_SCALE),L"1");combo_select_value(ctl(app,ID_IMAGE_LAYER),L"behind");commit_card(app);request_preview(app);return 0;}
                case ID_PLAY:{app->playing=!app->playing;SetWindowTextW(ctl(app,ID_PLAY),app->playing?L"Pause":L"Play");if(app->playing){app->play_started=std::chrono::steady_clock::now();app->play_start_frame=app->current_frame;SetTimer(window,kPlaybackTimer,16,nullptr);}else KillTimer(window,kPlaybackTimer);return 0;}
                case ID_CHOOSE_INTRO:{const auto f=choose_file(window,false,L"MP4 video\0*.mp4\0All files\0*.*\0\0");if(!f.empty()){SetWindowTextW(ctl(app,ID_INTRO_VIDEO),f.c_str());combo_select_value(ctl(app,ID_INTRO_MODE),L"custom");commit_project(app);}return 0;}
                case ID_CHOOSE_SOUNDTRACK:{const auto f=choose_file(window,false,L"Audio\0*.mp3;*.m4a;*.aac;*.wav;*.flac;*.ogg\0All files\0*.*\0\0");if(!f.empty()){SetWindowTextW(ctl(app,ID_SOUNDTRACK),f.c_str());commit_project(app);}return 0;}
                case ID_CHOOSE_FONT:{const auto f=choose_file(window,false,L"Fonts\0*.ttf;*.otf\0All files\0*.*\0\0");if(!f.empty()){SetWindowTextW(ctl(app,ID_FONT_FILE),f.c_str());commit_project(app);}return 0;}
                case ID_NEW:new_project(app);return 0;case ID_OPEN:open_project(app);return 0;case ID_SAVE:save_project(app,false);return 0;
                case ID_IMPORT_DATA:{const auto f=choose_file(window,false,L"Data\0*.csv;*.tsv;*.xlsx\0All files\0*.*\0\0");if(!f.empty())begin_import(app,TaskResult::Kind::ImportData,f);return 0;}
                case ID_IMPORT_PACK:{const auto f=choose_file(window,false,L"MegaPack ZIP\0*.zip\0All files\0*.*\0\0");if(!f.empty())begin_import(app,TaskResult::Kind::ImportPack,f);return 0;}
                case ID_IMPORT_RENDERER:import_renderer(app);return 0;case ID_RENDERER_LIBRARY:open_renderer_library();return 0;
                case ID_EXPORT:{const auto f=choose_file(window,true,L"MP4 video\0*.mp4\0All files\0*.*\0\0",L"mp4");if(!f.empty())begin_export(app,f);return 0;}case ID_CANCEL:cancel_task(app);return 0;
            }break;}
        case WM_HSCROLL:if(reinterpret_cast<HWND>(lparam)==ctl(app,ID_TIMELINE)){app->current_frame=static_cast<int>(SendMessageW(ctl(app,ID_TIMELINE),TBM_GETPOS,0,0));update_frame_label(app);if(LOWORD(wparam)==TB_ENDTRACK||LOWORD(wparam)==TB_THUMBPOSITION||LOWORD(wparam)==TB_THUMBTRACK)request_preview(app);return 0;}break;
        case WM_TIMER:if(wparam==kAutosaveTimer){autosave(app);return 0;}if(wparam==kProgressTimer){std::ifstream in(app->progress_file);int pct=0;if(in>>pct)SendMessageW(ctl(app,ID_PROGRESS),PBM_SETPOS,std::clamp(pct,0,100),0);return 0;}if(wparam==kPlaybackTimer&&app->playing){const double elapsed=std::chrono::duration<double>(std::chrono::steady_clock::now()-app->play_started).count();app->current_frame=app->play_start_frame+static_cast<int>(elapsed*app->project.settings.fps);if(app->current_frame>=app->total_frames){app->current_frame=app->total_frames-1;app->playing=false;KillTimer(window,kPlaybackTimer);SetWindowTextW(ctl(app,ID_PLAY),L"Play");}SendMessageW(ctl(app,ID_TIMELINE),TBM_SETPOS,TRUE,app->current_frame);update_frame_label(app);if(app->current_frame%std::max(1,(int)app->project.settings.fps/10)==0)request_preview(app);return 0;}break;
        case WM_PREVIEW_DONE:{std::unique_ptr<PreviewResult> r(reinterpret_cast<PreviewResult*>(lparam));if(r->generation!=app->preview_generation.load())return 0;if(!r->error.empty()){status(app,"Preview error: "+r->error);return 0;}HBITMAP bmp=static_cast<HBITMAP>(LoadImageW(nullptr,r->bitmap.wstring().c_str(),IMAGE_BITMAP,0,0,LR_LOADFROMFILE|LR_CREATEDIBSECTION));std::error_code ec;fs::remove(r->bitmap,ec);if(bmp){replace_bitmap(app,bmp);status(app,"Preview ready");}return 0;}
        case WM_TASK_DONE:{std::unique_ptr<TaskResult> t(reinterpret_cast<TaskResult*>(lparam));KillTimer(window,kProgressTimer);set_busy(app,false,t->process.exit_code==0?"Ready":"Task failed");if(t->process.exit_code!=0){MessageBoxW(window,wide(t->process.output).c_str(),L"Operation failed",MB_ICONERROR);return 0;}if(t->kind==TaskResult::Kind::ImportData||t->kind==TaskResult::Kind::ImportPack){cubical::Project p;std::string e;if(cubical::load_ccx(p,t->output,&e)){app->project=std::move(p);app->selected=0;app->dirty=true;rebuild_card_list(app);load_card(app);load_project_controls(app);read_metadata(app);request_preview(app);status(app,t->kind==TaskResult::Kind::ImportPack?"MegaPack imported":"Data imported");}else MessageBoxW(window,wide(e).c_str(),L"Import failed",MB_ICONERROR);std::error_code ec;fs::remove(t->output,ec);}else{status(app,"Export complete: "+narrow_path(t->output));ShellExecuteW(nullptr,L"open",t->output.parent_path().wstring().c_str(),nullptr,nullptr,SW_SHOWNORMAL);}if(app->pending_close){app->pending_close=false;PostMessageW(window,WM_CLOSE,0,0);}return 0;}
        case WM_CLOSE:if(app->busy){app->pending_close=true;cancel_task(app);status(app,"Cancelling before close…");return 0;}autosave(app);DestroyWindow(window);return 0;
        case WM_DESTROY:{KillTimer(window,kAutosaveTimer);KillTimer(window,kPlaybackTimer);free_combo_data(ctl(app,ID_IMAGE_LAYER));free_combo_data(ctl(app,ID_INTRO_MODE));free_combo_data(ctl(app,ID_ENCODER));free_combo_data(ctl(app,ID_FONT_FAMILY));if(app->preview_bitmap)DeleteObject(app->preview_bitmap);if(app->font)DeleteObject(app->font);std::error_code ec;fs::remove(app->working_ccx,ec);PostQuitMessage(0);return 0;}
    }return DefWindowProcW(window,message,wparam,lparam);
}

} // namespace

int WINAPI wWinMain(HINSTANCE instance,HINSTANCE,LPWSTR command_line,int show){if(command_line&&std::wstring(command_line).find(L"--self-test")!=std::wstring::npos)return run_self_test()?0:2;INITCOMMONCONTROLSEX common{sizeof(common),ICC_WIN95_CLASSES|ICC_BAR_CLASSES|ICC_PROGRESS_CLASS};InitCommonControlsEx(&common);CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);App app{};app.instance=instance;WNDCLASSEXW wc{sizeof(wc)};wc.style=CS_HREDRAW|CS_VREDRAW;wc.lpfnWndProc=window_proc;wc.hInstance=instance;wc.hCursor=LoadCursor(nullptr,IDC_ARROW);wc.hIcon=LoadIcon(nullptr,IDI_APPLICATION);wc.hbrBackground=reinterpret_cast<HBRUSH>(COLOR_WINDOW+1);wc.lpszClassName=kWindowClass;if(!RegisterClassExW(&wc))return 1;HWND window=CreateWindowExW(0,kWindowClass,kTitle,WS_OVERLAPPEDWINDOW|WS_CLIPCHILDREN,CW_USEDEFAULT,CW_USEDEFAULT,1240,820,nullptr,nullptr,instance,&app);if(!window)return 1;ShowWindow(window,show);UpdateWindow(window);MSG msg{};while(GetMessageW(&msg,nullptr,0,0)>0){TranslateMessage(&msg);DispatchMessageW(&msg);}CoUninitialize();return static_cast<int>(msg.wParam);}
