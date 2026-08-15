#define UNICODE
#define _UNICODE
#include <windows.h>
#include <commctrl.h>
#include <commdlg.h>
#include <shellapi.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cctype>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iterator>
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

namespace fs = std::filesystem;
namespace {

constexpr wchar_t kWindowClass[] = L"CubicalCompareFinalStudio";
constexpr UINT WM_PREVIEW_DONE = WM_APP + 10;
constexpr UINT WM_TASK_DONE = WM_APP + 11;
constexpr UINT kProgressTimer = 20;

enum ControlId {
    ID_NEW = 100,
    ID_OPEN,
    ID_SAVE,
    ID_IMPORT_DATA,
    ID_IMPORT_PACK,
    ID_ADD_CARD,
    ID_DELETE_CARD,
    ID_EXPORT,
    ID_CANCEL,
    ID_CARD_LIST,
    ID_PREVIEW,
    ID_TIMELINE,
    ID_FRAME_LABEL,
    ID_TITLE,
    ID_VALUE,
    ID_DESCRIPTION,
    ID_IMAGE,
    ID_CHOOSE_IMAGE,
    ID_SOUNDTRACK,
    ID_CHOOSE_SOUNDTRACK,
    ID_BADGES,
    ID_CREDITS,
    ID_PROGRESS,
    ID_STATUS,
};

std::string utf8(const std::wstring& value) {
    if (value.empty()) return {};
    const int count = WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0, nullptr, nullptr);
    std::string result(static_cast<std::size_t>(count), '\0');
    WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), result.data(), count, nullptr, nullptr);
    return result;
}

std::wstring wide(const std::string& value) {
    if (value.empty()) return {};
    const int count = MultiByteToWideChar(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0);
    std::wstring result(static_cast<std::size_t>(count), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), result.data(), count);
    return result;
}

std::string narrow_path(const fs::path& value) { return utf8(value.wstring()); }

std::wstring control_text(HWND control) {
    const int length = GetWindowTextLengthW(control);
    std::wstring result(static_cast<std::size_t>(length) + 1, L'\0');
    GetWindowTextW(control, result.data(), length + 1);
    result.resize(static_cast<std::size_t>(length));
    return result;
}

void set_control_text(HWND control, const std::string& value) {
    SetWindowTextW(control, wide(value).c_str());
}

struct App {
    HINSTANCE instance{};
    HWND window{};
    HWND cards{};
    HWND preview{};
    HWND timeline{};
    HWND frame_label{};
    HWND title{};
    HWND value{};
    HWND description{};
    HWND image{};
    HWND soundtrack{};
    HWND badges{};
    HWND credits{};
    HWND progress{};
    HWND status{};
    HWND cancel{};
    HFONT font{};
    HBITMAP preview_bitmap{};

    cubical::Project project;
    fs::path project_path;
    fs::path working_ccx;
    fs::path preview_bmp;
    fs::path progress_file;
    fs::path cancel_file;

    int selected{0};
    int current_frame{0};
    int total_frames{1};
    bool loading_fields{false};
    std::atomic<unsigned long> preview_generation{0};
    std::atomic<bool> busy{false};
    bool pending_close{false};
};

struct PreviewResult {
    unsigned long generation{};
    fs::path bitmap;
    std::string error;
};

struct TaskResult {
    enum class Kind { ImportData, ImportPack, Export } kind{Kind::Export};
    cubical::ProcessResult process;
    fs::path output;
    fs::path assets;
};

HWND make_control(App* app, const wchar_t* cls, const wchar_t* text, DWORD style, int id) {
    HWND control = CreateWindowExW(
        WS_EX_NOPARENTNOTIFY,
        cls,
        text,
        WS_CHILD | WS_VISIBLE | style,
        0, 0, 10, 10,
        app->window,
        reinterpret_cast<HMENU>(static_cast<INT_PTR>(id)),
        app->instance,
        nullptr
    );
    if (control && app->font) SendMessageW(control, WM_SETFONT, reinterpret_cast<WPARAM>(app->font), TRUE);
    return control;
}

