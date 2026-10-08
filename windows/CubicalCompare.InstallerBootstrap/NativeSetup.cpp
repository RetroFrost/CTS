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
#include <bcrypt.h>
#include <dwmapi.h>
#include "SetupPayload.h"

#pragma comment(lib, "user32.lib")
#pragma comment(lib, "comctl32.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "bcrypt.lib")
#pragma comment(lib, "advapi32.lib")
#pragma comment(lib, "dwmapi.lib")

namespace {
constexpr wchar_t kWindowClass[] = L"CubicalCompareNativeSetup";
constexpr char kFooterMagic[8] = {'C','C','V','P','K','0','0','1'};
constexpr UINT WM_INSTALL_DONE = WM_APP + 1;
constexpr UINT WM_INSTALL_FAILED = WM_APP + 2;
constexpr UINT WM_INSTALL_PROGRESS = WM_APP + 3;
constexpr UINT WM_INSTALL_STATUS = WM_APP + 4;

HWND gWindow = nullptr;
HWND gStatus = nullptr;
HWND gProgress = nullptr;
std::wstring gError;
std::filesystem::path gRequestedRoot;
std::filesystem::path gRunningRoot;
std::wstring gStatusText = L"Preparing installer...";
HFONT gBodyFont = nullptr;
int gDpi = 96;
bool gFailed = false;
int Px(int value) { return MulDiv(value, gDpi, 96); }

void LogSetup(const std::wstring& message) {
    wchar_t local[32768]{};
    if (!GetEnvironmentVariableW(L"LOCALAPPDATA", local, 32768)) return;
    std::error_code ec;
    const auto folder = std::filesystem::path(local) / L"RetroFrost" / L"CubicalCompare" / L"Installer";
    std::filesystem::create_directories(folder, ec);
    std::wofstream log(folder / L"setup.log", std::ios::app);
    log << message << L"\n";
}

void SetStatus(const wchar_t* text) {
    LogSetup(text);
    auto status = new std::wstring(text);
    if (!gWindow || !PostMessageW(gWindow, WM_INSTALL_STATUS, 0, reinterpret_cast<LPARAM>(status))) delete status;
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
    LogSetup(L"Installer engine exit: " + std::to_wstring(exitCode));
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
    wchar_t registered[32768]{};
    DWORD bytes = sizeof(registered);
    if (RegGetValueW(HKEY_CURRENT_USER,
            L"Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\CubicalCompare",
            L"InstallLocation", RRF_RT_REG_SZ, nullptr, registered, &bytes) == ERROR_SUCCESS) {
        const auto root = std::filesystem::path(registered);
        if (std::filesystem::exists(root / L"current" / L"CubicalCompare.exe")) return root;
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
    if (_wcsicmp(preferred.wstring().c_str(), roots.front().wstring().c_str()) != 0)
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

std::filesystem::path ManagedRootForExecutable(const std::wstring& executable) {
    if (executable.empty()) return {};
    auto cursor = std::filesystem::path(executable).parent_path();
    std::filesystem::path result;
    for (int depth = 0; depth < 8 && !cursor.empty(); ++depth) {
        const auto parent = cursor.parent_path();
        if (_wcsicmp(cursor.filename().c_str(), L"current") == 0 &&
            std::filesystem::exists(parent / L"Update.exe") &&
            !std::filesystem::exists(parent / L".portable")) result = parent;
        if (parent == cursor) break;
        cursor = parent;
    }
    return result;
}

void DiscoverRunningInstall() {
    std::set<std::filesystem::path> roots;
    for (const auto& process : SnapshotProcesses()) {
        if (_wcsicmp(process.name.c_str(), L"CubicalCompare.exe") != 0) continue;
        const auto root = ManagedRootForExecutable(process.imagePath);
        if (!root.empty()) roots.insert(root);
    }
    if (roots.size() == 1) gRunningRoot = *roots.begin();
}

std::string Sha256File(const std::filesystem::path& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) throw std::runtime_error("An installed application file is missing or unreadable.");
    BCRYPT_ALG_HANDLE algorithm = nullptr;
    BCRYPT_HASH_HANDLE hash = nullptr;
    if (BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0) < 0)
        throw std::runtime_error("Could not initialize installed-file verification.");
    std::string result;
    try {
        if (BCryptCreateHash(algorithm, &hash, nullptr, 0, nullptr, 0, 0) < 0)
            throw std::runtime_error("Could not create installed-file verification hash.");
        std::vector<unsigned char> buffer(1024 * 1024);
        while (input) {
            input.read(reinterpret_cast<char*>(buffer.data()), buffer.size());
            const auto count = input.gcount();
            if (count > 0 && BCryptHashData(hash, buffer.data(), static_cast<ULONG>(count), 0) < 0)
                throw std::runtime_error("Could not hash the installed application.");
        }
        if (!input.eof()) throw std::runtime_error("Could not read the installed application.");
        unsigned char digest[32]{};
        if (BCryptFinishHash(hash, digest, sizeof(digest), 0) < 0)
            throw std::runtime_error("Could not finish installed-file verification.");
        constexpr char hex[] = "0123456789abcdef";
        for (const auto value : digest) { result += hex[value >> 4]; result += hex[value & 15]; }
    } catch (...) {
        if (hash) BCryptDestroyHash(hash);
        BCryptCloseAlgorithmProvider(algorithm, 0);
        throw;
    }
    BCryptDestroyHash(hash);
    BCryptCloseAlgorithmProvider(algorithm, 0);
    return result;
}

void VerifyInstalledPayload(const std::filesystem::path& root) {
    SetStatus(L"Checking the installed application files...");
    size_t index = 0;
    for (const auto& file : kPayloadFiles) {
        const auto path = root / L"current" / file.path;
        if (Sha256File(path) != file.sha256) {
            LogSetup(L"Installed file mismatch: " + path.wstring());
            throw std::runtime_error("Setup did not replace all application files. See the setup log for the mismatched file.");
        }
        SetProgress(94 + static_cast<int>((++index * 2) / std::size(kPayloadFiles)));
    }
    if (!std::filesystem::exists(root / L"Update.exe") || !std::filesystem::exists(root / L"current" / L"sq.version"))
        throw std::runtime_error("The installed update metadata is incomplete.");
    LogSetup(L"Verified release " + std::wstring(kAppVersion) + L" in " + root.wstring());
}

void StopConflictingCubicalCompareProcesses() {
    const DWORD currentPid = GetCurrentProcessId();
    auto installRoots = KnownInstallRoots();
    if (!gRequestedRoot.empty()) installRoots.push_back(gRequestedRoot);
    if (!gRunningRoot.empty()) installRoots.push_back(gRunningRoot);
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

        DiscoverRunningInstall();
        primaryRoot = !gRequestedRoot.empty() ? gRequestedRoot :
            !gRunningRoot.empty() ? gRunningRoot : ReadPreferredInstallRoot();
        SetStatus((L"Updating application files in " + primaryRoot.wstring()).c_str());
        StopConflictingCubicalCompareProcesses();
        quarantinedInstall = TryQuarantineBrokenInstall(primaryRoot, workspace);
        SetProgress(40);

        SetStatus(L"Installing Cubical Compare...");
        const std::wstring primaryArgs =
            L"--silent --installto " + QuoteCommandArgument(primaryRoot);
        const DWORD primaryExit = RunProcessAndWait(payload, primaryArgs, workspace, 45, 72);

        if (primaryExit == 0) {
            installedRoot = primaryRoot;

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

        }

        VerifyInstalledPayload(installedRoot);
        WritePreferredInstallRoot(installedRoot);
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

        LogSetup(L"Installation failed: " + gError);
        PostMessageW(gWindow, WM_INSTALL_FAILED, 0, 0);
        return 1;
    }
}

