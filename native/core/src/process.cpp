#include "cubical/process.hpp"
#include <chrono>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <thread>
#ifndef _WIN32
#include <sys/wait.h>
#endif
#ifdef _WIN32
#include <windows.h>
#else
#include <unistd.h>
#endif

namespace cubical {
namespace {
std::string quote_shell(const std::string& value) {
#ifdef _WIN32
    std::string out = "\"";
    unsigned backslashes = 0;
    for (char c : value) {
        if (c == '\\') {
            ++backslashes;
            continue;
        }
        if (c == '"') {
            out.append(backslashes * 2 + 1, '\\');
            out += '"';
            backslashes = 0;
            continue;
        }
        out.append(backslashes, '\\');
        backslashes = 0;
        out += c;
    }
    out.append(backslashes * 2, '\\');
    out += '"';
    return out;
#else
    std::string out = "'";
    for (char c : value) {
        if (c == '\'') out += "'\\''"; else out += c;
    }
    out += "'";
    return out;
#endif
}

std::vector<std::filesystem::path> engine_candidates() {
    std::vector<std::filesystem::path> candidates;
    if (const char* configured = std::getenv("CUBICAL_CREATE_ENGINE"); configured && *configured) {
        candidates.emplace_back(configured);
    }
    const auto base = executable_directory();
#ifdef _WIN32
    candidates.push_back(base / "cubical-create-engine.exe");
    candidates.push_back(base / "engine" / "cubical-create-engine.exe");
#else
    candidates.push_back(base / "cubical-create-engine");
    candidates.push_back(base / "engine" / "cubical-create-engine");
    candidates.emplace_back("/opt/cubical-create/cubical-create-engine");
    candidates.emplace_back("/opt/cubical-create/engine/cubical-create-engine");
#endif
    return candidates;
}
}

#ifdef _WIN32
std::wstring wide_utf8_or_acp(const std::string& value) {
    if (value.empty()) return {};
    auto convert = [&](UINT code_page) -> std::wstring {
        const int size = MultiByteToWideChar(code_page, MB_ERR_INVALID_CHARS, value.data(),
                                             static_cast<int>(value.size()), nullptr, 0);
        if (size <= 0) return {};
        std::wstring result(static_cast<std::size_t>(size), L'\0');
        MultiByteToWideChar(code_page, MB_ERR_INVALID_CHARS, value.data(),
                            static_cast<int>(value.size()), result.data(), size);
        return result;
    };
    auto result = convert(CP_UTF8);
    if (!result.empty()) return result;
    const int size = MultiByteToWideChar(CP_ACP, 0, value.data(), static_cast<int>(value.size()), nullptr, 0);
    if (size <= 0) return {};
    result.assign(static_cast<std::size_t>(size), L'\0');
    MultiByteToWideChar(CP_ACP, 0, value.data(), static_cast<int>(value.size()), result.data(), size);
    return result;
}

std::wstring quote_windows_argument(const std::wstring& value) {
    if (value.empty()) return L"\"\"";
    if (value.find_first_of(L" \t\n\v\"") == std::wstring::npos) return value;
    std::wstring out = L"\"";
    unsigned backslashes = 0;
    for (wchar_t c : value) {
        if (c == L'\\') {
            ++backslashes;
            continue;
        }
        if (c == L'\"') {
            out.append(backslashes * 2 + 1, L'\\');
            out.push_back(L'\"');
            backslashes = 0;
            continue;
        }
        out.append(backslashes, L'\\');
        backslashes = 0;
        out.push_back(c);
    }
    out.append(backslashes * 2, L'\\');
    out.push_back(L'\"');
    return out;
}

std::string windows_error_text(DWORD code) {
    LPWSTR buffer = nullptr;
    const DWORD size = FormatMessageW(
        FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
        nullptr, code, 0, reinterpret_cast<LPWSTR>(&buffer), 0, nullptr);
    std::string result = "Windows error " + std::to_string(code);
    if (size && buffer) {
        const int bytes = WideCharToMultiByte(CP_UTF8, 0, buffer, static_cast<int>(size), nullptr, 0, nullptr, nullptr);
        if (bytes > 0) {
            std::string message(static_cast<std::size_t>(bytes), '\0');
            WideCharToMultiByte(CP_UTF8, 0, buffer, static_cast<int>(size), message.data(), bytes, nullptr, nullptr);
            while (!message.empty() && (message.back() == '\r' || message.back() == '\n' || message.back() == ' ')) {
                message.pop_back();
            }
            if (!message.empty()) result += ": " + message;
        }
        LocalFree(buffer);
    }
    return result;
}
#endif

std::filesystem::path executable_directory() {
#ifdef _WIN32
    std::wstring buffer(32768, L'\0');
    DWORD length = GetModuleFileNameW(nullptr, buffer.data(), static_cast<DWORD>(buffer.size()));
    buffer.resize(length);
    return std::filesystem::path(buffer).parent_path();
#else
    std::string buffer(4096, '\0');
    ssize_t length = readlink("/proc/self/exe", buffer.data(), buffer.size() - 1);
    if (length > 0) {
        buffer.resize(static_cast<std::size_t>(length));
        return std::filesystem::path(buffer).parent_path();
    }
    return std::filesystem::current_path();
#endif
}

std::filesystem::path engine_path() {
    const auto candidates = engine_candidates();
    for (const auto& candidate : candidates) {
        std::error_code ec;
        if (std::filesystem::is_regular_file(candidate, ec)) return candidate;
    }
    return candidates.empty() ? std::filesystem::path{} : candidates.front();
}

std::filesystem::path temporary_path(const std::string& stem, const std::string& extension) {
    const auto now = std::chrono::high_resolution_clock::now().time_since_epoch().count();
    std::ostringstream name;
    name << stem << '-' << now << '-' << std::this_thread::get_id() << extension;
    return std::filesystem::temp_directory_path() / name.str();
}

ProcessResult run_process(const std::filesystem::path& executable, const std::vector<std::string>& args) {
    std::error_code ec;
    if (executable.empty() || !std::filesystem::is_regular_file(executable, ec)) {
        return {-1, "Rendering engine not found at: " + executable.string()};
    }
#ifndef _WIN32
    if (::access(executable.c_str(), X_OK) != 0) {
        return {-1, "Rendering engine is not executable: " + executable.string()};
    }

    const auto capture = temporary_path("cubical-create-output", ".txt");
    std::ostringstream command;
    command << quote_shell(executable.string());
    for (const auto& arg : args) command << ' ' << quote_shell(arg);
    command << " > " << quote_shell(capture.string()) << " 2>&1";

    const int raw = std::system(command.str().c_str());
    std::ifstream in(capture, std::ios::binary);
    std::ostringstream output;
    output << in.rdbuf();
    std::filesystem::remove(capture, ec);
    const int exit_code = raw != -1 && WIFEXITED(raw) ? WEXITSTATUS(raw) : raw;
#else
    const auto capture = temporary_path("cubical-create-output", ".txt");
    SECURITY_ATTRIBUTES security{};
    security.nLength = sizeof(security);
    security.bInheritHandle = TRUE;
    HANDLE capture_handle = CreateFileW(
        capture.wstring().c_str(), GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE,
        &security, CREATE_ALWAYS, FILE_ATTRIBUTE_TEMPORARY, nullptr);
    if (capture_handle == INVALID_HANDLE_VALUE) {
        return {-1, "Could not create engine output capture: " + windows_error_text(GetLastError())};
    }

    std::wstring command_line = quote_windows_argument(executable.wstring());
    for (const auto& arg : args) {
        command_line.push_back(L' ');
        command_line += quote_windows_argument(wide_utf8_or_acp(arg));
    }
    std::vector<wchar_t> mutable_command(command_line.begin(), command_line.end());
    mutable_command.push_back(L'\0');

    STARTUPINFOW startup{};
    startup.cb = sizeof(startup);
    startup.dwFlags = STARTF_USESTDHANDLES;
    startup.hStdOutput = capture_handle;
    startup.hStdError = capture_handle;
    startup.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    PROCESS_INFORMATION process{};
    const BOOL started = CreateProcessW(
        executable.wstring().c_str(), mutable_command.data(), nullptr, nullptr, TRUE,
        CREATE_NO_WINDOW, nullptr, executable.parent_path().wstring().c_str(), &startup, &process);
    if (!started) {
        const DWORD code = GetLastError();
        CloseHandle(capture_handle);
        std::filesystem::remove(capture, ec);
        return {-1, "Could not start rendering engine: " + windows_error_text(code) +
                    "\nEngine: " + executable.string()};
    }

    CloseHandle(process.hThread);
    WaitForSingleObject(process.hProcess, INFINITE);
    DWORD native_exit_code = 1;
    if (!GetExitCodeProcess(process.hProcess, &native_exit_code)) native_exit_code = GetLastError();
    CloseHandle(process.hProcess);
    FlushFileBuffers(capture_handle);
    CloseHandle(capture_handle);

    std::ifstream in(capture, std::ios::binary);
    std::ostringstream output;
    output << in.rdbuf();
    std::filesystem::remove(capture, ec);
    const int exit_code = static_cast<int>(native_exit_code);
#endif
    std::string text = output.str();
    if (exit_code != 0 && text.empty()) {
        text = "Rendering engine exited with code " + std::to_string(exit_code) + ".\nEngine: " + executable.string();
    }
    return {exit_code, text};
}


ProcessResult run_engine(const std::vector<std::string>& args) {
    return run_process(engine_path(), args);
}

} // namespace cubical
