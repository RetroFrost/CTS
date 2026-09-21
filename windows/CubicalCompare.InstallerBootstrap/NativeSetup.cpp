#define UNICODE
#define _UNICODE
#define NOMINMAX
#include <windows.h>
#include <tlhelp32.h>
#include <commctrl.h>
#include <shellapi.h>
#include <string>
#include <vector>
#include <filesystem>
#include <fstream>
#include <algorithm>
#include <cwctype>
#include <set>

#pragma comment(lib, "user32.lib")
#pragma comment(lib, "comctl32.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "ole32.lib")

namespace {
constexpr wchar_t kWindowClass[] = L"CubicalCompareNativeSetup";
constexpr char kFooterMagic[8] = {'C','C','V','P','K','0','0','1'};
constexpr UINT WM_INSTALL_DONE = WM_APP + 1;
constexpr UINT WM_INSTALL_FAILED = WM_APP + 2;
constexpr UINT WM_INSTALL_PROGRESS = WM_APP + 3;

HWND gWindow = nullptr;
HWND gStatus = nullptr;
HWND gProgress = nullptr;
std::wstring gError;

void SetStatus(const wchar_t* text) {
    if (gStatus) SetWindowTextW(gStatus, text);
}

void SetProgress(int value) {
    value = std::clamp(value, 0, 100);
    if (gWindow)
        PostMessageW(gWindow, WM_INSTALL_PROGRESS, static_cast<WPARAM>(value), 0);
}

void MoveCurrentDirectoryOutsideInstall() {
    wchar_t temp[32768]{};
    DWORD len = GetTempPathW(32768, temp);
    if (!len || len >= 32768 || !SetCurrentDirectoryW(temp))
        throw std::runtime_error("Could not move Setup out of the Cubical Compare install directory.");
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
    SetProgress(5);

    while (remaining > 0) {
        const auto chunk = static_cast<std::streamsize>(std::min<unsigned long long>(remaining, buffer.size()));
        input.read(buffer.data(), chunk);
        if (input.gcount() != chunk) throw std::runtime_error("Installer payload ended unexpectedly.");
        output.write(buffer.data(), chunk);
        if (!output) throw std::runtime_error("Could not write the installer engine.");
        remaining -= static_cast<unsigned long long>(chunk);

        if (payloadSize > 0) {
            const auto copied = payloadSize - remaining;
            SetProgress(5 + static_cast<int>((copied * 20ULL) / payloadSize));
        }
    }

    output.flush();
    SetProgress(25);
}

DWORD RunProcessAndWait(
    const std::wstring& executable,
    const std::wstring& arguments,
    const std::wstring& workingDirectory,
    int progressStart,
    int progressCap) {
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

    int installProgress = progressStart;
    SetProgress(installProgress);

    for (;;) {
        const DWORD wait = WaitForSingleObject(pi.hProcess, 250);
        if (wait == WAIT_OBJECT_0)
            break;
        if (wait == WAIT_TIMEOUT) {
            if (installProgress < progressCap) {
                ++installProgress;
                SetProgress(installProgress);
            }
            continue;
        }

        CloseHandle(pi.hThread);
        CloseHandle(pi.hProcess);
        throw std::runtime_error("Could not wait for the installer engine.");
    }

    DWORD exitCode = 1;
    GetExitCodeProcess(pi.hProcess, &exitCode);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    SetProgress(progressCap);
    return exitCode;
}

std::filesystem::path LocalAppDataRoot() {
    wchar_t localAppData[32768]{};
    DWORD len = GetEnvironmentVariableW(L"LOCALAPPDATA", localAppData, 32768);
    if (!len || len >= 32768)
        throw std::runtime_error("Could not resolve LOCALAPPDATA.");
    return std::filesystem::path(localAppData);
}

std::filesystem::path DefaultInstallRoot() {
    return LocalAppDataRoot() / L"CubicalCompare";
}

std::filesystem::path InstallerStateRoot() {
    return LocalAppDataRoot() / L"RetroFrost" / L"CubicalCompare" / L"Installer";
}

std::filesystem::path ActiveRootPointerPath() {
    return InstallerStateRoot() / L"active-root.txt";
}

std::filesystem::path ReadPreferredInstallRoot() {
    const auto pointer = ActiveRootPointerPath();
    std::wifstream input(pointer);
    std::wstring line;
    if (input && std::getline(input, line) && !line.empty()) {
        std::filesystem::path candidate(line);
        std::error_code ec;
        if (std::filesystem::exists(candidate / L"current" / L"CubicalCompare.exe", ec))
            return candidate;
    }
    return DefaultInstallRoot();
}

void WritePreferredInstallRoot(const std::filesystem::path& root) {
    std::error_code ec;
    std::filesystem::create_directories(InstallerStateRoot(), ec);
    if (ec) return;

    std::wofstream output(ActiveRootPointerPath(), std::ios::trunc);
    if (!output) return;
    output << root.wstring();
}

std::filesystem::path NextRecoveryInstallRoot() {
    const auto parent = LocalAppDataRoot();
    for (int index = 1; index <= 99; ++index) {
        const auto name = index == 1
            ? std::wstring(L"CubicalCompare-Recovery")
            : std::wstring(L"CubicalCompare-Recovery-") + std::to_wstring(index);
        const auto candidate = parent / name;
        std::error_code ec;
        if (!std::filesystem::exists(candidate, ec))
            return candidate;
    }

    GUID guid{};
    if (FAILED(CoCreateGuid(&guid)))
        throw std::runtime_error("Could not allocate a recovery install location.");
    wchar_t token[64]{};
    StringFromGUID2(guid, token, 64);
    return parent / (std::wstring(L"CubicalCompare-Recovery-") + token);
}

std::vector<std::filesystem::path> KnownInstallRoots() {
    std::vector<std::filesystem::path> roots;
    roots.push_back(DefaultInstallRoot());
    const auto preferred = ReadPreferredInstallRoot();
    if (LowerPath(preferred.wstring()) != LowerPath(roots.front().wstring()))
        roots.push_back(preferred);
    return roots;
}

std::wstring QuoteCommandArgument(const std::filesystem::path& path) {
    std::wstring value = path.wstring();
    std::wstring escaped;
    escaped.reserve(value.size() + 2);
    escaped.push_back(L'"');
    for (wchar_t ch : value) {
        if (ch == L'"') escaped.push_back(L'\\');
        escaped.push_back(ch);
    }
    escaped.push_back(L'"');
    return escaped;
}

std::wstring VelopackLogPath() {
    wchar_t localAppData[32768]{};
    DWORD len = GetEnvironmentVariableW(L"LOCALAPPDATA", localAppData, 32768);
    if (!len || len >= 32768) return L"%LOCALAPPDATA%\\velopack\\velopack.log";
    return (std::filesystem::path(localAppData) / L"velopack" / L"velopack.log").wstring();
}

BOOL CALLBACK CloseCubicalCompareWindow(HWND hwnd, LPARAM lParam) {
    DWORD windowPid = 0;
    GetWindowThreadProcessId(hwnd, &windowPid);
    if (windowPid == static_cast<DWORD>(lParam))
        PostMessageW(hwnd, WM_CLOSE, 0, 0);
    return TRUE;
}

struct ProcessRecord {
    DWORD pid{};
    DWORD parentPid{};
    std::wstring name;
    std::wstring imagePath;
};

std::wstring LowerPath(std::wstring value) {
    std::transform(value.begin(), value.end(), value.begin(),
        [](wchar_t ch) { return static_cast<wchar_t>(std::towlower(ch)); });
    return value;
}

std::wstring QueryProcessImagePath(DWORD pid) {
    HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!process) return L"";

