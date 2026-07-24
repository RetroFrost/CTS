#define UNICODE
#define _UNICODE
#include <windows.h>
#include <commctrl.h>
#include <shobjidl.h>
#include <shellapi.h>
#include <string>
#include "cubical/project.hpp"

namespace {
constexpr wchar_t kWindowClass[] = L"CubicalCreateWindow";
constexpr int ID_IMPORT_SHEET = 1001;
constexpr int ID_EXPORT = 1002;

std::wstring choose_file(HWND owner) {
    IFileOpenDialog* dialog = nullptr;
    std::wstring result;
    if (SUCCEEDED(CoCreateInstance(CLSID_FileOpenDialog, nullptr, CLSCTX_INPROC_SERVER,
                                   IID_PPV_ARGS(&dialog)))) {
        COMDLG_FILTERSPEC filters[] = {
            {L"Images", L"*.png;*.jpg;*.jpeg;*.webp;*.bmp"},
            {L"All files", L"*.*"},
        };
        dialog->SetFileTypes(2, filters);
        if (SUCCEEDED(dialog->Show(owner))) {
            IShellItem* item = nullptr;
            if (SUCCEEDED(dialog->GetResult(&item))) {
                PWSTR path = nullptr;
                if (SUCCEEDED(item->GetDisplayName(SIGDN_FILESYSPATH, &path))) {
                    result = path;
                    CoTaskMemFree(path);
                }
                item->Release();
            }
        }
        dialog->Release();
    }
    return result;
}

LRESULT CALLBACK WindowProc(HWND hwnd, UINT msg, WPARAM wparam, LPARAM lparam) {
    switch (msg) {
    case WM_CREATE:
        CreateWindowExW(0, WC_STATICW, L"Cubical Create", WS_CHILD | WS_VISIBLE,
                        20, 18, 500, 34, hwnd, nullptr, nullptr, nullptr);
        CreateWindowExW(0, WC_BUTTONW, L"Insert Data", WS_CHILD | WS_VISIBLE | BS_DEFPUSHBUTTON,
                        20, 520, 180, 42, hwnd, nullptr, nullptr, nullptr);
        CreateWindowExW(0, WC_BUTTONW, L"Image Sheet", WS_CHILD | WS_VISIBLE,
                        210, 520, 150, 42, hwnd, reinterpret_cast<HMENU>(ID_IMPORT_SHEET), nullptr, nullptr);
        CreateWindowExW(0, WC_BUTTONW, L"Project Controls", WS_CHILD | WS_VISIBLE,
                        370, 520, 180, 42, hwnd, nullptr, nullptr, nullptr);
        CreateWindowExW(0, WC_BUTTONW, L"Export", WS_CHILD | WS_VISIBLE,
                        560, 520, 120, 42, hwnd, reinterpret_cast<HMENU>(ID_EXPORT), nullptr, nullptr);
        return 0;
    case WM_COMMAND:
        if (LOWORD(wparam) == ID_IMPORT_SHEET) {
            const auto path = choose_file(hwnd);
            if (!path.empty()) {
                const std::wstring title = L"Cubical Create — " + path;
                SetWindowTextW(hwnd, title.c_str());
            }
            return 0;
        }
        if (LOWORD(wparam) == ID_EXPORT) {
            MessageBoxW(hwnd, L"Native export engine migration is in progress.", L"Cubical Create", MB_OK | MB_ICONINFORMATION);
            return 0;
        }
        break;
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    default:
        break;
    }
    return DefWindowProcW(hwnd, msg, wparam, lparam);
}
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int show) {
    if (FAILED(CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED))) return 1;

    INITCOMMONCONTROLSEX controls{sizeof(controls), ICC_STANDARD_CLASSES | ICC_BAR_CLASSES | ICC_LISTVIEW_CLASSES};
    InitCommonControlsEx(&controls);

    WNDCLASSW wc{};
    wc.lpfnWndProc = WindowProc;
    wc.hInstance = instance;
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1);
    wc.lpszClassName = kWindowClass;
    if (!RegisterClassW(&wc)) {
        CoUninitialize();
        return 2;
    }

    HWND window = CreateWindowExW(0, kWindowClass, L"Cubical Create",
                                  WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT,
                                  1000, 650, nullptr, nullptr, instance, nullptr);
    if (!window) {
        CoUninitialize();
        return 3;
    }

    ShowWindow(window, show);
    UpdateWindow(window);

    MSG message{};
    while (GetMessageW(&message, nullptr, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
    CoUninitialize();
    return static_cast<int>(message.wParam);
}