void status(App* app, const std::string& text) { set_control_text(app->status, text); }

std::wstring choose_file(HWND owner, bool save, const wchar_t* filter, const wchar_t* extension = nullptr) {
    wchar_t path[32768]{};
    OPENFILENAMEW dialog{};
    dialog.lStructSize = sizeof(dialog);
    dialog.hwndOwner = owner;
    dialog.lpstrFile = path;
    dialog.nMaxFile = static_cast<DWORD>(std::size(path));
    dialog.lpstrFilter = filter;
    dialog.lpstrDefExt = extension;
    dialog.Flags = OFN_EXPLORER | OFN_PATHMUSTEXIST | (save ? OFN_OVERWRITEPROMPT : OFN_FILEMUSTEXIST);
    const BOOL ok = save ? GetSaveFileNameW(&dialog) : GetOpenFileNameW(&dialog);
    return ok ? std::wstring(path) : std::wstring();
}

void replace_bitmap(App* app, HBITMAP bitmap) {
    if (app->preview_bitmap) DeleteObject(app->preview_bitmap);
    app->preview_bitmap = bitmap;
    SendMessageW(app->preview, STM_SETIMAGE, IMAGE_BITMAP, reinterpret_cast<LPARAM>(bitmap));
}

void commit_fields(App* app) {
    if (app->loading_fields || app->selected < 0 || app->selected >= static_cast<int>(app->project.cards.size())) return;
    auto& card = app->project.cards[static_cast<std::size_t>(app->selected)];
    card.title = utf8(control_text(app->title));
    card.value = utf8(control_text(app->value));
    card.description = utf8(control_text(app->description));
    card.image = utf8(control_text(app->image));
    app->project.settings.soundtrack = utf8(control_text(app->soundtrack));
    app->project.settings.show_badges = SendMessageW(app->badges, BM_GETCHECK, 0, 0) == BST_CHECKED;
    app->project.settings.credits_enabled = SendMessageW(app->credits, BM_GETCHECK, 0, 0) == BST_CHECKED;
}

bool snapshot_project(App* app) {
    commit_fields(app);
    std::string error;
    if (!cubical::save_ccx(app->project, app->working_ccx, &error)) {
        status(app, "Could not create renderer snapshot: " + error);
        return false;
    }
    return true;
}

void rebuild_card_list(App* app) {
    SendMessageW(app->cards, LB_RESETCONTENT, 0, 0);
    for (std::size_t i = 0; i < app->project.cards.size(); ++i) {
        const auto& card = app->project.cards[i];
        const std::wstring label = std::to_wstring(i + 1) + L"  " + wide(card.title.empty() ? "Untitled" : card.title);
        SendMessageW(app->cards, LB_ADDSTRING, 0, reinterpret_cast<LPARAM>(label.c_str()));
    }
    if (!app->project.cards.empty()) {
        app->selected = std::clamp(app->selected, 0, static_cast<int>(app->project.cards.size()) - 1);
        SendMessageW(app->cards, LB_SETCURSEL, app->selected, 0);
    } else {
        app->selected = -1;
    }
}

void load_fields(App* app) {
    app->loading_fields = true;
    if (app->selected >= 0 && app->selected < static_cast<int>(app->project.cards.size())) {
        const auto& card = app->project.cards[static_cast<std::size_t>(app->selected)];
        set_control_text(app->title, card.title);
        set_control_text(app->value, card.value);
        set_control_text(app->description, card.description);
        set_control_text(app->image, card.image);
    } else {
        SetWindowTextW(app->title, L"");
        SetWindowTextW(app->value, L"");
        SetWindowTextW(app->description, L"");
        SetWindowTextW(app->image, L"");
    }
    set_control_text(app->soundtrack, app->project.settings.soundtrack);
    SendMessageW(app->badges, BM_SETCHECK, app->project.settings.show_badges ? BST_CHECKED : BST_UNCHECKED, 0);
    SendMessageW(app->credits, BM_SETCHECK, app->project.settings.credits_enabled ? BST_CHECKED : BST_UNCHECKED, 0);
    app->loading_fields = false;
}