    std::vector<wchar_t> buffer(32768);
    DWORD size = static_cast<DWORD>(buffer.size());
    std::wstring result;
    if (QueryFullProcessImageNameW(process, 0, buffer.data(), &size))
        result.assign(buffer.data(), size);
    CloseHandle(process);
    return result;
}

std::vector<ProcessRecord> SnapshotProcesses() {
    std::vector<ProcessRecord> records;
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE_VALUE) return records;

    PROCESSENTRY32W entry{};
    entry.dwSize = sizeof(entry);
    if (Process32FirstW(snapshot, &entry)) {
        do {
            if (entry.th32ProcessID == 0) continue;
            records.push_back(ProcessRecord{
                entry.th32ProcessID,
                entry.th32ParentProcessID,
                entry.szExeFile,
                QueryProcessImagePath(entry.th32ProcessID)
            });
        } while (Process32NextW(snapshot, &entry));
    }

    CloseHandle(snapshot);
    return records;
}

bool IsPathInside(const std::wstring& candidate, const std::filesystem::path& root) {
    if (candidate.empty()) return false;

    std::error_code ec;
    const auto normalizedRoot = LowerPath(std::filesystem::weakly_canonical(root, ec).wstring());
    if (ec || normalizedRoot.empty()) return false;

    ec.clear();
    const auto normalizedCandidate =
        LowerPath(std::filesystem::weakly_canonical(std::filesystem::path(candidate), ec).wstring());
    if (ec || normalizedCandidate.size() < normalizedRoot.size()) return false;
    if (normalizedCandidate.compare(0, normalizedRoot.size(), normalizedRoot) != 0) return false;

    return normalizedCandidate.size() == normalizedRoot.size() ||
           normalizedCandidate[normalizedRoot.size()] == L'\\' ||
           normalizedCandidate[normalizedRoot.size()] == L'/';
}

