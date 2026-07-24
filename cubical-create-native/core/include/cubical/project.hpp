#pragma once
#include <cstdint>
#include <filesystem>
#include <string>
#include <vector>

namespace cubical {
struct Card {
    std::string title;
    std::string value;
    std::string description;
    std::filesystem::path image;
};

struct Credits {
    bool enabled{true};
    std::string top_text{"Values are estimates and may vary."};
    std::string heading{"Credits"};
    std::string project_name{"Cubical Create"};
    std::string created_with_label{"Created with"};
    std::string created_with_value{"Cubical Create"};
    std::string design_label{"Design & Rendering"};
    std::string design_value{"Cubical"};
    std::string footer{"CREDITS ARE OPTIONAL"};
};

struct Soundtrack {
    std::filesystem::path path;
    double volume{0.75};
    bool loop{true};
    double offset_seconds{0.0};
    double fade_out_seconds{0.75};
};

struct Project {
    std::string name{"Untitled Comparison"};
    std::vector<Card> cards;
    Credits credits;
    Soundtrack soundtrack;
    std::uint32_t width{1920};
    std::uint32_t height{1080};
    std::uint32_t fps{60};
    std::string encoder_preset{"faster"};
    std::uint32_t encoder_crf{18};
};

std::string summary(const Project& project);
}
