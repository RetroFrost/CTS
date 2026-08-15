#include "cubical/project.hpp"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <array>
#include <fstream>
#include <iomanip>
#include <map>
#include <sstream>
#include <cmath>
#include <stdexcept>

namespace cubical {

std::string new_card_id() {
    static std::atomic<unsigned long long> counter{0};
    const auto ticks = std::chrono::high_resolution_clock::now().time_since_epoch().count();
    std::ostringstream out;
    out << std::hex << ticks << '-' << counter.fetch_add(1, std::memory_order_relaxed);
    return out.str();
}

int find_card_index_by_id(const Project& project, const std::string& id) {
    if (id.empty()) return -1;
    for (std::size_t i = 0; i < project.cards.size(); ++i) {
        if (project.cards[i].id == id) return static_cast<int>(i);
    }
    return -1;
}

int erase_card_by_id(Project& project, const std::string& id) {
    if (project.cards.size() <= 1) return -1;
    const int index = find_card_index_by_id(project, id);
    if (index < 0) return -1;
    project.cards.erase(project.cards.begin() + index);
    return std::min(index, static_cast<int>(project.cards.size()) - 1);
}

namespace {
constexpr char kBase64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

std::string bool_text(bool value) { return value ? "1" : "0"; }

void put(std::ostream& out, const std::string& key, const std::string& value) {
    out << key << '=' << value << '\n';
}
void puts(std::ostream& out, const std::string& key, const std::string& value) {
    put(out, key, base64_encode(value));
}

template<typename T>
T number(const std::map<std::string, std::string>& values, const std::string& key, T fallback) {
    auto it = values.find(key);
    if (it == values.end()) return fallback;
    std::istringstream in(it->second);
    T result{};
    if (!(in >> result)) return fallback;
    return result;
}

std::string decoded(const std::map<std::string, std::string>& values, const std::string& key, const std::string& fallback = {}) {
    auto it = values.find(key);
    return it == values.end() ? fallback : base64_decode(it->second);
}

double finite_number(double value, double fallback) { return std::isfinite(value) ? value : fallback; }
std::uint32_t even_dimension(std::uint32_t value, std::uint32_t fallback, std::uint32_t maximum) {
    value = std::clamp(value ? value : fallback, std::uint32_t{64}, maximum);
    if (value & 1U) value += value < maximum ? 1U : static_cast<std::uint32_t>(-1);
    return value;
}
}


double timeline_duration(const Project& project) {
    if (project.cards.empty()) return 0.0;
    const std::size_t count = project.cards.size();
    constexpr std::uint32_t opening_ends[] = {120, 240, 360, 528};
    constexpr std::uint32_t outro_frames = 409;
    const std::uint32_t content_frames = count == 57
        ? 11858
        : count <= 4
            ? opening_ends[count - 1]
            : static_cast<std::uint32_t>(528 + (count - 4) * 214);
    const double minimum = static_cast<double>(content_frames + outro_frames) / 60.0;
    if (!project.settings.auto_length) return std::max(finite_number(project.settings.custom_length_seconds, minimum), minimum);
    return minimum;
}

std::string summary(const Project& project) {
    std::ostringstream out;
    out << (project.name.empty() ? "New project" : project.name) << " • " << project.cards.size() << " cards • "
        << project.settings.width << 'x' << project.settings.height << " @ "
        << project.settings.fps << " FPS";
    return out.str();
}

std::string base64_encode(const std::string& input) {
    std::string out;
    int val = 0, bits = -6;
    for (unsigned char c : input) {
        val = (val << 8) + c;
        bits += 8;
        while (bits >= 0) {
            out.push_back(kBase64[(val >> bits) & 0x3F]);
            bits -= 6;
        }
    }
    if (bits > -6) out.push_back(kBase64[((val << 8) >> (bits + 8)) & 0x3F]);
    return out;
}

std::string base64_decode(const std::string& input) {
    std::array<int, 256> table{};
    table.fill(-1);
    for (int i = 0; i < 64; ++i) table[static_cast<unsigned char>(kBase64[i])] = i;
    std::string out;
    int val = 0, bits = -8;
    for (unsigned char c : input) {
        const int mapped = table[c];
        if (mapped < 0) throw std::runtime_error("Invalid base64 data in project file");
        val = (val << 6) + mapped;
        bits += 6;
        if (bits >= 0) {
            out.push_back(static_cast<char>((val >> bits) & 0xFF));
            bits -= 8;
        }
    }
    return out;
}

bool save_ccx(const Project& project, const std::filesystem::path& path, std::string* error) {
    std::ofstream out(path, std::ios::binary);
    if (!out) {
        if (error) *error = "Could not write " + path.string();
        return false;
    }
    const auto& s = project.settings;
    out << "CCX1\n";
    puts(out, "project.name", project.name);
    puts(out, "project.model_id", s.model_id);
    put(out, "project.model_revision", std::to_string(s.model_revision));
    put(out, "project.width", std::to_string(s.width));
    put(out, "project.height", std::to_string(s.height));
    put(out, "project.fps", std::to_string(s.fps));
    put(out, "project.auto_length", bool_text(s.auto_length));
    put(out, "project.custom_length_seconds", std::to_string(s.custom_length_seconds));
    put(out, "project.credits_enabled", bool_text(s.credits_enabled));
#define PUTS_FIELD(name) puts(out, "project." #name, s.name)
    PUTS_FIELD(credits_top_text); PUTS_FIELD(credits_heading); PUTS_FIELD(credits_project_name);
    PUTS_FIELD(credits_created_with_label); PUTS_FIELD(credits_created_with_value);
    PUTS_FIELD(credits_design_label); PUTS_FIELD(credits_design_value); PUTS_FIELD(credits_footer);
    PUTS_FIELD(end_best_label); PUTS_FIELD(end_newest_label); PUTS_FIELD(end_credit_label); PUTS_FIELD(end_credit_value);
    PUTS_FIELD(soundtrack); PUTS_FIELD(encoder_preset); PUTS_FIELD(font_title); PUTS_FIELD(font_description);
    PUTS_FIELD(font_badge); PUTS_FIELD(font_credits); PUTS_FIELD(image_fit_mode);
#undef PUTS_FIELD
    put(out, "project.soundtrack_volume", std::to_string(s.soundtrack_volume));
    put(out, "project.soundtrack_loop", bool_text(s.soundtrack_loop));
    put(out, "project.soundtrack_offset_seconds", std::to_string(s.soundtrack_offset_seconds));
    put(out, "project.soundtrack_fade_out_seconds", std::to_string(s.soundtrack_fade_out_seconds));
    put(out, "project.encoder_crf", std::to_string(s.encoder_crf));
    put(out, "cards.count", std::to_string(project.cards.size()));
    for (std::size_t i = 0; i < project.cards.size(); ++i) {
        const auto prefix = "card." + std::to_string(i) + ".";
        puts(out, prefix + "title", project.cards[i].title);
        puts(out, prefix + "value", project.cards[i].value);
        puts(out, prefix + "description", project.cards[i].description);
        puts(out, prefix + "image", project.cards[i].image);
        puts(out, prefix + "id", project.cards[i].id);
        put(out, prefix + "image_x", std::to_string(project.cards[i].image_x));
        put(out, prefix + "image_y", std::to_string(project.cards[i].image_y));
        put(out, prefix + "image_scale", std::to_string(project.cards[i].image_scale));
        put(out, prefix + "image_rotation", std::to_string(project.cards[i].image_rotation));
        put(out, prefix + "image_crop_left", std::to_string(project.cards[i].image_crop_left));
        put(out, prefix + "image_crop_top", std::to_string(project.cards[i].image_crop_top));
        put(out, prefix + "image_crop_right", std::to_string(project.cards[i].image_crop_right));
        put(out, prefix + "image_crop_bottom", std::to_string(project.cards[i].image_crop_bottom));
        puts(out, prefix + "image_layer", project.cards[i].image_layer);
    }
    return true;
}

bool load_ccx(Project& project, const std::filesystem::path& path, std::string* error) {
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        if (error) *error = "Could not open " + path.string();
        return false;
    }
    std::string line;
    if (!std::getline(in, line) || line != "CCX1") {
        if (error) *error = "Unsupported project interchange file";
        return false;
    }
    std::map<std::string, std::string> values;
    while (std::getline(in, line)) {
        auto equals = line.find('=');
        if (equals == std::string::npos) continue;
        values[line.substr(0, equals)] = line.substr(equals + 1);
    }
    try {
    Project loaded;
    auto& s = loaded.settings;
    loaded.name = decoded(values, "project.name", loaded.name);
    s.model_id = "what-males-learn-at-each-age";
    s.model_revision = number(values, "project.model_revision", std::uint32_t{1});
    s.model_revision = 1;
    s.width = number(values, "project.width", s.width);
    s.height = number(values, "project.height", s.height);
    s.fps = number(values, "project.fps", s.fps);
    s.auto_length = number(values, "project.auto_length", int(s.auto_length)) != 0;
    s.custom_length_seconds = number(values, "project.custom_length_seconds", s.custom_length_seconds);
    s.credits_enabled = number(values, "project.credits_enabled", int(s.credits_enabled)) != 0;
#define GETS_FIELD(name) s.name = decoded(values, "project." #name, s.name)
    GETS_FIELD(credits_top_text); GETS_FIELD(credits_heading); GETS_FIELD(credits_project_name);
    GETS_FIELD(credits_created_with_label); GETS_FIELD(credits_created_with_value);
    GETS_FIELD(credits_design_label); GETS_FIELD(credits_design_value); GETS_FIELD(credits_footer);
    GETS_FIELD(end_best_label); GETS_FIELD(end_newest_label); GETS_FIELD(end_credit_label); GETS_FIELD(end_credit_value);
    GETS_FIELD(soundtrack); GETS_FIELD(encoder_preset); GETS_FIELD(font_title); GETS_FIELD(font_description);
    GETS_FIELD(font_badge); GETS_FIELD(font_credits); GETS_FIELD(image_fit_mode);
#undef GETS_FIELD
    s.soundtrack_volume = number(values, "project.soundtrack_volume", s.soundtrack_volume);
    s.soundtrack_loop = number(values, "project.soundtrack_loop", int(s.soundtrack_loop)) != 0;
    s.soundtrack_offset_seconds = number(values, "project.soundtrack_offset_seconds", s.soundtrack_offset_seconds);
    s.soundtrack_fade_out_seconds = number(values, "project.soundtrack_fade_out_seconds", s.soundtrack_fade_out_seconds);
    s.encoder_crf = number(values, "project.encoder_crf", s.encoder_crf);
    // Model-owned output values are immutable in 1.0.
    s.width = 1920;
    s.height = 1080;
    s.fps = 60;
    s.auto_length = true;
    s.encoder_crf = std::clamp(s.encoder_crf, std::uint32_t{0}, std::uint32_t{51});
    const std::array<std::string,9> presets{"ultrafast","superfast","veryfast","faster","fast","medium","slow","slower","veryslow"};
    if (std::find(presets.begin(), presets.end(), s.encoder_preset) == presets.end()) s.encoder_preset = "faster";
    s.soundtrack_volume = std::clamp(finite_number(s.soundtrack_volume, 0.75), 0.0, 1.0);
    s.soundtrack_offset_seconds = std::max(0.0, finite_number(s.soundtrack_offset_seconds, 0.0));
    s.soundtrack_fade_out_seconds = std::max(0.0, finite_number(s.soundtrack_fade_out_seconds, 0.75));
    s.custom_length_seconds = std::max(0.0, finite_number(s.custom_length_seconds, 60.0));
    s.image_fit_mode = s.image_fit_mode == "contain" ? "contain" : "cover";
    const auto count = number<std::size_t>(values, "cards.count", 0);
    if (count > 2000) throw std::runtime_error("Projects are limited to 2000 cards");
    loaded.cards.clear();
    loaded.cards.reserve(count);
    for (std::size_t i = 0; i < count; ++i) {
        const auto prefix = "card." + std::to_string(i) + ".";
        Card card{
            decoded(values, prefix + "title"),
            decoded(values, prefix + "value"),
            decoded(values, prefix + "description"),
            decoded(values, prefix + "image"),
            decoded(values, prefix + "id", new_card_id()),
        };
        card.image_x = std::clamp(finite_number(number(values, prefix + "image_x", 0.0), 0.0), -20000.0, 20000.0);
        card.image_y = std::clamp(finite_number(number(values, prefix + "image_y", 0.0), 0.0), -20000.0, 20000.0);
        card.image_scale = std::clamp(finite_number(number(values, prefix + "image_scale", 1.0), 1.0), 0.05, 8.0);
        card.image_rotation = std::fmod(finite_number(number(values, prefix + "image_rotation", 0.0), 0.0), 360.0);
        card.image_crop_left = std::clamp(number(values, prefix + "image_crop_left", 0.0), 0.0, 0.49);
        card.image_crop_top = std::clamp(number(values, prefix + "image_crop_top", 0.0), 0.0, 0.49);
        card.image_crop_right = std::clamp(number(values, prefix + "image_crop_right", 0.0), 0.0, 0.49);
        card.image_crop_bottom = std::clamp(number(values, prefix + "image_crop_bottom", 0.0), 0.0, 0.49);
        card.image_layer = decoded(values, prefix + "image_layer", "behind") == "front" ? "front" : "behind";
        if (!card.image.empty()) {
            std::filesystem::path image_path(card.image);
            if (!image_path.is_absolute() && card.image.rfind("http://",0)!=0 && card.image.rfind("https://",0)!=0)
                card.image = (path.parent_path() / image_path).lexically_normal().string();
        }
        loaded.cards.push_back(std::move(card));
    }
    if (loaded.cards.empty()) loaded.cards.push_back({"Card 1", "1", "", ""});
    auto resolve_setting = [&](std::string& value) {
        if (value.empty() || value.rfind("http://",0)==0 || value.rfind("https://",0)==0) return;
        std::filesystem::path asset(value);
        if (!asset.is_absolute()) value = (path.parent_path() / asset).lexically_normal().string();
    };
    resolve_setting(s.soundtrack);
    for (auto* font : {&s.font_title,&s.font_description,&s.font_badge,&s.font_credits})
        if (font->find('/') != std::string::npos || font->find('\\') != std::string::npos || std::filesystem::path(*font).has_extension()) resolve_setting(*font);
    loaded.project_path = path;
    project = std::move(loaded);
    return true;
    } catch (const std::exception& ex) {
        if (error) *error = ex.what();
        return false;
    }
}

} // namespace cubical
