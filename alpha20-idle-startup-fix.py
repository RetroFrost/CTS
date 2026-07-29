#!/usr/bin/env python3
"""Patch Cubical Create Alpha 20 to launch its engine directly on Windows."""
from __future__ import annotations
import sys
from pathlib import Path

WINDOWS_HELPERS = r'''
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
'''

NEW_FUNCTION = r'''ProcessResult run_process(const std::filesystem::path& executable, const std::vector<std::string>& args) {
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
'''


def replace_function(text: str, signature: str, replacement: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise RuntimeError(f"Could not find {signature}")
    brace = text.find("{", start)
    depth = 0
    for i in range(brace, len(text)):
        if text[i] == "{": depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[:start] + replacement + text[i+1:]
    raise RuntimeError("Unterminated function")


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: alpha20-engine-launch-fix.py SOURCE_DIRECTORY", file=sys.stderr)
        return 2
    root = Path(sys.argv[1]).resolve()
    process = root / "native" / "core" / "src" / "process.cpp"
    text = process.read_text(encoding="utf-8")
    if "std::wstring quote_windows_argument" not in text:
        marker = "}\n\nstd::filesystem::path executable_directory()"
        if marker not in text:
            raise RuntimeError("Could not find process helper insertion point")
        text = text.replace(marker, "}\n" + WINDOWS_HELPERS + "\nstd::filesystem::path executable_directory()", 1)
    text = replace_function(text, "ProcessResult run_process(", NEW_FUNCTION)
    process.write_text(text, encoding="utf-8")

    windows = root / "native" / "windows" / "main.cpp"
    wtext = windows.read_text(encoding="utf-8")
    wtext = wtext.replace("load_project(s);initialize_player_idle(s);SetTimer", "load_project(s);reset_player(s);SetTimer")
    if "load_project(s);reset_player(s);SetTimer" not in wtext:
        raise RuntimeError("Windows startup preview call was not restored")
    windows.write_text(wtext, encoding="utf-8")

    linux = root / "native" / "linux-gtk" / "main.cpp"
    ltext = linux.read_text(encoding="utf-8")
    ltext = ltext.replace("initialize_player_idle(static_cast<AppState*>(data));", "reset_player(static_cast<AppState*>(data));")
    linux.write_text(ltext, encoding="utf-8")
    print("Patched Windows engine launch to use CreateProcessW and restored startup preview rendering.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
