#include "cubical/process.hpp"

#include <array>
#include <cerrno>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <sstream>
#include <thread>

#ifdef _WIN32
#include <windows.h>
#else
#include <signal.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#ifdef __linux__
#include <sys/prctl.h>
#endif
#endif

namespace cubical {
namespace {
std::vector<std::filesystem::path> engine_candidates() {
    std::vector<std::filesystem::path> candidates;

#ifdef CUBICAL_COMPARE_ENGINE_PATH
    if (std::string_view(CUBICAL_COMPARE_ENGINE_PATH).size() > 0) {
        candidates.emplace_back(CUBICAL_COMPARE_ENGINE_PATH);
    }
#endif

    if (const char* configured = std::getenv("CUBICAL_COMPARE_ENGINE"); configured && *configured) {
        candidates.emplace_back(configured);
    }

    const auto base = executable_directory();
#ifdef _WIN32
    candidates.push_back(base / "libexec" / "engine" / "cubical-compare-engine.exe");
    candidates.push_back(base / "engine" / "cubical-compare-engine.exe");
    candidates.push_back(base / "cubical-compare-engine.exe");
#else
    candidates.push_back((base / ".." / "libexec" / "cubical-compare" / "engine" /
                          "cubical-compare-engine").lexically_normal());
    candidates.push_back(base / "libexec" / "engine" / "cubical-compare-engine");
    candidates.push_back(base / "engine" / "cubical-compare-engine");
    candidates.emplace_back("/app/libexec/cubical-compare/engine/cubical-compare-engine");
    candidates.emplace_back("/opt/cubical-compare/libexec/engine/cubical-compare-engine");
#endif
    return candidates;
}

#ifdef _WIN32
std::wstring wide_utf8_or_acp(const std::string& value) {
    if (value.empty()) return {};
    auto convert = [&](UINT code_page, DWORD flags) -> std::wstring {
        const int size = MultiByteToWideChar(code_page, flags, value.data(),
                                             static_cast<int>(value.size()), nullptr, 0);
        if (size <= 0) return {};
        std::wstring result(static_cast<std::size_t>(size), L'\0');
        MultiByteToWideChar(code_page, flags, value.data(), static_cast<int>(value.size()),
                            result.data(), size);
        return result;
    };
    auto result = convert(CP_UTF8, MB_ERR_INVALID_CHARS);
    if (!result.empty()) return result;
    return convert(CP_ACP, 0);
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
        FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM |
            FORMAT_MESSAGE_IGNORE_INSERTS,
        nullptr, code, 0, reinterpret_cast<LPWSTR>(&buffer), 0, nullptr);

    std::string result = "Windows error " + std::to_string(code);
    if (size && buffer) {
        const int bytes = WideCharToMultiByte(CP_UTF8, 0, buffer, static_cast<int>(size),
                                              nullptr, 0, nullptr, nullptr);
        if (bytes > 0) {
            std::string message(static_cast<std::size_t>(bytes), '\0');
            WideCharToMultiByte(CP_UTF8, 0, buffer, static_cast<int>(size),
                                message.data(), bytes, nullptr, nullptr);
            while (!message.empty() &&
                   (message.back() == '\r' || message.back() == '\n' || message.back() == ' ')) {
                message.pop_back();
            }
            if (!message.empty()) result += ": " + message;
        }
        LocalFree(buffer);
    }
    return result;
}
#endif
} // namespace