void FillRounded(HDC dc, const RECT& rect, COLORREF color, int radius) {
    const auto brush = CreateSolidBrush(color);
    const auto oldBrush = SelectObject(dc, brush);
    const auto oldPen = SelectObject(dc, GetStockObject(NULL_PEN));
    RoundRect(dc, rect.left, rect.top, rect.right, rect.bottom, Px(radius), Px(radius));
    SelectObject(dc, oldPen); SelectObject(dc, oldBrush); DeleteObject(brush);
}

HFONT Font(int size, int weight = FW_NORMAL) {
    return CreateFontW(-MulDiv(size, gDpi, 72), 0, 0, 0, weight, FALSE, FALSE, FALSE,
        DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
        DEFAULT_PITCH, L"Segoe UI");
}

void Text(HDC dc, const wchar_t* value, RECT rect, int size, COLORREF color, int weight = FW_NORMAL) {
    auto font = Font(size, weight);
    auto old = SelectObject(dc, font);
    SetBkMode(dc, TRANSPARENT); SetTextColor(dc, color);
    DrawTextW(dc, value, -1, &rect, DT_LEFT | DT_WORDBREAK | DT_NOPREFIX);
    SelectObject(dc, old); DeleteObject(font);
}

LRESULT CALLBACK ProgressProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
    if (message == PBM_SETPOS) {
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, std::clamp(static_cast<int>(wParam), 0, 100));
        InvalidateRect(hwnd, nullptr, FALSE); return 0;
    }
    if (message == PBM_SETRANGE32) return 0;
    if (message == WM_ERASEBKGND) return 1;
    if (message == WM_PAINT) {
        PAINTSTRUCT paint{}; const auto dc = BeginPaint(hwnd, &paint);
        RECT rect{}; GetClientRect(hwnd, &rect);
        FillRect(dc, &rect, static_cast<HBRUSH>(GetStockObject(WHITE_BRUSH)));
        FillRounded(dc, rect, RGB(235, 233, 242), 8);
        const auto percent = static_cast<int>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
        if (percent > 0) {
            auto filled = rect; filled.right = std::max(Px(8), rect.right * percent / 100);
            FillRounded(dc, filled, RGB(108, 77, 226), 8);
        }
        EndPaint(hwnd, &paint); return 0;
    }
    return DefWindowProcW(hwnd, message, wParam, lParam);
}

