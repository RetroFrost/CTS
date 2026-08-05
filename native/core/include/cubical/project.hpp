#pragma once
#include <cstdint>
#include <filesystem>
#include <string>
#include <vector>

namespace cubical {

std::string new_card_id();

struct Card {
    std::string title;
    std::string value;
    std::string description;
    std::string image;
    std::string id{new_card_id()};
    double image_x{0.0};
    double image_y{0.0};
    double image_scale{1.0};
    double image_rotation{0.0};
    double image_crop_left{0.0};
    double image_crop_top{0.0};
    double image_crop_right{0.0};
    double image_crop_bottom{0.0};
    std::string image_layer{"behind"};
};

struct Settings {
    std::uint32_t width{1920};
    std::uint32_t height{1080};
    std::uint32_t fps{60};
    bool auto_length{true};
    double custom_length_seconds{60.0};

    bool credits_enabled{true};
    std::string credits_top_text{"Values are estimates and may vary."};
    std::string credits_heading{"Credits"};
    std::string credits_project_name{"Cubical Compare"};
    std::string credits_created_with_label{"Created with"};
    std::string credits_created_with_value{"Cubical Compare"};
    std::string credits_design_label{"Design & Rendering"};
    std::string credits_design_value{"Cubical"};
    std::string credits_footer{"CREDITS ARE OPTIONAL"};

    std::string end_best_label{"BEST VIDEO FOR YOU"};
    std::string end_newest_label{"NEWEST VIDEO"};
    std::string end_credit_label{"Video Made By"};
    std::string end_credit_value{"Cubical Compare"};

    std::string soundtrack;
    double soundtrack_volume{0.75};
    bool soundtrack_loop{true};
    double soundtrack_offset_seconds{0.0};
    double soundtrack_fade_out_seconds{0.75};

    std::string encoder_preset{"faster"};
    std::uint32_t encoder_crf{18};
    std::string font_title;
    std::string font_description;
    std::string font_badge;
    std::string font_credits;
    std::string image_fit_mode{"cover"};
};

struct Project {
    std::string name;
    std::vector<Card> cards{{"Card 1", "1", "", ""}};
    Settings settings;
    std::filesystem::path project_path;
};

std::string summary(const Project& project);
int find_card_index_by_id(const Project& project, const std::string& id);
int erase_card_by_id(Project& project, const std::string& id);
double timeline_duration(const Project& project);
std::string base64_encode(const std::string& input);
std::string base64_decode(const std::string& input);
bool save_ccx(const Project& project, const std::filesystem::path& path, std::string* error = nullptr);
bool load_ccx(Project& project, const std::filesystem::path& path, std::string* error = nullptr);

} // namespace cubical