void read_metadata(App* app) {
    if (!snapshot_project(app)) return;
    auto result = cubical::run_engine({"validate", narrow_path(app->working_ccx)});
    if (result.exit_code != 0) return;
    const std::string needle = "\"frame_count\":";
    const auto pos = result.output.find(needle);
    if (pos != std::string::npos) {
        std::size_t start = pos + needle.size();
        while (start < result.output.size() && std::isspace(static_cast<unsigned char>(result.output[start]))) ++start;
        app->total_frames = std::max(1, std::atoi(result.output.c_str() + start));
    }
    app->current_frame = std::clamp(app->current_frame, 0, app->total_frames - 1);
    SendMessageW(app->timeline, TBM_SETRANGEMAX, TRUE, app->total_frames - 1);
    SendMessageW(app->timeline, TBM_SETPOS, TRUE, app->current_frame);
}

void update_frame_label(App* app) {
    std::wostringstream text;
    text << L"Frame " << app->current_frame << L" / " << std::max(0, app->total_frames - 1);
    SetWindowTextW(app->frame_label, text.str().c_str());
}

void request_preview(App* app) {
    if (!snapshot_project(app)) return;
    const auto generation = ++app->preview_generation;
    const fs::path snapshot = app->working_ccx;
    const fs::path output = cubical::temporary_path("final-preview-" + std::to_string(generation), ".bmp");
    const int frame = app->current_frame;
    const HWND window = app->window;
    status(app, "Rendering exact preview...");
    std::thread([generation, snapshot, output, frame, window]() {
        auto* result = new PreviewResult();
        result->generation = generation;
        result->bitmap = output;
        auto process = cubical::run_engine({
            "render-preview", narrow_path(snapshot), narrow_path(output),
            "--frame", std::to_string(frame), "--width", "960", "--height", "540"
        });
        if (process.exit_code != 0) result->error = process.output.empty() ? "Renderer preview failed." : process.output;
        PostMessageW(window, WM_PREVIEW_DONE, 0, reinterpret_cast<LPARAM>(result));
    }).detach();
    update_frame_label(app);
}

void sync_project_ui(App* app, bool refresh_metadata = true) {
    rebuild_card_list(app);
    load_fields(app);
    if (refresh_metadata) read_metadata(app);
    update_frame_label(app);
    request_preview(app);
}

void save_project_as(App* app, bool force_choose) {
    commit_fields(app);
    fs::path output = app->project_path;
    if (force_choose || output.empty()) {
        const auto chosen = choose_file(app->window, true, L"Cubical Compare project (*.ccx)\0*.ccx\0All files\0*.*\0\0", L"ccx");
        if (chosen.empty()) return;
        output = chosen;
    }
    std::string error;
    if (!cubical::save_ccx(app->project, output, &error)) {
        MessageBoxW(app->window, wide(error).c_str(), L"Save failed", MB_ICONERROR);
        return;
    }
    app->project_path = output;
    status(app, "Saved " + narrow_path(output));
}

void set_busy(App* app, bool busy, const std::string& text) {
    app->busy = busy;
    EnableWindow(GetDlgItem(app->window, ID_EXPORT), !busy);
    EnableWindow(app->cancel, busy);
    if (!busy) SendMessageW(app->progress, PBM_SETPOS, 0, 0);
    status(app, text);
}