void StopConflictingCubicalCompareProcesses() {
    const DWORD currentPid = GetCurrentProcessId();
    const auto installRoots = KnownInstallRoots();
    const auto records = SnapshotProcesses();
    std::set<DWORD> targets;

    for (const auto& record : records) {
        if (record.pid == currentPid) continue;

        const bool app =
            _wcsicmp(record.name.c_str(), L"CubicalCompare.exe") == 0;
        const bool installResident = std::any_of(
            installRoots.begin(),
            installRoots.end(),
            [&](const std::filesystem::path& root) { return IsPathInside(record.imagePath, root); });
        const bool staleEmbeddedSetup =
            _wcsicmp(record.name.c_str(), L"CubicalCompare-Velopack-Setup.exe") == 0;
        const auto lowerName = LowerPath(record.name);
        const bool stalePublicSetup =
            lowerName.rfind(L"cubicalcompare-", 0) == 0 &&
            lowerName.find(L"-setup.exe") != std::wstring::npos;

        if (app || installResident || staleEmbeddedSetup || stalePublicSetup)
            targets.insert(record.pid);
    }

    // Include descendants such as ffmpeg/export helpers whose executable may live
    // outside the installation directory but whose inherited current directory
    // still pins the app tree.
    bool changed = true;
    while (changed) {
        changed = false;
        for (const auto& record : records) {
            if (record.pid == currentPid || targets.find(record.pid) != targets.end())
                continue;
            if (targets.find(record.parentPid) != targets.end()) {
                targets.insert(record.pid);
                changed = true;
            }
        }
    }

    if (targets.empty()) return;

    SetStatus(L"Closing Cubical Compare and background helpers...");
    SetProgress(30);

    for (DWORD pid : targets)
        EnumWindows(CloseCubicalCompareWindow, static_cast<LPARAM>(pid));

    const ULONGLONG deadline = GetTickCount64() + 5000;
    while (GetTickCount64() < deadline) {
        bool anyRunning = false;
        for (DWORD pid : targets) {
            HANDLE process = OpenProcess(SYNCHRONIZE, FALSE, pid);
            if (!process) continue;
            if (WaitForSingleObject(process, 0) == WAIT_TIMEOUT)
                anyRunning = true;
            CloseHandle(process);
        }
        if (!anyRunning) break;
        Sleep(100);
    }

    for (DWORD pid : targets) {
        HANDLE process = OpenProcess(SYNCHRONIZE | PROCESS_TERMINATE, FALSE, pid);
        if (!process) continue;
        if (WaitForSingleObject(process, 0) == WAIT_TIMEOUT) {
            TerminateProcess(process, 0);
            WaitForSingleObject(process, 3000);
        }
        CloseHandle(process);
    }

    SetProgress(35);
}