LRESULT CALLBACK WindowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
    switch (message) {
        case WM_CREATE: {
            gWindow = hwnd;
            gDpi = static_cast<int>(GetDpiForWindow(hwnd));
            gBodyFont = Font(10);
            gStatus = CreateWindowExW(0, L"STATIC", gStatusText.c_str(), WS_CHILD | WS_VISIBLE,
                Px(48), Px(180), Px(524), Px(64), hwnd, nullptr, nullptr, nullptr);
            SendMessageW(gStatus, WM_SETFONT, reinterpret_cast<WPARAM>(gBodyFont), TRUE);
            gProgress = CreateWindowExW(0, L"CubicalCompareSetupProgress", nullptr, WS_CHILD | WS_VISIBLE,
                Px(48), Px(254), Px(524), Px(8), hwnd, nullptr, nullptr, nullptr);
            SendMessageW(gProgress, PBM_SETRANGE32, 0, 100);
            SendMessageW(gProgress, PBM_SETPOS, 0, 0);
            HANDLE thread = CreateThread(nullptr, 0, InstallWorker, nullptr, 0, nullptr);
            if (thread) CloseHandle(thread);
            else { gError = L"Could not start the installer."; PostMessageW(hwnd, WM_INSTALL_FAILED, 0, 0); }
            return 0;
        }
        case WM_DPICHANGED: {
            gDpi = HIWORD(wParam);
            const auto suggested = reinterpret_cast<RECT*>(lParam);
            SetWindowPos(hwnd, nullptr, suggested->left, suggested->top, suggested->right - suggested->left, suggested->bottom - suggested->top, SWP_NOZORDER | SWP_NOACTIVATE);
            auto previous = gBodyFont; gBodyFont = Font(10);
            SendMessageW(gStatus, WM_SETFONT, reinterpret_cast<WPARAM>(gBodyFont), TRUE);
            if (previous) DeleteObject(previous);
            MoveWindow(gStatus, Px(48), Px(180), Px(524), Px(64), TRUE);
            MoveWindow(gProgress, Px(48), Px(254), Px(524), Px(8), TRUE);
            if (const auto close = GetDlgItem(hwnd, 1)) {
                MoveWindow(close, Px(496), Px(299), Px(96), Px(32), TRUE);
                SendMessageW(close, WM_SETFONT, reinterpret_cast<WPARAM>(gBodyFont), TRUE);
            }
            InvalidateRect(hwnd, nullptr, FALSE); return 0;
        }
        case WM_PAINT: {
            PAINTSTRUCT paint{}; const auto dc = BeginPaint(hwnd, &paint);
            RECT client{}; GetClientRect(hwnd, &client);
            const auto background = CreateSolidBrush(RGB(246, 245, 250));
            FillRect(dc, &client, background); DeleteObject(background);
            RECT icon{Px(32), Px(30), Px(84), Px(82)};
            FillRounded(dc, icon, RGB(108, 77, 226), 14);
            Text(dc, L"C", {Px(46), Px(32), Px(78), Px(78)}, 24, RGB(255,255,255), FW_SEMIBOLD);
            Text(dc, L"Cubical Compare", {Px(102), Px(27), Px(588), Px(64)}, 22, RGB(35,30,49), FW_SEMIBOLD);
            const std::wstring version = L"Windows setup  /  " + std::wstring(kAppVersion);
            Text(dc, version.c_str(), {Px(104), Px(67), Px(580), Px(90)}, 10, RGB(113,105,128));
            FillRounded(dc, {Px(28), Px(112), Px(592), Px(286)}, RGB(255,255,255), 20);
            Text(dc, gFailed ? L"Setup needs attention" : L"Getting everything ready", {Px(48), Px(133), Px(572), Px(170)}, 15, RGB(35,30,49), FW_SEMIBOLD);
            Text(dc, gFailed ? L"Details: Local AppData / RetroFrost / CubicalCompare / Installer / setup.log" : L"Your projects and settings stay with you.", {Px(32), Px(307), Px(gFailed ? 472 : 560), Px(342)}, 10, RGB(113,105,128));
            EndPaint(hwnd, &paint); return 0;
        }
        case WM_CTLCOLORSTATIC:
            SetBkMode(reinterpret_cast<HDC>(wParam), TRANSPARENT);
            SetTextColor(reinterpret_cast<HDC>(wParam), RGB(96,88,111));
            return reinterpret_cast<LRESULT>(GetStockObject(WHITE_BRUSH));
        case WM_ERASEBKGND: return 1;
        case WM_INSTALL_STATUS: {
            auto status = reinterpret_cast<std::wstring*>(lParam);
            gStatusText = *status; delete status;
            SetWindowTextW(gStatus, gStatusText.c_str()); return 0;
        }
        case WM_CLOSE:
            if (gFailed) DestroyWindow(hwnd);
            return 0;
        case WM_INSTALL_PROGRESS:
            SendMessageW(gProgress, PBM_SETPOS, wParam, 0); return 0;
        case WM_INSTALL_DONE:
            SendMessageW(gProgress, PBM_SETPOS, 100, 0);
            DestroyWindow(hwnd); return 0;
        case WM_INSTALL_FAILED: {
            gFailed = true;
            SetWindowTextW(gStatus, gError.empty() ? L"Installation failed. See the setup log in Local AppData / RetroFrost / CubicalCompare / Installer." : gError.c_str());
            InvalidateRect(hwnd, nullptr, FALSE);
            auto closeButton = CreateWindowExW(0, L"BUTTON", L"Close", WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON,
                Px(496), Px(299), Px(96), Px(32), hwnd, reinterpret_cast<HMENU>(1), nullptr, nullptr);
            SendMessageW(closeButton, WM_SETFONT, reinterpret_cast<WPARAM>(gBodyFont), TRUE); return 0;
        }
        case WM_COMMAND:
            if (LOWORD(wParam) == 1) { DestroyWindow(hwnd); return 0; }
            break;
        case WM_DESTROY:
            if (gBodyFont) DeleteObject(gBodyFont);
            PostQuitMessage(gFailed ? 1 : 0); return 0;
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

    int argc = 0;
    auto argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    bool invalidArgs = false;
    for (int i = 1; argv && i < argc; ++i) {
        if (std::wstring(argv[i]) == L"--installto" && i + 1 < argc) {
            gRequestedRoot = argv[++i];
            if (!gRequestedRoot.is_absolute() || !std::filesystem::exists(gRequestedRoot / L"Update.exe") ||
                std::filesystem::exists(gRequestedRoot / L".portable")) invalidArgs = true;
        } else invalidArgs = true;
    }
    if (argv) LocalFree(argv);
    if (invalidArgs) {
        MessageBoxW(nullptr, L"Setup could not identify the requested managed installation. Use Update from ZIP for a portable copy.", L"Cubical Compare Setup", MB_OK | MB_ICONERROR);
        ReleaseMutex(setupMutex); CloseHandle(setupMutex); return 5;
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
    wc.hbrBackground = nullptr;
    wc.lpszClassName = kWindowClass;
    RegisterClassExW(&wc);

    WNDCLASSEXW progressClass = wc;
    progressClass.lpfnWndProc = ProgressProc;
    progressClass.lpszClassName = L"CubicalCompareSetupProgress";
    RegisterClassExW(&progressClass);
    gDpi = static_cast<int>(GetDpiForSystem());
    RECT dimensions{0, 0, Px(620), Px(350)};
    AdjustWindowRectExForDpi(&dimensions, WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU, FALSE, 0, gDpi);
    const int width = dimensions.right - dimensions.left;
    const int height = dimensions.bottom - dimensions.top;
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
    const int rounded = 2;
    DwmSetWindowAttribute(gWindow, 33, &rounded, sizeof(rounded));
    const COLORREF caption = RGB(246,245,250);
    DwmSetWindowAttribute(gWindow, 35, &caption, sizeof(caption));
    ShowWindow(gWindow, SW_SHOW);
    UpdateWindow(gWindow);

    MSG msg{};
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    ReleaseMutex(setupMutex);
    CloseHandle(setupMutex);
    return static_cast<int>(msg.wParam);
}
