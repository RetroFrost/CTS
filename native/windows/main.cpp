#define UNICODE
#define _UNICODE
#include <windows.h>
#include <windowsx.h>
#include <commctrl.h>
#include <commdlg.h>
#include <shobjidl.h>
#include <shlobj.h>
#include <fstream>
#include <shellapi.h>
#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <cctype>
#include <chrono>
#include <cmath>
#include <iomanip>
#include <sstream>
#include <filesystem>
#include <memory>
#include <string>
#include <thread>
#include <vector>
#include "cubical/process.hpp"
#include "cubical/project.hpp"

#pragma comment(lib, "comctl32.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "uuid.lib")
#pragma comment(lib, "comdlg32.lib")

namespace fs = std::filesystem;
namespace {
constexpr wchar_t kMainClass[] = L"CubicalCompareMainWindow";
constexpr wchar_t kControlsClass[] = L"CubicalCompareControlsWindow";
constexpr UINT WM_EXPORT_DONE = WM_APP + 42;
constexpr UINT WM_PREVIEW_READY = WM_APP + 43;
constexpr UINT WM_TASK_DONE = WM_APP + 44;
constexpr UINT PLAYER_TIMER = 7;
constexpr UINT TASK_TIMER = 8;

enum Id {
    ID_NEW=100, ID_OPEN, ID_SAVE, ID_INSERT, ID_SHEET, ID_PREVIEW, ID_EXPORT, ID_CONTROLS,
    ID_CARD_LIST, ID_TITLE, ID_VALUE, ID_DESCRIPTION, ID_IMAGE, ID_CHOOSE_IMAGE, ID_ADD, ID_REMOVE, ID_TRIM,
    ID_PLAY, ID_RESTART, ID_SEEK, ID_TIME_LABEL, ID_STATUS, ID_PREVIEW_BITMAP, ID_MUSIC, ID_MODEL,
    ID_TAB=500, ID_APPLY_CONTROLS, ID_FONT_TITLE, ID_FONT_DESCRIPTION, ID_FONT_BADGE, ID_FONT_CREDITS, ID_REUSE_FONT, ID_TRANSFORM_RESET,
};

struct AppState {
    HINSTANCE instance{};
    HWND window{}, status{}, card_list{}, preview{}, play_button{}, seek{}, time_label{};
    HWND sheet_rows{}, sheet_columns{}, sheet_start{};
    HWND title{}, value{}, description{}, image{};
    HFONT ui_font{};
    HBITMAP preview_bitmap{};
    cubical::Project project;
    int selected{0};
    std::string selected_card_id;
    bool loading{false};
    bool busy{false};
    bool task_is_export{false};
    bool pending_close{false};
    bool closing{false};
    std::atomic<int> preview_jobs{0};
    IProgressDialog* task_dialog{};
    fs::path task_progress_path;
    fs::path task_cancel_path;
    fs::path working_ccx;
    fs::path preview_path;
    bool playing{false};
    bool preview_rendering{false};
    bool preview_pending{false};
    bool updating_seek{false};
    double current_time{0.0};
    double duration{0.0};
    double pending_time{0.0};
    double play_anchor_time{0.0};
    std::chrono::steady_clock::time_point play_anchor{};
    bool image_dragging{false};
    POINT image_drag_last{};
};

enum class TaskKind { ExportVideo, ImportSheet, ImportData };
struct TaskResult {
    AppState* state{};
    TaskKind kind{TaskKind::ExportVideo};
    cubical::ProcessResult process;
    fs::path output_ccx;
    fs::path assets;
    std::string target_path;
};

struct PreviewResult {
    AppState* state{};
    fs::path image_path;
    fs::path project_snapshot;
    double time{0.0};
    std::string error;
};

struct ControlsState {
    AppState* app{};
    HWND window{}, tabs{};
    std::vector<HWND> page_text, page_audio, page_output, page_transform;
    HWND project_name{}, model{}, credits_enabled{};
    std::vector<HWND> text_fields;
    HWND soundtrack{}, loop{}, volume{}, offset{}, fade{};
    HWND width{}, height{}, fps{}, preset{}, crf{}, fit_mode{};
    std::vector<HWND> font_fields;
    HWND image_x{}, image_y{}, image_scale{}, image_rotation{};
    HWND crop_left{}, crop_top{}, crop_right{}, crop_bottom{}, image_front{};
};

std::string utf8(const std::wstring& value) {
    if (value.empty()) return {};
    int size = WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0, nullptr, nullptr);
    std::string out(size, '\0'); WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), out.data(), size, nullptr, nullptr); return out;
}
std::wstring wide(const std::string& value) {
    if (value.empty()) return {};
    int size = MultiByteToWideChar(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0);
    std::wstring out(size, L'\0'); MultiByteToWideChar(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), out.data(), size); return out;
}
std::wstring text(HWND h) { int n = GetWindowTextLengthW(h); std::wstring v(static_cast<std::size_t>(n) + 1, L'\0'); GetWindowTextW(h, v.data(), n + 1); v.resize(static_cast<std::size_t>(n)); return v; }
void set_text(HWND h, const std::string& value) { SetWindowTextW(h, wide(value).c_str()); }
void set_status(AppState* s, const std::string& value) { SetWindowTextW(s->status, wide(value).c_str()); }
std::string lower_extension(const fs::path& path) {
    std::string value = path.extension().string();
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char ch){ return static_cast<char>(std::tolower(ch)); });
    return value;
}
fs::path persistent_asset_directory(const char* role) {
    const char* local = std::getenv("LOCALAPPDATA");
    fs::path root = local && *local ? fs::path(local) : fs::temp_directory_path();
    const auto ticks = std::chrono::high_resolution_clock::now().time_since_epoch().count();
    fs::path output = root / "Cubical Compare" / "Imported Assets" / (std::string(role) + "-" + std::to_string(ticks));
    fs::create_directories(output);
    return output;
}
bool valid_mp4_file(const fs::path& path) {
    std::error_code ec;
    if (!fs::is_regular_file(path, ec) || fs::file_size(path, ec) < 256) return false;
    std::ifstream in(path, std::ios::binary);
    std::string header(64, '\0');
    in.read(header.data(), static_cast<std::streamsize>(header.size()));
    header.resize(static_cast<std::size_t>(std::max<std::streamsize>(0, in.gcount())));
    return header.find("ftyp") != std::string::npos;
}
void set_font(HWND h, HFONT font) { SendMessageW(h, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE); }

int selected_index(AppState* s) {
    const int by_id = cubical::find_card_index_by_id(s->project, s->selected_card_id);
    if (by_id >= 0) { s->selected = by_id; return by_id; }
    if (s->selected >= 0 && s->selected < static_cast<int>(s->project.cards.size())) {
        s->selected_card_id = s->project.cards[static_cast<std::size_t>(s->selected)].id;
        return s->selected;
    }
    return -1;
}
void select_index(AppState* s, int index) {
    if (s->project.cards.empty()) { s->selected=-1; s->selected_card_id.clear(); return; }
    s->selected=std::clamp(index,0,static_cast<int>(s->project.cards.size())-1);
    s->selected_card_id=s->project.cards[static_cast<std::size_t>(s->selected)].id;
}

HWND make_control(AppState* s, const wchar_t* cls, const wchar_t* caption, DWORD style, int x, int y, int w, int h, HWND parent, int id=0, DWORD ex=0) {
    HWND result = CreateWindowExW(ex, cls, caption, WS_CHILD|WS_VISIBLE|style, x,y,w,h,parent, reinterpret_cast<HMENU>(static_cast<INT_PTR>(id)), s->instance, nullptr);
    set_font(result, s->ui_font); return result;
}