bool IsLegacyBrokenInstall(const std::filesystem::path& root) {
    const auto current = root / L"current";
    std::error_code ec;
    if (!std::filesystem::exists(current, ec)) return false;

    const bool nestedCurrent =
        std::filesystem::exists(current / L"current" / L"CubicalCompare.exe", ec);
    const bool copiedPortableMarker =
        std::filesystem::exists(current / L".portable", ec);
    const bool copiedRootUpdater =
        std::filesystem::exists(current / L"Update.exe", ec);
    const bool managedButManifestMissing =
        std::filesystem::exists(root / L"Update.exe", ec) &&
        std::filesystem::exists(current / L"CubicalCompare.exe", ec) &&
        !std::filesystem::exists(current / L"sq.version", ec);

    return nestedCurrent || copiedPortableMarker || copiedRootUpdater || managedButManifestMissing;
}

void MoveDirectoryPreservingData(
    const std::filesystem::path& source,
    const std::filesystem::path& destination) {
    std::error_code ec;
    std::filesystem::remove_all(destination, ec);
    ec.clear();
    std::filesystem::rename(source, destination, ec);
    if (!ec) return;

    ec.clear();
    std::filesystem::copy(
        source,
        destination,
        std::filesystem::copy_options::recursive |
            std::filesystem::copy_options::overwrite_existing,
        ec);
    if (ec) throw std::runtime_error("Could not preserve the previous installation before repair.");

    ec.clear();
    std::filesystem::remove_all(source, ec);
    if (ec) throw std::runtime_error("Could not clear the damaged installation before repair.");
}

std::filesystem::path TryQuarantineBrokenInstall(
    const std::filesystem::path& installRoot,
    const std::filesystem::path& workspace) {
    if (!IsLegacyBrokenInstall(installRoot)) return {};

    SetStatus(L"Repairing the previous Cubical Compare installation...");
    const auto current = installRoot / L"current";
    const auto backup = workspace / L"previous-broken-current";
    try {
        MoveDirectoryPreservingData(current, backup);
        return backup;
    } catch (...) {
        // A third-party process may still have a handle open. Do not fail here:
        // the smart installer can fall back to a fresh install root instead.
        return {};
    }
}

void RestoreQuarantinedInstall(
    const std::filesystem::path& installRoot,
    const std::filesystem::path& backup) {
    if (backup.empty() || !std::filesystem::exists(backup)) return;

    const auto current = installRoot / L"current";
    std::error_code ec;
    std::filesystem::remove_all(current, ec);
    MoveDirectoryPreservingData(backup, current);
}