void begin_import(App* app, TaskResult::Kind kind, const fs::path& source) {
    if (app->busy || !snapshot_project(app)) return;
    const fs::path output = cubical::temporary_path(kind == TaskResult::Kind::ImportData ? "import-data" : "import-pack", ".ccx");
    const fs::path assets = kind == TaskResult::Kind::ImportPack
        ? (fs::temp_directory_path() / ("cubical-pack-" + std::to_string(std::chrono::high_resolution_clock::now().time_since_epoch().count())))
        : fs::path();
    if (!assets.empty()) fs::create_directories(assets);
    set_busy(app, true, kind == TaskResult::Kind::ImportData ? "Importing data..." : "Importing MegaPack...");
    const HWND window = app->window;
    const fs::path input = app->working_ccx;
    std::thread([kind, source, output, assets, input, window]() {
        auto* task = new TaskResult();
        task->kind = kind; task->output = output; task->assets = assets;
        if (kind == TaskResult::Kind::ImportData) {
            task->process = cubical::run_engine({"import-data", narrow_path(input), narrow_path(source), narrow_path(output)});
        } else {
            task->process = cubical::run_engine({"import-megapack", narrow_path(source), narrow_path(output), narrow_path(assets)});
        }
        PostMessageW(window, WM_TASK_DONE, 0, reinterpret_cast<LPARAM>(task));
    }).detach();
}

void begin_export(App* app, const fs::path& output) {
    if (app->busy || !snapshot_project(app)) return;
    app->progress_file = cubical::temporary_path("final-export-progress", ".txt");
    app->cancel_file = cubical::temporary_path("final-export-cancel", ".flag");
    std::error_code ec;
    fs::remove(app->progress_file, ec); fs::remove(app->cancel_file, ec);
    set_busy(app, true, "Exporting through verified renderer...");
    SetTimer(app->window, kProgressTimer, 120, nullptr);
    const HWND window = app->window;
    const fs::path input = app->working_ccx;
    const fs::path progress = app->progress_file;
    const fs::path cancel = app->cancel_file;
    std::thread([input, output, progress, cancel, window]() {
        auto* task = new TaskResult();
        task->kind = TaskResult::Kind::Export; task->output = output;
        task->process = cubical::run_engine({
            "export", narrow_path(input), narrow_path(output),
            "--progress-file", narrow_path(progress), "--cancel-file", narrow_path(cancel)
        });
        PostMessageW(window, WM_TASK_DONE, 0, reinterpret_cast<LPARAM>(task));
    }).detach();
}

void cancel_task(App* app) {
    if (!app->busy || app->cancel_file.empty()) return;
    std::ofstream(app->cancel_file, std::ios::binary) << "cancel\n";
    status(app, "Cancel requested...");
}

void layout(App* app, int width, int height) {
    const int margin = 10;
    const int toolbar_h = 38;
    const int status_h = 26;
    const int progress_h = 8;
    const int body_top = margin + toolbar_h + 6;
    const int body_bottom = height - margin - status_h - progress_h - 8;
    const int body_h = std::max(180, body_bottom - body_top);
    const int left_w = std::clamp(width / 6, 190, 260);
    const int right_w = std::clamp(width / 4, 270, 360);
    const int center_x = margin + left_w + 10;
    const int center_w = std::max(320, width - center_x - right_w - 20);
    const int right_x = center_x + center_w + 10;

    int x = margin;
    const int button_w[] = {66,66,66,92,98,76,76,78,76};
    const int ids[] = {ID_NEW,ID_OPEN,ID_SAVE,ID_IMPORT_DATA,ID_IMPORT_PACK,ID_ADD_CARD,ID_DELETE_CARD,ID_EXPORT,ID_CANCEL};
    for (std::size_t i=0;i<std::size(ids);++i) { MoveWindow(GetDlgItem(app->window,ids[i]),x,margin,button_w[i],toolbar_h,TRUE); x += button_w[i]+5; }

    MoveWindow(app->cards, margin, body_top, left_w, body_h, TRUE);

    const int preview_h = std::min(body_h - 70, center_w * 9 / 16);
    MoveWindow(app->preview, center_x, body_top, center_w, std::max(180, preview_h), TRUE);
    MoveWindow(app->timeline, center_x, body_top + std::max(180, preview_h) + 8, center_w, 32, TRUE);
    MoveWindow(app->frame_label, center_x, body_top + std::max(180, preview_h) + 42, center_w, 24, TRUE);

    int y = body_top;
    auto place = [&](int id, int h) { MoveWindow(GetDlgItem(app->window,id),right_x,y,right_w,h,TRUE); y += h + 7; };
    place(ID_TITLE, 38); place(ID_VALUE,38); place(ID_DESCRIPTION,88); place(ID_IMAGE,38); place(ID_CHOOSE_IMAGE,32); place(ID_SOUNDTRACK,38); place(ID_CHOOSE_SOUNDTRACK,32);
    MoveWindow(app->badges,right_x,y,right_w/2,28,TRUE); MoveWindow(app->credits,right_x+right_w/2,y,right_w/2,28,TRUE);

    MoveWindow(app->progress, margin, height - margin - status_h - progress_h - 2, width - 2*margin, progress_h, TRUE);
    MoveWindow(app->status, margin, height - margin - status_h, width - 2*margin, status_h, TRUE);
}