std::wstring choose_shell_file(HWND owner, bool save, const wchar_t* title, const COMDLG_FILTERSPEC* filters, UINT count, const wchar_t* default_extension=nullptr) {
    IFileDialog* dialog = nullptr; std::wstring result;
    HRESULT hr = CoCreateInstance(save ? CLSID_FileSaveDialog : CLSID_FileOpenDialog, nullptr, CLSCTX_INPROC_SERVER,
        save ? IID_IFileSaveDialog : IID_IFileOpenDialog, reinterpret_cast<void**>(&dialog));
    if (FAILED(hr) || !dialog) return result;
    dialog->SetTitle(title); if (filters && count) dialog->SetFileTypes(count, filters);
    if (save && default_extension) static_cast<IFileSaveDialog*>(dialog)->SetDefaultExtension(default_extension);
    if (SUCCEEDED(dialog->Show(owner))) {
        IShellItem* item = nullptr; if (SUCCEEDED(dialog->GetResult(&item))) {
            PWSTR path = nullptr; if (SUCCEEDED(item->GetDisplayName(SIGDN_FILESYSPATH, &path))) { result = path; CoTaskMemFree(path); }
            item->Release();
        }
    }
    dialog->Release(); return result;
}

bool write_working(AppState* s) {
    const int index = selected_index(s);
    if (index >= 0) {
        auto& c = s->project.cards[static_cast<std::size_t>(index)];
        c.title=utf8(text(s->title)); c.value=utf8(text(s->value)); c.description=utf8(text(s->description)); c.image=utf8(text(s->image));
    }
    std::string error; if (!cubical::save_ccx(s->project, s->working_ccx, &error)) { set_status(s,error); return false; } return true;
}

void rebuild_cards(AppState* s) {
    SendMessageW(s->card_list, LB_RESETCONTENT, 0, 0);
    for (std::size_t i=0;i<s->project.cards.size();++i) {
        std::wstring label=std::to_wstring(i+1)+L". "+wide(s->project.cards[i].title.empty()?"Untitled card":s->project.cards[i].title);
        SendMessageW(s->card_list, LB_ADDSTRING, 0, reinterpret_cast<LPARAM>(label.c_str()));
    }
    if (!s->project.cards.empty()) { int index=cubical::find_card_index_by_id(s->project,s->selected_card_id); if(index<0)index=std::clamp(s->selected,0,static_cast<int>(s->project.cards.size())-1);select_index(s,index);SendMessageW(s->card_list,LB_SETCURSEL,s->selected,0); }
}
void load_card(AppState* s) {
    s->loading=true; const int index=selected_index(s); if (index>=0) {
        const auto& c=s->project.cards[static_cast<std::size_t>(index)]; set_text(s->title,c.title);set_text(s->value,c.value);set_text(s->description,c.description);set_text(s->image,c.image);
    } s->loading=false;
}
void load_project(AppState* s) { rebuild_cards(s); load_card(s); set_status(s,cubical::summary(s->project)); }

std::wstring format_time(double seconds) {
    seconds = std::max(0.0, seconds);
    const int total = static_cast<int>(seconds);
    const int minutes = total / 60;
    const int secs = total % 60;
    const int tenths = static_cast<int>((seconds - total) * 10.0 + 0.5) % 10;
    std::wostringstream out;
    out << std::setfill(L'0') << std::setw(2) << minutes << L':' << std::setw(2) << secs << L'.' << tenths;
    return out.str();
}

HBITMAP load_scaled_bitmap(const fs::path& path, int target_width, int target_height) {
    HBITMAP source = static_cast<HBITMAP>(LoadImageW(nullptr, path.wstring().c_str(), IMAGE_BITMAP, 0, 0, LR_LOADFROMFILE | LR_CREATEDIBSECTION));
    if (!source) return nullptr;
    BITMAP info{};
    GetObjectW(source, sizeof(info), &info);
    HDC screen = GetDC(nullptr);
    HDC source_dc = CreateCompatibleDC(screen);
    HDC target_dc = CreateCompatibleDC(screen);
    HBITMAP target = CreateCompatibleBitmap(screen, target_width, target_height);
    HGDIOBJ old_source = SelectObject(source_dc, source);
    HGDIOBJ old_target = SelectObject(target_dc, target);
    SetStretchBltMode(target_dc, HALFTONE);
    SetBrushOrgEx(target_dc, 0, 0, nullptr);
    const BOOL ok = StretchBlt(target_dc, 0, 0, target_width, target_height, source_dc, 0, 0, info.bmWidth, info.bmHeight, SRCCOPY);
    SelectObject(source_dc, old_source);
    SelectObject(target_dc, old_target);
    DeleteDC(source_dc);
    DeleteDC(target_dc);
    ReleaseDC(nullptr, screen);
    DeleteObject(source);
    if (!ok) { DeleteObject(target); return nullptr; }
    return target;
}

void update_player_ui(AppState* s) {
    s->current_time = std::clamp(s->current_time, 0.0, std::max(0.0, s->duration));
    s->updating_seek = true;
    const int position = s->duration > 0.0 ? static_cast<int>(std::round(s->current_time * 10000.0 / s->duration)) : 0;
    SendMessageW(s->seek, TBM_SETPOS, TRUE, std::clamp(position, 0, 10000));
    s->updating_seek = false;
    const std::wstring label = format_time(s->current_time) + L" / " + format_time(s->duration);
    SetWindowTextW(s->time_label, label.c_str());
    SetWindowTextW(s->play_button, s->playing ? L"Pause" : L"Play");
}

void start_preview_render(AppState* s, double requested_time);

void request_preview(AppState* s, double requested_time, bool sync_project = true) {
    if (sync_project && !write_working(s)) return;
    s->duration = cubical::timeline_duration(s->project);
    s->current_time = std::clamp(requested_time, 0.0, std::max(0.0, s->duration));
    update_player_ui(s);
    start_preview_render(s, s->current_time);
}

