#pragma once
#include <filesystem>
#include <string>
#include <vector>

namespace cubical {
struct ProcessResult {
    int exit_code{-1};
    std::string output;
};

std::filesystem::path executable_directory();
std::filesystem::path engine_path();
std::filesystem::path temporary_path(const std::string& stem, const std::string& extension);
ProcessResult run_process(const std::filesystem::path& executable, const std::vector<std::string>& args);
ProcessResult run_engine(const std::vector<std::string>& args);
}