void create_ui(App* app) {
    NONCLIENTMETRICSW metrics{sizeof(metrics)};
    SystemParametersInfoW(SPI_GETNONCLIENTMETRICS, sizeof(metrics), &metrics, 0);
    app->font = CreateFontIndirectW(&metrics.lfMessageFont);

    auto button = [&](int id, const wchar_t* label) { make_control(app, L"BUTTON", label, BS_PUSHBUTTON, id); };
    button(ID_NEW,L"New"); button(ID_OPEN,L"Open"); button(ID_SAVE,L"Save"); button(ID_IMPORT_DATA,L"Import data"); button(ID_IMPORT_PACK,L"MegaPack"); button(ID_ADD_CARD,L"Add card"); button(ID_DELETE_CARD,L"Delete"); button(ID_EXPORT,L"Export"); button(ID_CANCEL,L"Cancel");
    app->cancel = GetDlgItem(app->window, ID_CANCEL); EnableWindow(app->cancel, FALSE);

    app->cards = make_control(app,L"LISTBOX",L"",LBS_NOTIFY|WS_BORDER|WS_VSCROLL,ID_CARD_LIST);
    app->preview = make_control(app,L"STATIC",L"",SS_BITMAP|SS_CENTERIMAGE|WS_BORDER,ID_PREVIEW);
    app->timeline = make_control(app,TRACKBAR_CLASSW,L"",TBS_HORZ|TBS_NOTICKS,ID_TIMELINE);
    app->frame_label = make_control(app,L"STATIC",L"Frame 0",SS_CENTER,ID_FRAME_LABEL);
    app->title = make_control(app,L"EDIT",L"",WS_BORDER|ES_AUTOHSCROLL,ID_TITLE);
    app->value = make_control(app,L"EDIT",L"",WS_BORDER|ES_AUTOHSCROLL,ID_VALUE);
    app->description = make_control(app,L"EDIT",L"",WS_BORDER|ES_MULTILINE|ES_AUTOVSCROLL|WS_VSCROLL,ID_DESCRIPTION);
    app->image = make_control(app,L"EDIT",L"",WS_BORDER|ES_AUTOHSCROLL,ID_IMAGE);
    button(ID_CHOOSE_IMAGE,L"Choose artwork...");
    app->soundtrack = make_control(app,L"EDIT",L"",WS_BORDER|ES_AUTOHSCROLL,ID_SOUNDTRACK);
    button(ID_CHOOSE_SOUNDTRACK,L"Choose soundtrack...");
    app->badges = make_control(app,L"BUTTON",L"Badges",BS_AUTOCHECKBOX,ID_BADGES);
    app->credits = make_control(app,L"BUTTON",L"Credits",BS_AUTOCHECKBOX,ID_CREDITS);
    app->progress = make_control(app,PROGRESS_CLASSW,L"",PBS_SMOOTH,ID_PROGRESS);
    SendMessageW(app->progress, PBM_SETRANGE, 0, MAKELPARAM(0,100));
    app->status = make_control(app,L"STATIC",L"Ready",SS_LEFT,ID_STATUS);
}