LRESULT CALLBACK PreviewSubclassProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp, UINT_PTR, DWORD_PTR ref_data) {
    auto* s = reinterpret_cast<AppState*>(ref_data);
    if (!s) return DefSubclassProc(hwnd, msg, wp, lp);
    auto selected_card = [&]() -> cubical::Card* {
        const int index = selected_index(s);
        return index >= 0 ? &s->project.cards[static_cast<std::size_t>(index)] : nullptr;
    };
    auto commit_transform = [&]() {
        if (write_working(s)) request_preview(s, s->current_time, false);
    };
    switch (msg) {
        case WM_LBUTTONDOWN: {
            auto* card = selected_card();
            if (!card || card->image.empty()) {
                set_status(s, "Choose an image before using free transform.");
                return 0;
            }
            s->image_dragging = true;
            s->image_drag_last = {GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
            SetCapture(hwnd);
            set_status(s, "Free transform: drag to move; wheel scales; Ctrl+wheel rotates; Shift+wheel crops.");
            return 0;
        }
        case WM_MOUSEMOVE:
            if (s->image_dragging && (wp & MK_LBUTTON)) {
                auto* card = selected_card();
                if (!card) return 0;
                POINT current{GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
                RECT rect{}; GetClientRect(hwnd, &rect);
                const double preview_width = std::max(1L, rect.right - rect.left);
                const double preview_height = std::max(1L, rect.bottom - rect.top);
                card->image_x += (current.x - s->image_drag_last.x) * 1920.0 / preview_width;
                card->image_y += (current.y - s->image_drag_last.y) * 1080.0 / preview_height;
                s->image_drag_last = current;
                commit_transform();
                return 0;
            }
            break;
        case WM_LBUTTONUP:
        case WM_CAPTURECHANGED:
            if (s->image_dragging) {
                s->image_dragging = false;
                if (GetCapture() == hwnd) ReleaseCapture();
                commit_transform();
            }
            return 0;
        case WM_MOUSEWHEEL: {
            auto* card = selected_card();
            if (!card || card->image.empty()) return 0;
            const double steps = static_cast<double>(GET_WHEEL_DELTA_WPARAM(wp)) / WHEEL_DELTA;
            const UINT keys = GET_KEYSTATE_WPARAM(wp);
            if (keys & MK_CONTROL) {
                card->image_rotation += steps * 5.0;
            } else if (keys & MK_SHIFT) {
                const double amount = steps * 0.02;
                POINT point{}; GetCursorPos(&point); ScreenToClient(hwnd, &point);
                RECT rect{}; GetClientRect(hwnd, &rect);
                const int distances[] = {point.x, point.y, std::max(0, rect.right-point.x), std::max(0, rect.bottom-point.y)};
                const int edge = static_cast<int>(std::min_element(std::begin(distances), std::end(distances)) - std::begin(distances));
                double* crops[] = {&card->image_crop_left,&card->image_crop_top,&card->image_crop_right,&card->image_crop_bottom};
                *crops[edge] = std::clamp(*crops[edge] + amount, 0.0, 0.49);
            } else {
                card->image_scale = std::clamp(card->image_scale * std::pow(1.10, steps), 0.05, 8.0);
            }
            commit_transform();
            return 0;
        }
        case WM_NCDESTROY:
            RemoveWindowSubclass(hwnd, PreviewSubclassProc, 1);
            break;
    }
    return DefSubclassProc(hwnd, msg, wp, lp);
}

void start_preview_render(AppState* s, double requested_time) {
    if (s->preview_rendering) {
        s->preview_pending = true;
        s->pending_time = requested_time;
        return;
    }
    if (s->closing) return;
    s->preview_rendering = true;
    ++s->preview_jobs;
    const auto snapshot = cubical::temporary_path("cubical-compare-preview-project", ".ccx");
    const auto output = cubical::temporary_path("cubical-compare-preview", ".bmp");
    std::error_code copy_error;
    fs::copy_file(s->working_ccx, snapshot, fs::copy_options::overwrite_existing, copy_error);
    if (copy_error) {
        s->preview_rendering = false;
        --s->preview_jobs;
        set_status(s, "Could not prepare preview project: " + copy_error.message());
        return;
    }
    HWND window = s->window;
    std::thread([s, window, snapshot, output, requested_time]() {
        const auto result = cubical::run_engine({
            "render-preview", snapshot.string(), output.string(), "--time", std::to_string(requested_time)
        });
        auto* payload = new PreviewResult{
            s, output, snapshot, requested_time,
            result.exit_code == 0 && fs::exists(output)
                ? std::string{}
                : (result.output.empty() ? "Preview rendering failed." : result.output)
        };
        if (s->closing || !IsWindow(window) || !PostMessageW(window, WM_PREVIEW_READY, 0, reinterpret_cast<LPARAM>(payload))) {
            std::error_code ignored;
            fs::remove(output, ignored); fs::remove(snapshot, ignored);
            delete payload;
        }
        --s->preview_jobs;
    }).detach();
}

void reset_player(AppState* s) {
    s->playing = false;
    s->duration = cubical::timeline_duration(s->project);
    s->current_time = std::min(0.15, s->duration);
    update_player_ui(s);
    if (write_working(s)) start_preview_render(s, s->current_time);
}

void toggle_play(AppState* s) {
    if (s->playing) {
        s->playing = false;
        update_player_ui(s);
        return;
    }
    if (!write_working(s)) return;
    s->duration = cubical::timeline_duration(s->project);
    if (s->current_time >= s->duration) s->current_time = 0.0;
    s->playing = true;
    s->play_anchor_time = s->current_time;
    s->play_anchor = std::chrono::steady_clock::now();
    update_player_ui(s);
    start_preview_render(s, s->current_time);
}

void restart_player(AppState* s) {
    s->playing = false;
    request_preview(s, std::min(0.15, s->duration), true);
}

void import_project(AppState* s,const fs::path& path){
    std::string error;bool loaded=false;
    if(lower_extension(path)==".ccx")loaded=cubical::load_ccx(s->project,path,&error);
    else{auto r=cubical::run_engine({"project-to-ccx",path.string(),s->working_ccx.string()});if(r.exit_code==0)loaded=cubical::load_ccx(s->project,s->working_ccx,&error);else error=r.output;}
    if(loaded){s->project.project_path=path;select_index(s,0);load_project(s);reset_player(s);}else set_status(s,error.empty()?"Could not open the selected project.":error);
}
void save_project(AppState* s,const fs::path& path){
    if(!write_working(s))return;
    auto r=cubical::run_engine({"save-portable",s->working_ccx.string(),path.string()});
    if(r.exit_code==0){s->project.project_path=path;set_status(s,"Saved portable project "+path.string());}
    else set_status(s,r.output.empty()?"Could not save the project.":r.output);
}

double to_double(HWND h,double fallback){try{return std::stod(text(h));}catch(...){return fallback;}}
int to_int(HWND h,int fallback){try{return std::stoi(text(h));}catch(...){return fallback;}}
bool begin_task(AppState* s, bool export_task, const wchar_t* title, const wchar_t* line);

void choose_and_import_data(AppState* s){
    if(s->busy)return;
    COMDLG_FILTERSPEC f[]={{L"Spreadsheet",L"*.xlsx;*.xlsm;*.csv"},{L"All files",L"*.*"}};
    auto p=choose_shell_file(s->window,false,L"Insert spreadsheet data",f,2);
    if(p.empty()||!write_working(s))return;
    auto out=cubical::temporary_path("cubical-import",".ccx");
    if(!begin_task(s,false,L"Importing spreadsheet",L"Reading rows and applying cards…"))return;
    HWND window=s->window; auto input=s->working_ccx; auto source=fs::path(p);
    std::thread([s,window,input,source,out](){
        auto result=cubical::run_engine({"import-data",input.string(),source.string(),out.string()});
        auto* payload=new TaskResult{s,TaskKind::ImportData,std::move(result),out,{},source.string()};
        if(!IsWindow(window)||!PostMessageW(window,WM_TASK_DONE,0,reinterpret_cast<LPARAM>(payload)))delete payload;
    }).detach();
}

bool begin_task(AppState* s, bool export_task, const wchar_t* title, const wchar_t* line) {
    if (s->busy) { set_status(s, "Another operation is already running."); return false; }
    IProgressDialog* dialog=nullptr;
    if (FAILED(CoCreateInstance(CLSID_ProgressDialog,nullptr,CLSCTX_INPROC_SERVER,IID_IProgressDialog,reinterpret_cast<void**>(&dialog)))||!dialog) {
        set_status(s,"Could not open the progress window."); return false;
    }
    s->busy=true;s->task_is_export=export_task;s->playing=false;update_player_ui(s);
    s->task_dialog=dialog;
    s->task_progress_path=cubical::temporary_path("cubical-compare-progress",".txt");
    s->task_cancel_path=cubical::temporary_path("cubical-compare-cancel",".flag");
    std::ofstream(s->task_progress_path)<<0;
    dialog->SetTitle(title);dialog->SetLine(1,line,FALSE,nullptr);dialog->SetProgress(0,100);
    dialog->StartProgressDialog(s->window,nullptr,PROGDLG_MODAL|PROGDLG_AUTOTIME|PROGDLG_NOMINIMIZE,nullptr);
    SetTimer(s->window,TASK_TIMER,100,nullptr);
    return true;
}
void finish_task(AppState* s,const std::string& status){
    KillTimer(s->window,TASK_TIMER);
    if(s->task_dialog){s->task_dialog->StopProgressDialog();s->task_dialog->Release();s->task_dialog=nullptr;}
    std::error_code ec;fs::remove(s->task_progress_path,ec);fs::remove(s->task_cancel_path,ec);
    s->busy=false;s->task_is_export=false;set_status(s,status);
    if(s->pending_close){s->closing=true;DestroyWindow(s->window);}
}
void update_task_progress(AppState* s){
    if(!s->busy||!s->task_dialog)return;
    int percent=0,done=0,total=0;std::ifstream in(s->task_progress_path);if(in)in>>percent>>done>>total;
    percent=std::clamp(percent,0,100);s->task_dialog->SetProgress(percent,100);
    if(done>0&&total>0){
        const std::wstring line=s->task_is_export
            ? L"Rendering frame "+std::to_wstring(done)+L" of "+std::to_wstring(total)+L"…"
            : L"Preparing image "+std::to_wstring(done)+L" of "+std::to_wstring(total)+L"…";
        s->task_dialog->SetLine(1,line.c_str(),FALSE,nullptr);
    }
    if(s->task_dialog->HasUserCancelled()){std::ofstream(s->task_cancel_path)<<"cancel";s->task_dialog->SetLine(1,L"Cancelling…",FALSE,nullptr);}
}

void choose_and_import_sheet(AppState* s){
    if(s->busy)return;
    COMDLG_FILTERSPEC f[]={{L"Images",L"*.png;*.jpg;*.jpeg;*.webp;*.bmp"},{L"All files",L"*.*"}};
    auto p=choose_shell_file(s->window,false,L"Import image sheet",f,2);
    if(p.empty()||!write_working(s))return;
    auto out=cubical::temporary_path("cubical-sheet",".ccx");
    auto assets=persistent_asset_directory("sheet");
    int rows=to_int(s->sheet_rows,0),columns=to_int(s->sheet_columns,0),start=std::max(0,to_int(s->sheet_start,1)-1);
    if(!begin_task(s,false,L"Importing image sheet",L"Detecting the grid and preparing card artwork…"))return;
    const int expected=std::max(1,static_cast<int>(s->project.cards.size())-start);
    std::vector<std::string> command={"import-sheet",s->working_ccx.string(),utf8(p),out.string(),assets.string(),"--expected",std::to_string(expected),"--start",std::to_string(start),"--create-extra","--fit","cts_card","--progress-file",s->task_progress_path.string(),"--cancel-file",s->task_cancel_path.string()};
    if(rows>0&&columns>0)command.insert(command.end(),{"--rows",std::to_string(rows),"--columns",std::to_string(columns)});
    HWND window=s->window;
    std::thread([s,window,command=std::move(command),out,assets]()mutable{
        auto result=cubical::run_engine(command);
        auto* payload=new TaskResult{s,TaskKind::ImportSheet,std::move(result),out,assets,{}};
        if(!IsWindow(window)||!PostMessageW(window,WM_TASK_DONE,0,reinterpret_cast<LPARAM>(payload)))delete payload;
    }).detach();
}

void begin_export(AppState* s){
    if(s->busy)return;
    COMDLG_FILTERSPEC f[]={{L"MP4 Video",L"*.mp4"}};
    auto p=choose_shell_file(s->window,true,L"Export MP4",f,1,L"mp4");
    if(p.empty()||!write_working(s))return;
    fs::path export_path=utf8(p);
    if(export_path.extension()!=".mp4")export_path.replace_extension(".mp4");
    if(!begin_task(s,true,L"Exporting MP4",L"Starting the real frame renderer and FFmpeg encoder…"))return;
    auto input=s->working_ccx;auto path=export_path.string();auto progress=s->task_progress_path;auto cancel=s->task_cancel_path;HWND window=s->window;
    std::thread([s,window,input,path,progress,cancel](){
        auto result=cubical::run_engine({"export",input.string(),path,"--progress-file",progress.string(),"--cancel-file",cancel.string()});
        auto* payload=new TaskResult{s,TaskKind::ExportVideo,std::move(result),{},{},path};
        if(!IsWindow(window)||!PostMessageW(window,WM_TASK_DONE,0,reinterpret_cast<LPARAM>(payload)))delete payload;
    }).detach();
}

void show_page(ControlsState* c,int index){auto set=[&](std::vector<HWND>& v,bool show){for(HWND h:v)ShowWindow(h,show?SW_SHOW:SW_HIDE);};set(c->page_text,index==0);set(c->page_audio,index==1);set(c->page_output,index==2);set(c->page_transform,index==3);}
HWND cedit(ControlsState* c,const wchar_t* label,int y,std::vector<HWND>& page,int id=0){auto* s=c->app;HWND l=make_control(s,WC_STATICW,label,0,18,y,190,22,c->window);HWND e=make_control(s,WC_EDITW,L"",WS_BORDER|ES_AUTOHSCROLL,215,y-2,540,25,c->window,id,WS_EX_CLIENTEDGE);page.push_back(l);page.push_back(e);return e;}
HWND ccheck(ControlsState* c,const wchar_t* label,int y,std::vector<HWND>& page){HWND h=make_control(c->app,WC_BUTTONW,label,BS_AUTOCHECKBOX,215,y,400,24,c->window);page.push_back(h);return h;}

void choose_system_font(ControlsState* c, int index) {
    LOGFONTW lf{};
    const std::wstring current = text(c->font_fields[static_cast<std::size_t>(index)]);
    if (!current.empty() && current.find(L'\\') == std::wstring::npos && current.find(L'/') == std::wstring::npos)
        wcsncpy_s(lf.lfFaceName, current.c_str(), LF_FACESIZE - 1);
    CHOOSEFONTW choice{};
    choice.lStructSize = sizeof(choice);
    choice.hwndOwner = c->window;
    choice.lpLogFont = &lf;
    choice.Flags = CF_SCREENFONTS | CF_INITTOLOGFONTSTRUCT | CF_NOVERTFONTS;
    if (ChooseFontW(&choice)) SetWindowTextW(c->font_fields[static_cast<std::size_t>(index)], lf.lfFaceName);
}

void reuse_title_font(ControlsState* c) {
    const std::wstring selected = text(c->font_fields[0]);
    for (HWND field : c->font_fields) SetWindowTextW(field, selected.c_str());
}

void load_controls(ControlsState* c){auto& p=c->app->project;auto& s=p.settings;set_text(c->project_name,p.name);SendMessageW(c->model,CB_SETCURSEL,0,0);SendMessageW(c->credits_enabled,BM_SETCHECK,s.credits_enabled?BST_CHECKED:BST_UNCHECKED,0);std::vector<std::string*> tv={&s.credits_top_text,&s.credits_heading,&s.credits_project_name,&s.credits_created_with_label,&s.credits_created_with_value,&s.credits_design_label,&s.credits_design_value,&s.credits_footer,&s.end_best_label,&s.end_newest_label,&s.end_credit_label,&s.end_credit_value};for(size_t i=0;i<tv.size();++i)set_text(c->text_fields[i],*tv[i]);set_text(c->soundtrack,s.soundtrack);SendMessageW(c->loop,BM_SETCHECK,s.soundtrack_loop?BST_CHECKED:BST_UNCHECKED,0);SetWindowTextW(c->volume,std::to_wstring(static_cast<int>(s.soundtrack_volume*100)).c_str());SetWindowTextW(c->offset,std::to_wstring(s.soundtrack_offset_seconds).c_str());SetWindowTextW(c->fade,std::to_wstring(s.soundtrack_fade_out_seconds).c_str());SetWindowTextW(c->width,L"1920");SetWindowTextW(c->height,std::to_wstring(s.height).c_str());SetWindowTextW(c->fps,std::to_wstring(s.fps).c_str());set_text(c->preset,s.encoder_preset);SetWindowTextW(c->crf,std::to_wstring(s.encoder_crf).c_str());set_text(c->fit_mode,s.image_fit_mode);set_text(c->font_fields[0],s.font_title);set_text(c->font_fields[1],s.font_description);set_text(c->font_fields[2],s.font_badge);set_text(c->font_fields[3],s.font_credits);const int index=selected_index(c->app);if(index>=0){const auto& card=p.cards[static_cast<std::size_t>(index)];SetWindowTextW(c->image_x,std::to_wstring(card.image_x).c_str());SetWindowTextW(c->image_y,std::to_wstring(card.image_y).c_str());SetWindowTextW(c->image_scale,std::to_wstring(card.image_scale*100.0).c_str());SetWindowTextW(c->image_rotation,std::to_wstring(card.image_rotation).c_str());SetWindowTextW(c->crop_left,std::to_wstring(card.image_crop_left*100.0).c_str());SetWindowTextW(c->crop_top,std::to_wstring(card.image_crop_top*100.0).c_str());SetWindowTextW(c->crop_right,std::to_wstring(card.image_crop_right*100.0).c_str());SetWindowTextW(c->crop_bottom,std::to_wstring(card.image_crop_bottom*100.0).c_str());SendMessageW(c->image_front,BM_SETCHECK,card.image_layer=="front"?BST_CHECKED:BST_UNCHECKED,0);}}

void apply_controls(ControlsState* c){auto& p=c->app->project;auto& s=p.settings;p.name=utf8(text(c->project_name));s.model_id="what-males-learn-at-each-age";s.model_revision=1;s.credits_enabled=SendMessageW(c->credits_enabled,BM_GETCHECK,0,0)==BST_CHECKED;std::vector<std::string*> tv={&s.credits_top_text,&s.credits_heading,&s.credits_project_name,&s.credits_created_with_label,&s.credits_created_with_value,&s.credits_design_label,&s.credits_design_value,&s.credits_footer,&s.end_best_label,&s.end_newest_label,&s.end_credit_label,&s.end_credit_value};for(size_t i=0;i<tv.size();++i)*tv[i]=utf8(text(c->text_fields[i]));s.soundtrack=utf8(text(c->soundtrack));s.soundtrack_loop=SendMessageW(c->loop,BM_GETCHECK,0,0)==BST_CHECKED;s.soundtrack_volume=std::clamp(to_double(c->volume,75.0)/100.0,0.0,1.0);s.soundtrack_offset_seconds=std::max(0.0,to_double(c->offset,0));s.soundtrack_fade_out_seconds=std::max(0.0,to_double(c->fade,.75));s.auto_length=true;s.width=1920;s.height=1080;s.fps=60;s.encoder_preset=utf8(text(c->preset));{const std::vector<std::string> valid={"ultrafast","superfast","veryfast","faster","fast","medium","slow","slower","veryslow"};if(std::find(valid.begin(),valid.end(),s.encoder_preset)==valid.end())s.encoder_preset="faster";}s.encoder_crf=std::clamp(to_int(c->crf,18),0,51);s.image_fit_mode=utf8(text(c->fit_mode))=="contain"?"contain":"cover";s.font_title=utf8(text(c->font_fields[0]));s.font_description=utf8(text(c->font_fields[1]));s.font_badge=utf8(text(c->font_fields[2]));s.font_credits=utf8(text(c->font_fields[3]));const int index=selected_index(c->app);if(index>=0){auto& card=p.cards[static_cast<std::size_t>(index)];card.image_x=to_double(c->image_x,0.0);card.image_y=to_double(c->image_y,0.0);card.image_scale=std::clamp(to_double(c->image_scale,100.0)/100.0,0.05,8.0);card.image_rotation=to_double(c->image_rotation,0.0);card.image_crop_left=std::clamp(to_double(c->crop_left,0.0)/100.0,0.0,0.49);card.image_crop_top=std::clamp(to_double(c->crop_top,0.0)/100.0,0.0,0.49);card.image_crop_right=std::clamp(to_double(c->crop_right,0.0)/100.0,0.0,0.49);card.image_crop_bottom=std::clamp(to_double(c->crop_bottom,0.0)/100.0,0.0,0.49);card.image_layer=SendMessageW(c->image_front,BM_GETCHECK,0,0)==BST_CHECKED?"front":"behind";}load_project(c->app);request_preview(c->app,c->app->current_time);}

LRESULT CALLBACK ControlsProc(HWND hwnd,UINT msg,WPARAM wp,LPARAM lp){auto* c=reinterpret_cast<ControlsState*>(GetWindowLongPtrW(hwnd,GWLP_USERDATA));if(msg==WM_CREATE){auto* cs=reinterpret_cast<CREATESTRUCTW*>(lp);c=static_cast<ControlsState*>(cs->lpCreateParams);SetWindowLongPtrW(hwnd,GWLP_USERDATA,reinterpret_cast<LONG_PTR>(c));c->window=hwnd;c->tabs=make_control(c->app,WC_TABCONTROLW,L"",WS_CLIPSIBLINGS,10,10,770,38,hwnd,ID_TAB);TCITEMW item{};item.mask=TCIF_TEXT;wchar_t t0[]=L"Visible text";item.pszText=t0;TabCtrl_InsertItem(c->tabs,0,&item);wchar_t t1[]=L"Soundtrack";item.pszText=t1;TabCtrl_InsertItem(c->tabs,1,&item);wchar_t t2[]=L"Appearance & output";item.pszText=t2;TabCtrl_InsertItem(c->tabs,2,&item);wchar_t t3[]=L"Image transform";item.pszText=t3;TabCtrl_InsertItem(c->tabs,3,&item);
int y=62;c->project_name=cedit(c,L"Project title (optional)",y,c->page_text);y+=32;c->credits_enabled=ccheck(c,L"Show opening credits",y,c->page_text);y+=32;const wchar_t* labels[]={L"Credits top text",L"Credits heading",L"Credits project/name",L"Created-with label",L"Created-with value",L"Design label",L"Design value",L"Credits footer",L"End left label",L"End right label",L"End credit label",L"End credit value"};for(auto* label:labels){c->text_fields.push_back(cedit(c,label,y,c->page_text));y+=32;}
y=70;c->soundtrack=cedit(c,L"Soundtrack",y,c->page_audio);y+=36;{HWND b=make_control(c->app,WC_BUTTONW,L"Choose / replace…",0,215,y,160,28,hwnd,9001);c->page_audio.push_back(b);}y+=40;c->loop=ccheck(c,L"Loop until video ends",y,c->page_audio);y+=38;c->volume=cedit(c,L"Volume %",y,c->page_audio);y+=34;c->offset=cedit(c,L"Start inside track (s)",y,c->page_audio);y+=34;c->fade=cedit(c,L"Fade out (s)",y,c->page_audio);
y=70;{HWND label=make_control(c->app,WC_STATICW,L"Reference model",0,18,y,190,22,hwnd);c->model=make_control(c->app,WC_COMBOBOXW,L"",CBS_DROPDOWNLIST|WS_VSCROLL,215,y-3,540,160,hwnd);SendMessageW(c->model,CB_ADDSTRING,0,reinterpret_cast<LPARAM>(L"What Males Learn At Each Age"));EnableWindow(c->model,FALSE);c->page_output.push_back(label);c->page_output.push_back(c->model);}y+=36;{HWND note=make_control(c->app,WC_STATICW,L"The Males reference locks every animation, transition, layout, 1920×1080 geometry and 60 FPS timing.",SS_LEFT,18,y,737,42,hwnd);c->page_output.push_back(note);}y+=48;c->fit_mode=cedit(c,L"Image fit (cover / contain)",y,c->page_output);y+=34;c->width=cedit(c,L"Width (model locked)",y,c->page_output);EnableWindow(c->width,FALSE);y+=34;c->height=cedit(c,L"Height (model locked)",y,c->page_output);EnableWindow(c->height,FALSE);y+=34;c->fps=cedit(c,L"FPS (model locked)",y,c->page_output);EnableWindow(c->fps,FALSE);y+=34;c->preset=cedit(c,L"Encoder preset",y,c->page_output);y+=34;c->crf=cedit(c,L"CRF",y,c->page_output);y+=34;
const wchar_t* fl[]={L"Title font (file or family)",L"Description font (file or family)",L"Badge font (file or family)",L"Credits font (file or family)"};
const int font_ids[]={ID_FONT_TITLE,ID_FONT_DESCRIPTION,ID_FONT_BADGE,ID_FONT_CREDITS};
for(int i=0;i<4;++i){HWND label=make_control(c->app,WC_STATICW,fl[i],0,18,y,190,22,hwnd);HWND edit=make_control(c->app,WC_EDITW,L"",WS_BORDER|ES_AUTOHSCROLL,215,y-2,365,25,hwnd,0,WS_EX_CLIENTEDGE);HWND button=make_control(c->app,WC_BUTTONW,L"List system fonts…",0,590,y-3,165,27,hwnd,font_ids[i]);c->page_output.push_back(label);c->page_output.push_back(edit);c->page_output.push_back(button);c->font_fields.push_back(edit);y+=34;}
{HWND reuse=make_control(c->app,WC_BUTTONW,L"Reuse title font for all fields",0,215,y,280,29,hwnd,ID_REUSE_FONT);c->page_output.push_back(reuse);}
y=70;c->image_x=cedit(c,L"Horizontal offset (px)",y,c->page_transform);y+=34;c->image_y=cedit(c,L"Vertical offset (px)",y,c->page_transform);y+=34;c->image_scale=cedit(c,L"Scale %",y,c->page_transform);y+=34;c->image_rotation=cedit(c,L"Rotation degrees",y,c->page_transform);y+=34;c->crop_left=cedit(c,L"Crop left %",y,c->page_transform);y+=34;c->crop_top=cedit(c,L"Crop top %",y,c->page_transform);y+=34;c->crop_right=cedit(c,L"Crop right %",y,c->page_transform);y+=34;c->crop_bottom=cedit(c,L"Crop bottom %",y,c->page_transform);y+=38;c->image_front=ccheck(c,L"Place image in front of title, description and badge",y,c->page_transform);y+=40;{HWND hint=make_control(c->app,WC_STATICW,L"Preview shortcuts: drag = move, wheel = scale, Ctrl+wheel = rotate, Shift+wheel = crop",0,18,y,740,44,hwnd);c->page_transform.push_back(hint);}y+=48;{HWND reset=make_control(c->app,WC_BUTTONW,L"Reset selected image",0,215,y,220,29,hwnd,ID_TRANSFORM_RESET);c->page_transform.push_back(reset);}
make_control(c->app,WC_BUTTONW,L"Apply",BS_DEFPUSHBUTTON,620,585,150,34,hwnd,ID_APPLY_CONTROLS);load_controls(c);show_page(c,0);return 0;}
if(!c)return DefWindowProcW(hwnd,msg,wp,lp);switch(msg){case WM_NOTIFY:if(reinterpret_cast<LPNMHDR>(lp)->idFrom==ID_TAB&&reinterpret_cast<LPNMHDR>(lp)->code==TCN_SELCHANGE)show_page(c,TabCtrl_GetCurSel(c->tabs));return 0;case WM_COMMAND:if(LOWORD(wp)==ID_APPLY_CONTROLS){apply_controls(c);DestroyWindow(hwnd);return 0;}if(LOWORD(wp)==9001){COMDLG_FILTERSPEC f[]={{L"Audio",L"*.mp3;*.wav;*.m4a;*.aac;*.ogg;*.flac;*.opus"},{L"All files",L"*.*"}};auto p=choose_shell_file(hwnd,false,L"Choose soundtrack",f,2);if(!p.empty())SetWindowTextW(c->soundtrack,p.c_str());return 0;}if(LOWORD(wp)>=ID_FONT_TITLE&&LOWORD(wp)<=ID_FONT_CREDITS){choose_system_font(c,LOWORD(wp)-ID_FONT_TITLE);return 0;}if(LOWORD(wp)==ID_REUSE_FONT){reuse_title_font(c);return 0;}if(LOWORD(wp)==ID_TRANSFORM_RESET){SetWindowTextW(c->image_x,L"0");SetWindowTextW(c->image_y,L"0");SetWindowTextW(c->image_scale,L"100");SetWindowTextW(c->image_rotation,L"0");SetWindowTextW(c->crop_left,L"0");SetWindowTextW(c->crop_top,L"0");SetWindowTextW(c->crop_right,L"0");SetWindowTextW(c->crop_bottom,L"0");SendMessageW(c->image_front,BM_SETCHECK,BST_UNCHECKED,0);return 0;}break;case WM_CLOSE:DestroyWindow(hwnd);return 0;case WM_DESTROY:delete c;return 0;}return DefWindowProcW(hwnd,msg,wp,lp);}

void open_controls(AppState* s,int initial_tab=0){auto* c=new ControlsState();c->app=s;HWND w=CreateWindowExW(WS_EX_DLGMODALFRAME,kControlsClass,L"Cubical Compare — Project Controls",WS_OVERLAPPED|WS_CAPTION|WS_SYSMENU|WS_VISIBLE,CW_USEDEFAULT,CW_USEDEFAULT,810,680,s->window,nullptr,s->instance,c);EnableWindow(s->window,FALSE);ShowWindow(w,SW_SHOW);UpdateWindow(w);if(IsWindow(w)){TabCtrl_SetCurSel(c->tabs,std::clamp(initial_tab,0,3));show_page(c,std::clamp(initial_tab,0,3));}MSG m{};while(IsWindow(w)&&GetMessageW(&m,nullptr,0,0)>0){if(!IsDialogMessageW(w,&m)){TranslateMessage(&m);DispatchMessageW(&m);}}EnableWindow(s->window,TRUE);SetForegroundWindow(s->window);}

LRESULT CALLBACK MainProc(HWND hwnd,UINT msg,WPARAM wp,LPARAM lp){auto* s=reinterpret_cast<AppState*>(GetWindowLongPtrW(hwnd,GWLP_USERDATA));if(msg==WM_CREATE){auto* cs=reinterpret_cast<CREATESTRUCTW*>(lp);s=static_cast<AppState*>(cs->lpCreateParams);SetWindowLongPtrW(hwnd,GWLP_USERDATA,reinterpret_cast<LONG_PTR>(s));s->window=hwnd;int x=12;struct B{const wchar_t* t;int id;int w;}bs[]={{L"New",ID_NEW,55},{L"Open",ID_OPEN,60},{L"Save",ID_SAVE,60},{L"Click to Insert Data",ID_INSERT,145},{L"Image Sheet",ID_SHEET,90},{L"Music",ID_MUSIC,68},{L"Model",ID_MODEL,70},{L"Manual editor",ID_CONTROLS,105},{L"Export MP4",ID_EXPORT,95}};for(auto&b:bs){make_control(s,WC_BUTTONW,b.t,b.id==ID_INSERT?BS_DEFPUSHBUTTON:0,x,10,b.w,30,hwnd,b.id);x+=b.w+6;}
s->preview=make_control(s,WC_STATICW,L"",SS_BITMAP|SS_CENTERIMAGE|SS_NOTIFY,12,50,650,366,hwnd,ID_PREVIEW_BITMAP,WS_EX_CLIENTEDGE);SetWindowSubclass(s->preview,PreviewSubclassProc,1,reinterpret_cast<DWORD_PTR>(s));
s->play_button=make_control(s,WC_BUTTONW,L"Play",0,12,422,70,30,hwnd,ID_PLAY);
make_control(s,WC_BUTTONW,L"Restart",0,88,422,80,30,hwnd,ID_RESTART);
s->seek=make_control(s,TRACKBAR_CLASSW,L"",TBS_HORZ|TBS_NOTICKS,176,421,355,32,hwnd,ID_SEEK);
SendMessageW(s->seek,TBM_SETRANGE,TRUE,MAKELONG(0,10000));
s->time_label=make_control(s,WC_STATICW,L"00:00.0 / 00:00.0",SS_RIGHT,536,427,126,22,hwnd,ID_TIME_LABEL);
make_control(s,WC_STATICW,L"Rows",0,680,55,42,22,hwnd);s->sheet_rows=make_control(s,WC_EDITW,L"0",WS_BORDER|ES_AUTOHSCROLL,725,52,52,26,hwnd,0,WS_EX_CLIENTEDGE);make_control(s,WC_STATICW,L"Columns",0,785,55,65,22,hwnd);s->sheet_columns=make_control(s,WC_EDITW,L"0",WS_BORDER|ES_AUTOHSCROLL,852,52,52,26,hwnd,0,WS_EX_CLIENTEDGE);make_control(s,WC_STATICW,L"Start",0,912,55,42,22,hwnd);s->sheet_start=make_control(s,WC_EDITW,L"1",WS_BORDER|ES_AUTOHSCROLL,956,52,70,26,hwnd,0,WS_EX_CLIENTEDGE);s->card_list=make_control(s,WC_LISTBOXW,L"",LBS_NOTIFY|WS_VSCROLL|WS_BORDER,680,85,470,330,hwnd,ID_CARD_LIST,WS_EX_CLIENTEDGE);
int y=462;make_control(s,WC_STATICW,L"Title",0,12,y,70,22,hwnd);s->title=make_control(s,WC_EDITW,L"",WS_BORDER|ES_AUTOHSCROLL,82,y-3,270,27,hwnd,ID_TITLE,WS_EX_CLIENTEDGE);make_control(s,WC_STATICW,L"Value / badge",0,365,y,100,22,hwnd);s->value=make_control(s,WC_EDITW,L"",WS_BORDER|ES_AUTOHSCROLL,465,y-3,197,27,hwnd,ID_VALUE,WS_EX_CLIENTEDGE);y+=34;make_control(s,WC_STATICW,L"Description",0,12,y,70,22,hwnd);s->description=make_control(s,WC_EDITW,L"",WS_BORDER|ES_AUTOHSCROLL,82,y-3,580,27,hwnd,ID_DESCRIPTION,WS_EX_CLIENTEDGE);y+=34;make_control(s,WC_STATICW,L"Image",0,12,y,70,22,hwnd);s->image=make_control(s,WC_EDITW,L"",WS_BORDER|ES_AUTOHSCROLL,82,y-3,465,27,hwnd,ID_IMAGE,WS_EX_CLIENTEDGE);make_control(s,WC_BUTTONW,L"Choose…",0,552,y-4,110,29,hwnd,ID_CHOOSE_IMAGE);
make_control(s,WC_BUTTONW,L"Add card",0,680,422,90,30,hwnd,ID_ADD);make_control(s,WC_BUTTONW,L"Remove",0,776,422,90,30,hwnd,ID_REMOVE);make_control(s,WC_BUTTONW,L"Trim after selected",0,872,422,180,30,hwnd,ID_TRIM);s->status=make_control(s,WC_STATICW,L"Ready",SS_SUNKEN,12,570,1138,28,hwnd,ID_STATUS);load_project(s);reset_player(s);SetTimer(hwnd,PLAYER_TIMER,100,nullptr);return 0;}
if(!s)return DefWindowProcW(hwnd,msg,wp,lp);switch(msg){case WM_COMMAND:{int id=LOWORD(wp);if(id==ID_CARD_LIST&&HIWORD(wp)==LBN_SELCHANGE){write_working(s);const int index=static_cast<int>(SendMessageW(s->card_list,LB_GETCURSEL,0,0));if(index>=0&&index<static_cast<int>(s->project.cards.size()))select_index(s,index);load_card(s);return 0;}if(id==ID_PLAY){toggle_play(s);}else if(id==ID_RESTART){restart_player(s);}else if(id==ID_NEW){if(s->busy)return 0;s->project=cubical::Project{};s->project.project_path.clear();select_index(s,0);load_project(s);reset_player(s);}else if(id==ID_OPEN){COMDLG_FILTERSPEC f[]={{L"Cubical Compare project",L"*.json;*.ccp;*.cts.json;*.ccx"},{L"All files",L"*.*"}};auto p=choose_shell_file(hwnd,false,L"Open project",f,2);if(!p.empty())import_project(s,p);}else if(id==ID_SAVE){fs::path p=s->project.project_path;if(p.empty()){COMDLG_FILTERSPEC f[]={{L"Cubical Compare project",L"*.json"}};auto w=choose_shell_file(hwnd,true,L"Save project",f,1,L"json");if(w.empty())return 0;p=w;}save_project(s,p);}else if(id==ID_INSERT)choose_and_import_data(s);else if(id==ID_SHEET)choose_and_import_sheet(s);else if(id==ID_EXPORT)begin_export(s);else if(id==ID_MUSIC)open_controls(s,1);else if(id==ID_MODEL)open_controls(s,2);else if(id==ID_CONTROLS)open_controls(s,3);else if(id==ID_CHOOSE_IMAGE){COMDLG_FILTERSPEC f[]={{L"Images",L"*.png;*.jpg;*.jpeg;*.webp;*.bmp"}};auto p=choose_shell_file(hwnd,false,L"Choose card image",f,1);if(!p.empty()){SetWindowTextW(s->image,p.c_str());write_working(s);request_preview(s, s->current_time, false);}}else if(id==ID_ADD){if(s->busy)return 0;write_working(s);s->project.cards.push_back({"New card","","",""});select_index(s,static_cast<int>(s->project.cards.size())-1);rebuild_cards(s);load_card(s);reset_player(s);}else if(id==ID_REMOVE){if(s->busy)return 0;write_working(s);if(s->project.cards.size()>1){const int selected_row=static_cast<int>(SendMessageW(s->card_list,LB_GETCURSEL,0,0));std::string remove_id=s->selected_card_id;if(selected_row>=0&&selected_row<static_cast<int>(s->project.cards.size()))remove_id=s->project.cards[static_cast<std::size_t>(selected_row)].id;const int next=cubical::erase_card_by_id(s->project,remove_id);if(next>=0){select_index(s,next);rebuild_cards(s);load_card(s);reset_player(s);}else set_status(s,"The selected card could not be resolved.");}}else if(id==ID_TRIM){if(s->busy)return 0;write_working(s);int keep=selected_index(s)+1;if(keep>0&&keep<static_cast<int>(s->project.cards.size())){s->project.cards.resize(keep);rebuild_cards(s);load_card(s);reset_player(s);}}return 0;}case WM_HSCROLL:{if(reinterpret_cast<HWND>(lp)==s->seek&&!s->updating_seek){const int pos=static_cast<int>(SendMessageW(s->seek,TBM_GETPOS,0,0));const double value=s->duration>0.0?s->duration*pos/10000.0:0.0;s->current_time=value;if(s->playing){s->play_anchor_time=value;s->play_anchor=std::chrono::steady_clock::now();}request_preview(s,value,true);}return 0;}case WM_TIMER:{if(wp==TASK_TIMER){update_task_progress(s);return 0;}if(wp==PLAYER_TIMER&&s->playing){const auto now=std::chrono::steady_clock::now();const double elapsed=std::chrono::duration<double>(now-s->play_anchor).count();s->current_time=s->play_anchor_time+elapsed;if(s->current_time>=s->duration){s->current_time=s->duration;s->playing=false;}update_player_ui(s);start_preview_render(s,s->current_time);}return 0;}case WM_PREVIEW_READY:{std::unique_ptr<PreviewResult> payload(reinterpret_cast<PreviewResult*>(lp));s->preview_rendering=false;std::error_code ignored;fs::remove(payload->project_snapshot,ignored);if(payload->error.empty()&&fs::exists(payload->image_path)){RECT preview_rect{};GetClientRect(s->preview,&preview_rect);const int preview_width=std::max(1,static_cast<int>(preview_rect.right-preview_rect.left));const int preview_height=std::max(1,static_cast<int>(preview_rect.bottom-preview_rect.top));HBITMAP next=load_scaled_bitmap(payload->image_path,preview_width,preview_height);if(next){if(s->preview_bitmap)DeleteObject(s->preview_bitmap);s->preview_bitmap=next;SendMessageW(s->preview,STM_SETIMAGE,IMAGE_BITMAP,reinterpret_cast<LPARAM>(next));if(!s->playing)set_status(s,"Preview frame matches exported output at "+utf8(format_time(payload->time)));}else payload->error="Windows could not decode the rendered preview bitmap.";}if(!payload->error.empty())set_status(s,payload->error);fs::remove(payload->image_path,ignored);if(s->preview_pending){double next=s->pending_time;s->preview_pending=false;start_preview_render(s,next);}return 0;}case WM_TASK_DONE:{std::unique_ptr<TaskResult> payload(reinterpret_cast<TaskResult*>(lp));std::string status;if(payload->process.exit_code==0){if(payload->kind==TaskKind::ImportSheet||payload->kind==TaskKind::ImportData){std::string error;if(cubical::load_ccx(s->project,payload->output_ccx,&error)){select_index(s,0);load_project(s);reset_player(s);status=payload->process.output.empty()?(payload->kind==TaskKind::ImportData?"Spreadsheet data applied.":"Image sheet imported."):payload->process.output;}else status=error.empty()?"The imported project could not be loaded.":error;}else{const fs::path exported(payload->target_path);status=valid_mp4_file(exported)?"Exported "+exported.string():"Export failed: no usable MP4 was created.";}}else status=payload->process.output.empty()?"Operation failed.":payload->process.output;std::error_code ec;if(!payload->output_ccx.empty())fs::remove(payload->output_ccx,ec);finish_task(s,status);return 0;}case WM_CLOSE:if(s->busy){s->pending_close=true;std::ofstream(s->task_cancel_path)<<"cancel";set_status(s,"Cancelling the current operation before closing…");return 0;}s->closing=true;DestroyWindow(hwnd);return 0;case WM_DESTROY:s->closing=true;KillTimer(hwnd,PLAYER_TIMER);if(s->preview_bitmap)DeleteObject(s->preview_bitmap);PostQuitMessage(0);return 0;}return DefWindowProcW(hwnd,msg,wp,lp);}
}

int WINAPI wWinMain(HINSTANCE instance,HINSTANCE,PWSTR,int show){
int argc=0;LPWSTR* argv=CommandLineToArgvW(GetCommandLineW(),&argc);if(argv&&argc>1&&std::wstring(argv[1])==L"--self-test"){cubical::Project timing_project;timing_project.cards.assign(8,{"Card","1","",""});if(std::abs(cubical::timeline_duration(timing_project)-28.75)>1e-9){LocalFree(argv);return 3;}cubical::Project deletion_project;deletion_project.cards={{"First","1","",""},{"","2","",""},{"Third","3","",""}};const std::string untitled_id=deletion_project.cards[1].id;if(cubical::erase_card_by_id(deletion_project,untitled_id)!=1||deletion_project.cards.size()!=2||deletion_project.cards[0].title!="First"||deletion_project.cards[1].title!="Third"){LocalFree(argv);return 4;}const auto directory=cubical::temporary_path("cubical-compare-native-self-test","");const auto result=cubical::run_engine({"self-test","--directory",directory.string()});std::error_code ignored;fs::remove_all(directory,ignored);LocalFree(argv);return result.exit_code;}if(argv)LocalFree(argv);
CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);INITCOMMONCONTROLSEX cc{sizeof(cc),ICC_STANDARD_CLASSES|ICC_TAB_CLASSES|ICC_LISTVIEW_CLASSES|ICC_BAR_CLASSES};InitCommonControlsEx(&cc);AppState state;state.instance=instance;state.ui_font=CreateFontW(-16,0,0,0,FW_NORMAL,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH|FF_DONTCARE,L"Segoe UI");state.working_ccx=cubical::temporary_path("cubical-compare",".ccx");state.preview_path.clear();WNDCLASSW wc{};wc.lpfnWndProc=MainProc;wc.hInstance=instance;wc.hCursor=LoadCursorW(nullptr,IDC_ARROW);wc.hbrBackground=reinterpret_cast<HBRUSH>(COLOR_WINDOW+1);wc.lpszClassName=kMainClass;RegisterClassW(&wc);WNDCLASSW cw{};cw.lpfnWndProc=ControlsProc;cw.hInstance=instance;cw.hCursor=LoadCursorW(nullptr,IDC_ARROW);cw.hbrBackground=reinterpret_cast<HBRUSH>(COLOR_WINDOW+1);cw.lpszClassName=kControlsClass;RegisterClassW(&cw);HWND window=CreateWindowExW(0,kMainClass,L"Cubical Compare",WS_OVERLAPPEDWINDOW,CW_USEDEFAULT,CW_USEDEFAULT,1180,640,nullptr,nullptr,instance,&state);ShowWindow(window,show);UpdateWindow(window);MSG m{};while(GetMessageW(&m,nullptr,0,0)>0){TranslateMessage(&m);DispatchMessageW(&m);}for(int i=0;i<100&&state.preview_jobs.load()>0;++i)Sleep(20);std::error_code ec;fs::remove(state.working_ccx,ec);fs::remove(state.preview_path,ec);DeleteObject(state.ui_font);CoUninitialize();return static_cast<int>(m.wParam);}