std::filesystem::path executable_directory() {
#ifdef _WIN32
    std::wstring buffer(32768, L'\0');
    const DWORD length = GetModuleFileNameW(nullptr, buffer.data(),
                                            static_cast<DWORD>(buffer.size()));
    if (length == 0 || length >= buffer.size()) return std::filesystem::current_path();
    buffer.resize(length);
    return std::filesystem::path(buffer).parent_path();
#else
    std::string buffer(4096, '\0');
    const ssize_t length = readlink("/proc/self/exe", buffer.data(), buffer.size() - 1);
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

ProcessResult run_process(const std::filesystem::path& executable,
                          const std::vector<std::string>& args) {
    std::error_code ec;
    if (executable.empty() || !std::filesystem::is_regular_file(executable, ec)) {
        return {-1, "Rendering engine not found at: " + executable.string()};
    }

#ifdef _WIN32
    SECURITY_ATTRIBUTES security{};
    security.nLength = sizeof(security);
    security.bInheritHandle = TRUE;

    HANDLE read_pipe = nullptr;
    HANDLE write_pipe = nullptr;
    if (!CreatePipe(&read_pipe, &write_pipe, &security, 0)) {
        return {-1, "Could not create engine output pipe: " +
                       windows_error_text(GetLastError())};
    }
    SetHandleInformation(read_pipe, HANDLE_FLAG_INHERIT, 0);

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
    startup.hStdOutput = write_pipe;
    startup.hStdError = write_pipe;
    startup.hStdInput = GetStdHandle(STD_INPUT_HANDLE);

    PROCESS_INFORMATION process{};
    const std::wstring working_directory = executable.parent_path().wstring();
    const BOOL started = CreateProcessW(
        executable.wstring().c_str(), mutable_command.data(), nullptr, nullptr, TRUE,
        CREATE_NO_WINDOW, nullptr,
        working_directory.empty() ? nullptr : working_directory.c_str(), &startup, &process);
    CloseHandle(write_pipe);

    if (!started) {
        const DWORD code = GetLastError();
        CloseHandle(read_pipe);
        return {-1, "Could not start rendering engine: " + windows_error_text(code) +
                       "\nEngine: " + executable.string()};
    }

    HANDLE job = CreateJobObjectW(nullptr, nullptr);
    if (job) {
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits,
                                     sizeof(limits)) ||
            !AssignProcessToJobObject(job, process.hProcess)) {
            CloseHandle(job);
            job = nullptr;
        }
    }

    CloseHandle(process.hThread);
    std::string output;
    std::array<char, 4096> buffer{};
    DWORD bytes_read = 0;
    while (ReadFile(read_pipe, buffer.data(), static_cast<DWORD>(buffer.size()),
                    &bytes_read, nullptr) && bytes_read > 0) {
        output.append(buffer.data(), bytes_read);
    }
    CloseHandle(read_pipe);

    WaitForSingleObject(process.hProcess, INFINITE);
    DWORD native_exit_code = 1;
    if (!GetExitCodeProcess(process.hProcess, &native_exit_code)) {
        native_exit_code = GetLastError();
    }
    CloseHandle(process.hProcess);
    if (job) CloseHandle(job);
    const int exit_code = static_cast<int>(native_exit_code);
#else
    if (::access(executable.c_str(), X_OK) != 0) {
        return {-1, "Rendering engine is not executable: " + executable.string()};
    }

    int descriptors[2] = {-1, -1};
    if (::pipe(descriptors) != 0) {
        return {-1, "Could not create engine output pipe: " +
                       std::string(std::strerror(errno))};
    }

    const pid_t child = ::fork();
    if (child < 0) {
        const std::string error = std::strerror(errno);
        ::close(descriptors[0]);
        ::close(descriptors[1]);
        return {-1, "Could not start rendering engine: " + error};
    }

    if (child == 0) {
        ::close(descriptors[0]);
        ::dup2(descriptors[1], STDOUT_FILENO);
        ::dup2(descriptors[1], STDERR_FILENO);
        ::close(descriptors[1]);
#ifdef __linux__
        ::prctl(PR_SET_PDEATHSIG, SIGTERM);
        if (::getppid() == 1) _exit(125);
#endif
        const auto working_directory = executable.parent_path();
        if (!working_directory.empty()) {
            ::chdir(working_directory.c_str());
        }

        std::vector<std::string> storage;
        storage.reserve(args.size() + 1);
        storage.emplace_back(executable.string());
        storage.insert(storage.end(), args.begin(), args.end());
        std::vector<char*> argv;
        argv.reserve(storage.size() + 1);
        for (auto& value : storage) argv.push_back(value.data());
        argv.push_back(nullptr);

        ::execv(storage.front().c_str(), argv.data());
        const std::string message = "Could not execute rendering engine: " +
                                    std::string(std::strerror(errno)) + "\n";
        ::write(STDERR_FILENO, message.data(), message.size());
        _exit(127);
    }

    ::close(descriptors[1]);
    std::string output;
    std::array<char, 4096> buffer{};
    for (;;) {
        const ssize_t count = ::read(descriptors[0], buffer.data(), buffer.size());
        if (count > 0) {
            output.append(buffer.data(), static_cast<std::size_t>(count));
            continue;
        }
        if (count == 0) break;
        if (errno == EINTR) continue;
        output += "Could not read rendering engine output: ";
        output += std::strerror(errno);
        output.push_back('\n');
        break;
    }
    ::close(descriptors[0]);

    int status = 0;
    while (::waitpid(child, &status, 0) < 0 && errno == EINTR) {
    }
    const int exit_code = WIFEXITED(status)
                              ? WEXITSTATUS(status)
                              : (WIFSIGNALED(status) ? 128 + WTERMSIG(status) : status);
#endif

    if (exit_code != 0 && output.empty()) {
        output = "Rendering engine exited with code " + std::to_string(exit_code) +
                 ".\nEngine: " + executable.string();
    }
    return {exit_code, output};
}

ProcessResult run_engine(const std::vector<std::string>& args) {
    return run_process(engine_path(), args);
}

} // namespace cubical