void new_project(App* app) {
    commit_fields(app);
    app->project = cubical::Project{};
    app->project.name = "Untitled";
    app->project.cards.clear();
    app->project.cards.push_back(cubical::Card{"Card 1","1","",""});
    app->project_path.clear();
    app->selected = 0; app->current_frame = 0;
    sync_project_ui(app);
    status(app,"New project · exact renderer active");
}

bool run_self_test() {
    try {
        cubical::Project project;
        project.name = "Final Windows shell self-test";
        project.cards = {
            cubical::Card{"Card 1","1","",""},
            cubical::Card{"Card 2","2","",""},
            cubical::Card{"Card 3","3","",""},
            cubical::Card{"Card 4","4","",""},
            cubical::Card{"Card 5","7M YEARS AGO","",""},
        };
        const fs::path ccx = cubical::temporary_path("windows-final-self-test", ".ccx");
        const fs::path bmp = cubical::temporary_path("windows-final-self-test", ".bmp");
        std::string error;
        if (!cubical::save_ccx(project, ccx, &error)) return false;
        const auto result = cubical::run_engine({"render-preview",narrow_path(ccx),narrow_path(bmp),"--frame","700","--width","960","--height","540"});
        std::error_code ec;
        const bool ok = result.exit_code == 0 && fs::is_regular_file(bmp,ec) && fs::file_size(bmp,ec) > 4096;
        fs::remove(ccx,ec); fs::remove(bmp,ec);
        return ok;
    } catch (...) { return false; }
}

