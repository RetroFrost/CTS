#define UNICODE
#define _UNICODE
#define NOMINMAX
#include <windows.h>
#include <commctrl.h>
#include <shellapi.h>
#include <string>
#include <vector>
#include <filesystem>
#include <fstream>
#include <algorithm>

#pragma comment(lib, "user32.lib")
#pragma comment(lib, "comctl32.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "ole32.lib")

namespace {
constexpr wchar_t kWindowClass[] = L"CubicalCompareNativeSetup";
constexpr char kFooterMagic[8] = {'C','C','V','P','K','0','0','1'};
constexpr UINT WM_INSTALL_DONE = WM_APP + 1;
constexpr UINT WM_INSTALL_FAILED = WM_APP + 2;

HWND gWindow = nullptr;
HWND gStatus = nullptr;
HWND gProgress = nullptr;
std::wstring gError;

void SetStatus(const wchar_t* text) {
    if (gStatus) SetWindowTextW(gStatus, text);
}

std::wstring GetModulePath() {
    std::vector<wchar_t> buffer(32768);
    DWORD len = GetModuleFileNameW(nullptr, buffer.data(), static_cast<DWORD>(buffer.size()));
    if (!len || len >= buffer.size()) throw std::runtime_error("Could not locate installer executable.");
    return std::wstring(buffer.data(), len);
}

std::wstring TempRoot() {
    std::vector<wchar_t> temp(32768);
    DWORD len = GetTempPathW(static_cast<DWORD>(temp.size()), temp.data());
    if (!len || len >= temp.size()) throw std::runtime_error("Could not resolve the temporary directory.");

    GUID guid{};
    if (FAILED(CoCreateGuid(&guid))) throw std::runtime_error("Could not allocate installer workspace.");

    wchar_t token[64]{};
    StringFromGUID2(guid, token, 64);
    std::wstring root = std::wstring(temp.data()) + L"CubicalCompare\\Installer\\" + token;
    std::filesystem::create_directories(root);
    return root;
}

void ExtractPayload(const std::wstring& self, const std::wstring& destination) {
    std::ifstream input(self, std::ios::binary | std::ios::ate);
    if (!input) throw std::runtime_error("Could not open the installer payload.");

    const auto end = input.tellg();
    if (end < static_cast<std::streamoff>(16)) throw std::runtime_error("Installer payload is incomplete.");

    input.seekg(end - static_cast<std::streamoff>(16));
    char magic[8]{};
    unsigned long long payloadSize = 0;
    input.read(magic, 8);
    input.read(reinterpret_cast<char*>(&payloadSize), sizeof(payloadSize));

    if (!input || memcmp(magic, kFooterMagic, 8) != 0)
        throw std::runtime_error("Installer payload footer is invalid.");

    const auto payloadStart = end - static_cast<std::streamoff>(16) - static_cast<std::streamoff>(payloadSize);
    if (payloadStart < 0) throw std::runtime_error("Installer payload size is invalid.");

    input.seekg(payloadStart);
    std::ofstream output(destination, std::ios::binary | std::ios::trunc);
    if (!output) throw std::runtime_error("Could not create the embedded installer engine.");

    constexpr size_t kBufferSize = 1024 * 1024;
    std::vector<char> buffer(kBufferSize);
    unsigned long long remaining = payloadSize;
    while (remaining > 0) {
        const auto chunk = static_cast<std::streamsize>(std::min<unsigned long long>(remaining, buffer.size()));
        input.read(buffer.data(), chunk);
        if (input.gcount() != chunk) throw std::runtime_error("Installer payload ended unexpectedly.");
        output.write(buffer.data(), chunk);
        if (!output) throw std::runtime_error("Could not write the installer engine.");
        remaining -= static_cast<unsigned long long>(chunk);
    }
}

DWORD RunProcessAndWait(const std::wstring& executable, const std::wstring& arguments, const std::wstring& workingDirectory) {
    std::wstring command = L"\"" + executable + L"\" " + arguments;
    std::vector<wchar_t> mutableCommand(command.begin(), command.end());
    mutableCommand.push_back(L'\0');

    STARTUPINFOW si{};
    si.cb = sizeof(si);
    PROCESS_INFORMATION pi{};

    if (!CreateProcessW(
            executable.c_str(),
            mutableCommand.data(),
            nullptr,
            nullptr,
            FALSE,
            CREATE_NO_WINDOW,
            nullptr,
            workingDirectory.c_str(),
            &si,
            &pi)) {
        throw std::runtime_error("Could not start the installer engine.");
    }

    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD exitCode = 1;
    GetExitCodeProcess(pi.hProcess, &exitCode);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return exitCode;
}

std::wstring FindInstalledExe() {
    wchar_t localAppData[32768]{};
    DWORD len = GetEnvironmentVariableW(L"LOCALAPPDATA", localAppData, 32768);
    if (!len || len >= 32768) return L"";

    std::filesystem::path root = std::filesystem::path(localAppData) / L"CubicalCompare";
    std::filesystem::path expected = root / L"current" / L"CubicalCompare.exe";
    if (std::filesystem::exists(expected)) return expected.wstring();

    if (!std::filesystem::exists(root)) return L"";
    std::error_code ec;
    for (const auto& entry : std::filesystem::recursive_directory_iterator(
             root, std::filesystem::directory_options::skip_permission_denied, ec)) {
        if (ec) break;
        if (entry.is_regular_file(ec) && _wcsicmp(entry.path().filename().c_str(), L"CubicalCompare.exe") == 0)
            return entry.path().wstring();
    }
    return L"";
}

void LaunchInstalledApp(const std::wstring& executable) {
    std::wstring command = L"\"" + executable + L"\"";
    std::vector<wchar_t> mutableCommand(command.begin(), command.end());
    mutableCommand.push_back(L'\0');

    STARTUPINFOW si{};
    si.cb = sizeof(si);
    PROCESS_INFORMATION pi{};
    const auto working = std::filesystem::path(executable).parent_path().wstring();

    if (!CreateProcessW(
            executable.c_str(),
            mutableCommand.data(),
            nullptr,
            nullptr,
            FALSE,
            0,
            nullptr,
            working.c_str(),
            &si,
            &pi)) {
        throw std::runtime_error("Cubical Compare installed, but Windows could not start it.");
    }

    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
}

DWORD WINAPI InstallWorker(void*) {
    std::wstring root;
    try {
        SetStatus(L"Preparing files...");
        root = TempRoot();
        const std::wstring payload = root + L"\\CubicalCompare-Velopack-Setup.exe";
        ExtractPayload(GetModulePath(), payload);

        SetStatus(L"Installing Cubical Compare...");
        const DWORD exitCode = RunProcessAndWait(payload, L"--silent", root);
        if (exitCode != 0)
            throw std::runtime_error("The installer engine returned a failure code.");

        SetStatus(L"Starting Cubical Compare...");
        const std::wstring installed = FindInstalledExe();
        if (installed.empty())
            throw std::runtime_error("Installation finished, but CubicalCompare.exe was not found.");

        LaunchInstalledApp(installed);
        std::error_code ec;
        std::filesystem::remove_all(root, ec);
        PostMessageW(gWindow, WM_INSTALL_DONE, 0, 0);
        return 0;
    } catch (const std::exception& ex) {
        if (!root.empty()) {
            std::error_code ec;
            std::filesystem::remove_all(root, ec);
        }
        int needed = MultiByteToWideChar(CP_UTF8, 0, ex.what(), -1, nullptr, 0);
        if (needed > 1) {
            std::vector<wchar_t> wide(static_cast<size_t>(needed));
            MultiByteToWideChar(CP_UTF8, 0, ex.what(), -1, wide.data(), needed);
            gError = wide.data();
        } else {
            gError = L"Installation failed.";
        }
        PostMessageW(gWindow, WM_INSTALL_FAILED, 0, 0);
        return 1;
    }
}

LRESULT CALLBACK WindowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
    switch (message) {
        case WM_CREATE: {
            HFONT font = static_cast<HFONT>(GetStockObject(DEFAULT_GUI_FONT));

            HWND title = CreateWindowExW(
                0, L"STATIC", L"Installing Cubical Compare",
                WS_CHILD | WS_VISIBLE,
                24, 20, 450, 28,
                hwnd, nullptr, nullptr, nullptr);
            SendMessageW(title, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);

            gStatus = CreateWindowExW(
                0, L"STATIC", L"Preparing installer...",
                WS_CHILD | WS_VISIBLE,
                24, 58, 452, 24,
                hwnd, nullptr, nullptr, nullptr);
            SendMessageW(gStatus, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);

            gProgress = CreateWindowExW(
                0, PROGRESS_CLASSW, nullptr,
                WS_CHILD | WS_VISIBLE | PBS_MARQUEE,
                24, 92, 452, 18,
                hwnd, nullptr, nullptr, nullptr);
            SendMessageW(gProgress, PBM_SETMARQUEE, TRUE, 20);

            HANDLE thread = CreateThread(nullptr, 0, InstallWorker, nullptr, 0, nullptr);
            if (thread) CloseHandle(thread);
            else PostMessageW(hwnd, WM_INSTALL_FAILED, 0, 0);
            return 0;
        }
        case WM_CLOSE:
            // Installation owns the window lifecycle. There is no success OK button.
            return 0;
        case WM_INSTALL_DONE:
            DestroyWindow(hwnd);
            return 0;
        case WM_INSTALL_FAILED: {
            SendMessageW(gProgress, PBM_SETMARQUEE, FALSE, 0);
            SetStatus(gError.empty() ? L"Installation failed." : gError.c_str());

            HWND closeButton = CreateWindowExW(
                0, L"BUTTON", L"Close",
                WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON,
                400, 118, 76, 28,
                hwnd, reinterpret_cast<HMENU>(1), nullptr, nullptr);
            SendMessageW(closeButton, WM_SETFONT, reinterpret_cast<WPARAM>(GetStockObject(DEFAULT_GUI_FONT)), TRUE);
            return 0;
        }
        case WM_COMMAND:
            if (LOWORD(wParam) == 1) {
                DestroyWindow(hwnd);
                return 0;
            }
            break;
        case WM_DESTROY:
            PostQuitMessage(0);
            return 0;
    }
    return DefWindowProcW(hwnd, message, wParam, lParam);
}
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int) {
    INITCOMMONCONTROLSEX controls{};
    controls.dwSize = sizeof(controls);
    controls.dwICC = ICC_PROGRESS_CLASS;
    InitCommonControlsEx(&controls);

    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = WindowProc;
    wc.hInstance = instance;
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1);
    wc.lpszClassName = kWindowClass;
    RegisterClassExW(&wc);

    const int width = 520;
    const int height = 190;
    const int x = (GetSystemMetrics(SM_CXSCREEN) - width) / 2;
    const int y = (GetSystemMetrics(SM_CYSCREEN) - height) / 2;

    gWindow = CreateWindowExW(
        0,
        kWindowClass,
        L"Cubical Compare Setup",
        WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU,
        x, y, width, height,
        nullptr, nullptr, instance, nullptr);

    if (!gWindow) return 1;
    ShowWindow(gWindow, SW_SHOW);
    UpdateWindow(gWindow);

    MSG msg{};
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    return 0;
}