std::wstring FindInstalledExe(const std::filesystem::path& root) {
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
    std::wstring workspace;
    std::filesystem::path quarantinedInstall;
    std::filesystem::path primaryRoot;
    std::filesystem::path installedRoot;
    gError.clear();

    try {
        MoveCurrentDirectoryOutsideInstall();
        SetProgress(1);
        SetStatus(L"Preparing smart installer...");
        workspace = TempRoot();
        const std::wstring payload = workspace + L"\\CubicalCompare-Velopack-Setup.exe";
        ExtractPayload(GetModulePath(), payload);

        StopConflictingCubicalCompareProcesses();

        primaryRoot = ReadPreferredInstallRoot();
        quarantinedInstall = TryQuarantineBrokenInstall(primaryRoot, workspace);
        SetProgress(40);

        SetStatus(L"Installing Cubical Compare...");
        const std::wstring primaryArgs =
            L"--silent --installto " + QuoteCommandArgument(primaryRoot);
        const DWORD primaryExit = RunProcessAndWait(payload, primaryArgs, workspace, 45, 72);

        if (primaryExit == 0) {
            installedRoot = primaryRoot;
            WritePreferredInstallRoot(installedRoot);
        } else {
            if (!quarantinedInstall.empty()) {
                try {
                    RestoreQuarantinedInstall(primaryRoot, quarantinedInstall);
                    quarantinedInstall.clear();
                } catch (...) {
                    // The legacy root is no longer required for recovery installation.
                }
            }

            // Unknown processes (Explorer extensions, scanners, shells, elevated
            // helpers, etc.) may keep the old root locked. Do not make the user
            // hunt the handle owner. Install a clean managed copy elsewhere and
            // let Velopack update shortcuts/registry to that active location.
            SetStatus(L"Existing install is locked. Switching to recovery location...");
            SetProgress(75);
            const auto recoveryRoot = NextRecoveryInstallRoot();
            const std::wstring recoveryArgs =
                L"--silent --installto " + QuoteCommandArgument(recoveryRoot);
            const DWORD recoveryExit =
                RunProcessAndWait(payload, recoveryArgs, workspace, 78, 94);

            if (recoveryExit != 0) {
                gError =
                    L"Smart installer could not update the existing copy (code " +
                    std::to_wstring(primaryExit) +
                    L") or create a clean recovery copy (code " +
                    std::to_wstring(recoveryExit) +
                    L"). Details: " + VelopackLogPath();
                throw std::runtime_error("smart installer exhausted both install paths");
            }

            installedRoot = recoveryRoot;
            WritePreferredInstallRoot(installedRoot);
        }

        SetStatus(L"Starting Cubical Compare...");
        SetProgress(96);
        const std::wstring installed = FindInstalledExe(installedRoot);
        if (installed.empty())
            throw std::runtime_error("Installation finished, but CubicalCompare.exe was not found.");

        LaunchInstalledApp(installed);
        SetProgress(100);

        std::error_code ec;
        std::filesystem::remove_all(workspace, ec);
        PostMessageW(gWindow, WM_INSTALL_DONE, 0, 0);
        return 0;
    } catch (const std::exception& ex) {
        if (!quarantinedInstall.empty() && !primaryRoot.empty()) {
            try {
                RestoreQuarantinedInstall(primaryRoot, quarantinedInstall);
            } catch (...) {
                if (gError.empty())
                    gError = L"Installation failed and the previous install could not be restored automatically.";
            }
        }

        if (!workspace.empty()) {
            std::error_code ec;
            std::filesystem::remove_all(workspace, ec);
        }

        if (gError.empty()) {
            int needed = MultiByteToWideChar(CP_UTF8, 0, ex.what(), -1, nullptr, 0);
            if (needed > 1) {
                std::vector<wchar_t> wide(static_cast<size_t>(needed));
                MultiByteToWideChar(CP_UTF8, 0, ex.what(), -1, wide.data(), needed);
                gError = wide.data();
            } else {
                gError = L"Installation failed.";
            }
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
                24, 58, 452, 44,
                hwnd, nullptr, nullptr, nullptr);
            SendMessageW(gStatus, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);

            gProgress = CreateWindowExW(
                0, PROGRESS_CLASSW, nullptr,
                WS_CHILD | WS_VISIBLE,
                24, 112, 452, 18,
                hwnd, nullptr, nullptr, nullptr);
            SendMessageW(gProgress, PBM_SETRANGE32, 0, 100);
            SendMessageW(gProgress, PBM_SETPOS, 0, 0);

            HANDLE thread = CreateThread(nullptr, 0, InstallWorker, nullptr, 0, nullptr);
            if (thread) CloseHandle(thread);
            else PostMessageW(hwnd, WM_INSTALL_FAILED, 0, 0);
            return 0;
        }
        case WM_CLOSE:
            // Installation owns the window lifecycle. There is no success OK button.
            return 0;
        case WM_INSTALL_PROGRESS:
            SendMessageW(gProgress, PBM_SETPOS, wParam, 0);
            return 0;
        case WM_INSTALL_DONE:
            SendMessageW(gProgress, PBM_SETPOS, 100, 0);
            DestroyWindow(hwnd);
            return 0;
        case WM_INSTALL_FAILED: {
            SetStatus(gError.empty() ? L"Installation failed." : gError.c_str());

            HWND closeButton = CreateWindowExW(
                0, L"BUTTON", L"Close",
                WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON,
                400, 148, 76, 28,
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
    try {
        MoveCurrentDirectoryOutsideInstall();
    } catch (...) {
        MessageBoxW(nullptr, L"Setup could not release its working-directory lock.", L"Cubical Compare Setup", MB_OK | MB_ICONERROR);
        return 2;
    }

    HANDLE setupMutex = CreateMutexW(nullptr, TRUE, L"Local\\CubicalCompare.Setup.Singleton.v1");
    if (!setupMutex) return 3;
    if (GetLastError() == ERROR_ALREADY_EXISTS) {
        MessageBoxW(nullptr, L"Another Cubical Compare setup is already running.", L"Cubical Compare Setup", MB_OK | MB_ICONINFORMATION);
        CloseHandle(setupMutex);
        return 4;
    }

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
    const int height = 225;
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
    ReleaseMutex(setupMutex);
    CloseHandle(setupMutex);
    return 0;
}