LRESULT CALLBACK window_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    auto* app = reinterpret_cast<App*>(GetWindowLongPtrW(window, GWLP_USERDATA));
    if (message == WM_NCCREATE) {
        auto* create = reinterpret_cast<CREATESTRUCTW*>(lparam);
        app = reinterpret_cast<App*>(create->lpCreateParams);
        SetWindowLongPtrW(window,GWLP_USERDATA,reinterpret_cast<LONG_PTR>(app));
        app->window = window;
    }
    if (!app) return DefWindowProcW(window,message,wparam,lparam);

    switch (message) {
        case WM_CREATE:
            create_ui(app);
            app->working_ccx = cubical::temporary_path("final-studio", ".ccx");
            new_project(app);
            return 0;
        case WM_SIZE:
            layout(app, LOWORD(lparam), HIWORD(lparam));
            return 0;
        case WM_COMMAND: {
            const int id = LOWORD(wparam);
            const int code = HIWORD(wparam);
            if (id == ID_CARD_LIST && code == LBN_SELCHANGE) {
                commit_fields(app);
                const int next = static_cast<int>(SendMessageW(app->cards,LB_GETCURSEL,0,0));
                if (next >= 0) { app->selected = next; load_fields(app); request_preview(app); }
                return 0;
            }
            if ((id == ID_TITLE || id == ID_VALUE || id == ID_DESCRIPTION || id == ID_IMAGE || id == ID_SOUNDTRACK) && code == EN_KILLFOCUS && !app->loading_fields) {
                commit_fields(app); rebuild_card_list(app); request_preview(app); return 0;
            }
            if ((id == ID_BADGES || id == ID_CREDITS) && code == BN_CLICKED) { commit_fields(app); request_preview(app); return 0; }
            switch (id) {
                case ID_NEW: if (!app->busy) new_project(app); return 0;
                case ID_OPEN: if (!app->busy) {
                    const auto file = choose_file(window,false,L"Cubical Compare project (*.ccx)\0*.ccx\0All files\0*.*\0\0");
                    if (!file.empty()) { cubical::Project loaded; std::string error; if (cubical::load_ccx(loaded,fs::path(file),&error)) { app->project=std::move(loaded); app->project_path=file; app->selected=0; app->current_frame=0; sync_project_ui(app); status(app,"Project opened"); } else MessageBoxW(window,wide(error).c_str(),L"Open failed",MB_ICONERROR); }
                    return 0;
                }
                case ID_SAVE: if (!app->busy) save_project_as(app,false); return 0;
                case ID_IMPORT_DATA: if (!app->busy) {
                    const auto file=choose_file(window,false,L"Spreadsheet data\0*.csv;*.tsv;*.xlsx\0CSV\0*.csv\0Excel\0*.xlsx\0All files\0*.*\0\0");
                    if(!file.empty())begin_import(app,TaskResult::Kind::ImportData,fs::path(file)); return 0;
                }
                case ID_IMPORT_PACK: if (!app->busy) {
                    const auto file=choose_file(window,false,L"Cubical Compare MegaPack\0*.zip;*.megapack\0ZIP files\0*.zip\0All files\0*.*\0\0");
                    if(!file.empty())begin_import(app,TaskResult::Kind::ImportPack,fs::path(file)); return 0;
                }
                case ID_ADD_CARD: if (!app->busy) {
                    commit_fields(app); app->project.cards.push_back(cubical::Card{"New card","","",""}); app->selected=static_cast<int>(app->project.cards.size())-1; read_metadata(app); sync_project_ui(app,false); return 0;
                }
                case ID_DELETE_CARD: if (!app->busy && app->project.cards.size()>1 && app->selected>=0) {
                    commit_fields(app); app->project.cards.erase(app->project.cards.begin()+app->selected); app->selected=std::min(app->selected,static_cast<int>(app->project.cards.size())-1); read_metadata(app); sync_project_ui(app,false); return 0;
                }
                case ID_CHOOSE_IMAGE: if (!app->busy && app->selected>=0) {
                    const auto file=choose_file(window,false,L"Images\0*.png;*.jpg;*.jpeg;*.webp;*.bmp\0All files\0*.*\0\0");
                    if(!file.empty()){SetWindowTextW(app->image,file.c_str());commit_fields(app);request_preview(app);} return 0;
                }
                case ID_CHOOSE_SOUNDTRACK: if (!app->busy) {
                    const auto file=choose_file(window,false,L"Audio\0*.mp3;*.wav;*.m4a;*.aac;*.flac;*.ogg\0All files\0*.*\0\0");
                    if(!file.empty()){SetWindowTextW(app->soundtrack,file.c_str());commit_fields(app);} return 0;
                }
                case ID_EXPORT: if (!app->busy) {
                    const auto file=choose_file(window,true,L"MP4 video\0*.mp4\0All files\0*.*\0\0",L"mp4");
                    if(!file.empty())begin_export(app,fs::path(file)); return 0;
                }
                case ID_CANCEL: cancel_task(app); return 0;
            }
            break;
        }
        case WM_HSCROLL:
            if (reinterpret_cast<HWND>(lparam) == app->timeline) {
                app->current_frame = static_cast<int>(SendMessageW(app->timeline,TBM_GETPOS,0,0));
                update_frame_label(app);
                if (LOWORD(wparam) == TB_ENDTRACK || LOWORD(wparam) == TB_THUMBPOSITION) request_preview(app);
                return 0;
            }
            break;
        case WM_TIMER:
            if (wparam == kProgressTimer && app->busy && !app->progress_file.empty()) {
                std::ifstream input(app->progress_file);
                int percent=0,done=0,total=0;
                if(input>>percent>>done>>total){SendMessageW(app->progress,PBM_SETPOS,std::clamp(percent,0,100),0);status(app,"Exporting · "+std::to_string(percent)+"% · frame "+std::to_string(done)+" / "+std::to_string(total));}
                return 0;
            }
            break;
        case WM_PREVIEW_DONE: {
            std::unique_ptr<PreviewResult> result(reinterpret_cast<PreviewResult*>(lparam));
            if (result && result->generation == app->preview_generation.load()) {
                if (result->error.empty()) {
                    HBITMAP bitmap = static_cast<HBITMAP>(LoadImageW(nullptr,result->bitmap.wstring().c_str(),IMAGE_BITMAP,0,0,LR_LOADFROMFILE|LR_CREATEDIBSECTION));
                    if(bitmap){replace_bitmap(app,bitmap);status(app,"Exact renderer preview · frame "+std::to_string(app->current_frame));}
                    else status(app,"Preview bitmap could not be loaded.");
                } else status(app,result->error);
            }
            if(result){std::error_code ec;fs::remove(result->bitmap,ec);}
            return 0;
        }
        case WM_TASK_DONE: {
            std::unique_ptr<TaskResult> task(reinterpret_cast<TaskResult*>(lparam));
            KillTimer(window,kProgressTimer);
            set_busy(app,false,"Ready");
            if (!task) return 0;
            if (task->process.exit_code != 0) {
                status(app,task->process.output.empty()?"Operation failed.":task->process.output);
            } else if (task->kind == TaskResult::Kind::Export) {
                status(app,"Export finished · "+narrow_path(task->output));
            } else {
                cubical::Project imported; std::string error;
                if(cubical::load_ccx(imported,task->output,&error)){app->project=std::move(imported);app->selected=0;app->current_frame=0;sync_project_ui(app);status(app,task->kind==TaskResult::Kind::ImportData?"Data imported through shared engine":"MegaPack imported through shared engine");}
                else status(app,"Import result could not be opened: "+error);
            }
            std::error_code ec; if(task->kind != TaskResult::Kind::Export) fs::remove(task->output,ec); fs::remove(app->progress_file,ec); fs::remove(app->cancel_file,ec);
            if(app->pending_close) DestroyWindow(window);
            return 0;
        }
        case WM_CLOSE:
            if(app->busy){app->pending_close=true;cancel_task(app);status(app,"Canceling current task before closing...");return 0;}
            DestroyWindow(window); return 0;
        case WM_DESTROY: {
            ++app->preview_generation;
            if(app->preview_bitmap)DeleteObject(app->preview_bitmap);
            if(app->font)DeleteObject(app->font);
            std::error_code ec;fs::remove(app->working_ccx,ec);fs::remove(app->preview_bmp,ec);fs::remove(app->progress_file,ec);fs::remove(app->cancel_file,ec);
            PostQuitMessage(0); return 0;
        }
    }
    return DefWindowProcW(window,message,wparam,lparam);
}

} // namespace

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR command_line, int show) {
    if (command_line && std::wstring(command_line).find(L"--self-test") != std::wstring::npos) return run_self_test() ? 0 : 2;

    INITCOMMONCONTROLSEX controls{sizeof(controls),ICC_STANDARD_CLASSES|ICC_BAR_CLASSES|ICC_PROGRESS_CLASS};
    InitCommonControlsEx(&controls);

    WNDCLASSEXW klass{sizeof(klass)};
    klass.lpfnWndProc=window_proc; klass.hInstance=instance; klass.lpszClassName=kWindowClass;
    klass.hCursor=LoadCursorW(nullptr,IDC_ARROW); klass.hIcon=LoadIconW(nullptr,IDI_APPLICATION); klass.hbrBackground=reinterpret_cast<HBRUSH>(COLOR_WINDOW+1);
    if(!RegisterClassExW(&klass))return 1;

    App app; app.instance=instance;
    HWND window=CreateWindowExW(0,kWindowClass,L"Cubical Compare 2.0 · Final",WS_OVERLAPPEDWINDOW|WS_CLIPCHILDREN,
        CW_USEDEFAULT,CW_USEDEFAULT,1440,860,nullptr,nullptr,instance,&app);
    if(!window)return 1;
    ShowWindow(window,show);UpdateWindow(window);
    MSG message{};
    while(GetMessageW(&message,nullptr,0,0)>0){TranslateMessage(&message);DispatchMessageW(&message);}
    return static_cast<int>(message.wParam);
}
