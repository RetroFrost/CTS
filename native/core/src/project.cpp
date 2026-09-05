#include "cubical/project.hpp"

#include <algorithm>
#include <array>
#include <atomic>
#include <charconv>
#include <cctype>
#include <cstdlib>
#include <chrono>
#include <cmath>
#include <fstream>
#include <iomanip>
#include <map>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string_view>
#include <variant>

namespace cubical {

std::string new_card_id() {
    static std::atomic<unsigned long long> counter{0};
    const auto ticks = std::chrono::high_resolution_clock::now().time_since_epoch().count();
    std::ostringstream out;
    out << std::hex << ticks << counter.fetch_add(1, std::memory_order_relaxed);
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
void put(std::ostream& out, const std::string& key, const std::string& value) { out << key << '=' << value << '\n'; }
void puts(std::ostream& out, const std::string& key, const std::string& value) { put(out, key, base64_encode(value)); }

template<typename T>
T number(const std::map<std::string, std::string>& values, const std::string& key, T fallback) {
    auto it = values.find(key);
    if (it == values.end()) return fallback;
    std::istringstream in(it->second);
    T result{};
    return in >> result ? result : fallback;
}

std::string decoded(const std::map<std::string, std::string>& values, const std::string& key, const std::string& fallback = {}) {
    auto it = values.find(key);
    return it == values.end() ? fallback : base64_decode(it->second);
}

double finite_number(double value, double fallback) { return std::isfinite(value) ? value : fallback; }
void remove_carriage_return(std::string& line) { if (!line.empty() && line.back() == '\r') line.pop_back(); }

void normalize(Project& project) {
    if (project.name.empty()) project.name = "Untitled";
    if (project.cards.empty()) project.cards.push_back({"Card 1", "1", "", ""});
    if (project.cards.size() > 2000) project.cards.resize(2000);
    auto& s = project.settings;
    s.width = std::clamp<std::uint32_t>(s.width ? s.width : 1920, 64, 7680);
    s.height = std::clamp<std::uint32_t>(s.height ? s.height : 1080, 64, 4320);
    if (s.width & 1U) ++s.width;
    if (s.height & 1U) ++s.height;
    s.fps = std::clamp<std::uint32_t>(s.fps ? s.fps : 60, 1, 240);
    s.custom_length_seconds = std::clamp(finite_number(s.custom_length_seconds, 90.0), 0.1, 24.0 * 60.0 * 60.0);
    s.soundtrack_volume = std::clamp(finite_number(s.soundtrack_volume, 0.75), 0.0, 1.0);
    s.soundtrack_offset_seconds = std::max(0.0, finite_number(s.soundtrack_offset_seconds, 0.0));
    s.soundtrack_fade_out_seconds = std::max(0.0, finite_number(s.soundtrack_fade_out_seconds, 0.75));
    if (s.intro_mode != "renderer" && s.intro_mode != "custom" && s.intro_mode != "disabled") s.intro_mode = "renderer";
    if (s.encoder_preference != "auto" && s.encoder_preference != "h264" && s.encoder_preference != "h265") s.encoder_preference = "auto";
    s.encoder_crf = std::clamp<std::uint32_t>(s.encoder_crf, 0, 51);
    s.image_fit_mode = s.image_fit_mode == "contain" ? "contain" : "cover";
    for (auto& c : project.cards) {
        if (c.id.empty()) c.id = new_card_id();
        c.image_x = std::clamp(finite_number(c.image_x, 0.0), -20000.0, 20000.0);
        c.image_y = std::clamp(finite_number(c.image_y, 0.0), -20000.0, 20000.0);
        c.image_scale = std::clamp(finite_number(c.image_scale, 1.0), 0.05, 8.0);
        c.image_rotation = std::fmod(finite_number(c.image_rotation, 0.0), 360.0);
        c.image_crop_left = std::clamp(finite_number(c.image_crop_left, 0.0), 0.0, 0.49);
        c.image_crop_top = std::clamp(finite_number(c.image_crop_top, 0.0), 0.0, 0.49);
        c.image_crop_right = std::clamp(finite_number(c.image_crop_right, 0.0), 0.0, 0.49);
        c.image_crop_bottom = std::clamp(finite_number(c.image_crop_bottom, 0.0), 0.0, 0.49);
        c.image_layer = c.image_layer == "front" ? "front" : "behind";
    }
}

bool is_url(const std::string& value) {
    return value.rfind("http://", 0) == 0 || value.rfind("https://", 0) == 0 || value.rfind("content://", 0) == 0;
}
void resolve_relative(std::string& value, const std::filesystem::path& base) {
    if (value.empty() || is_url(value)) return;
    std::filesystem::path p(value);
    if (!p.is_absolute()) value = (base / p).lexically_normal().string();
}

struct JValue;
using JObject = std::map<std::string, JValue>;
using JArray = std::vector<JValue>;
struct JValue {
    using Data = std::variant<std::nullptr_t, bool, double, std::string, JArray, JObject>;
    Data data{nullptr};
    bool is_object() const { return std::holds_alternative<JObject>(data); }
    bool is_array() const { return std::holds_alternative<JArray>(data); }
    const JObject* object() const { return std::get_if<JObject>(&data); }
    const JArray* array() const { return std::get_if<JArray>(&data); }
};

class JsonParser {
public:
    explicit JsonParser(std::string_view text) : text_(text) {}
    JValue parse() {
        skip();
        JValue value = parse_value();
        skip();
        if (pos_ != text_.size()) fail("Trailing JSON data");
        return value;
    }
private:
    std::string_view text_;
    std::size_t pos_{};
    [[noreturn]] void fail(const char* what) const { throw std::runtime_error(std::string(what) + " at byte " + std::to_string(pos_)); }
    void skip() { while (pos_ < text_.size() && (text_[pos_] == ' ' || text_[pos_] == '\n' || text_[pos_] == '\r' || text_[pos_] == '\t')) ++pos_; }
    bool consume(char c) { skip(); if (pos_ < text_.size() && text_[pos_] == c) { ++pos_; return true; } return false; }
    JValue parse_value() {
        skip();
        if (pos_ >= text_.size()) fail("Unexpected end of JSON");
        const char c = text_[pos_];
        if (c == '{') return JValue{parse_object()};
        if (c == '[') return JValue{parse_array()};
        if (c == '"') return JValue{parse_string()};
        if (c == 't' && text_.substr(pos_, 4) == "true") { pos_ += 4; return JValue{true}; }
        if (c == 'f' && text_.substr(pos_, 5) == "false") { pos_ += 5; return JValue{false}; }
        if (c == 'n' && text_.substr(pos_, 4) == "null") { pos_ += 4; return JValue{nullptr}; }
        if (c == '-' || (c >= '0' && c <= '9')) return JValue{parse_number()};
        fail("Invalid JSON token");
    }
    JObject parse_object() {
        if (!consume('{')) fail("Expected object");
        JObject out;
        skip(); if (consume('}')) return out;
        for (;;) {
            skip(); if (pos_ >= text_.size() || text_[pos_] != '"') fail("Expected object key");
            std::string key = parse_string();
            if (!consume(':')) fail("Expected ':'");
            out[std::move(key)] = parse_value();
            if (consume('}')) return out;
            if (!consume(',')) fail("Expected ','");
        }
    }
    JArray parse_array() {
        if (!consume('[')) fail("Expected array");
        JArray out;
        skip(); if (consume(']')) return out;
        for (;;) {
            out.push_back(parse_value());
            if (consume(']')) return out;
            if (!consume(',')) fail("Expected ','");
        }
    }
    static void append_utf8(std::string& out, unsigned cp) {
        if (cp <= 0x7F) out.push_back(static_cast<char>(cp));
        else if (cp <= 0x7FF) { out.push_back(static_cast<char>(0xC0 | (cp >> 6))); out.push_back(static_cast<char>(0x80 | (cp & 0x3F))); }
        else if (cp <= 0xFFFF) { out.push_back(static_cast<char>(0xE0 | (cp >> 12))); out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F))); out.push_back(static_cast<char>(0x80 | (cp & 0x3F))); }
        else { out.push_back(static_cast<char>(0xF0 | (cp >> 18))); out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F))); out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F))); out.push_back(static_cast<char>(0x80 | (cp & 0x3F))); }
    }
    unsigned parse_hex4() {
        if (pos_ + 4 > text_.size()) fail("Truncated unicode escape");
        unsigned value = 0;
        for (int i = 0; i < 4; ++i) {
            const char c = text_[pos_++]; value <<= 4;
            if (c >= '0' && c <= '9') value |= c - '0';
            else if (c >= 'a' && c <= 'f') value |= c - 'a' + 10;
            else if (c >= 'A' && c <= 'F') value |= c - 'A' + 10;
            else fail("Invalid unicode escape");
        }
        return value;
    }
    std::string parse_string() {
        if (text_[pos_++] != '"') fail("Expected string");
        std::string out;
        while (pos_ < text_.size()) {
            char c = text_[pos_++];
            if (c == '"') return out;
            if (static_cast<unsigned char>(c) < 0x20) fail("Control character in string");
            if (c != '\\') { out.push_back(c); continue; }
            if (pos_ >= text_.size()) fail("Truncated escape");
            const char e = text_[pos_++];
            switch (e) {
                case '"': out.push_back('"'); break; case '\\': out.push_back('\\'); break; case '/': out.push_back('/'); break;
                case 'b': out.push_back('\b'); break; case 'f': out.push_back('\f'); break; case 'n': out.push_back('\n'); break; case 'r': out.push_back('\r'); break; case 't': out.push_back('\t'); break;
                case 'u': {
                    unsigned cp = parse_hex4();
                    if (cp >= 0xD800 && cp <= 0xDBFF) {
                        if (pos_ + 2 > text_.size() || text_[pos_] != '\\' || text_[pos_ + 1] != 'u') fail("Invalid surrogate pair");
                        pos_ += 2; unsigned lo = parse_hex4();
                        if (lo < 0xDC00 || lo > 0xDFFF) fail("Invalid surrogate pair");
                        cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                    }
                    append_utf8(out, cp); break;
                }
                default: fail("Invalid string escape");
            }
        }
        fail("Unterminated string");
    }
    double parse_number() {
        const std::size_t start = pos_;
        if (text_[pos_] == '-') ++pos_;
        if (pos_ >= text_.size()) fail("Bad number");
        if (text_[pos_] == '0') ++pos_; else { if (text_[pos_] < '1' || text_[pos_] > '9') fail("Bad number"); while (pos_ < text_.size() && std::isdigit(static_cast<unsigned char>(text_[pos_]))) ++pos_; }
        if (pos_ < text_.size() && text_[pos_] == '.') { ++pos_; if (pos_ >= text_.size() || !std::isdigit(static_cast<unsigned char>(text_[pos_]))) fail("Bad fraction"); while (pos_ < text_.size() && std::isdigit(static_cast<unsigned char>(text_[pos_]))) ++pos_; }
        if (pos_ < text_.size() && (text_[pos_] == 'e' || text_[pos_] == 'E')) { ++pos_; if (pos_ < text_.size() && (text_[pos_] == '+' || text_[pos_] == '-')) ++pos_; if (pos_ >= text_.size() || !std::isdigit(static_cast<unsigned char>(text_[pos_]))) fail("Bad exponent"); while (pos_ < text_.size() && std::isdigit(static_cast<unsigned char>(text_[pos_]))) ++pos_; }
        const std::string temp(text_.substr(start, pos_ - start));
        char* end = nullptr; double value = std::strtod(temp.c_str(), &end);
        if (!end || *end || !std::isfinite(value)) fail("Invalid number");
        return value;
    }
};

const JValue* member(const JObject* obj, const char* key) {
    if (!obj) return nullptr; auto it = obj->find(key); return it == obj->end() ? nullptr : &it->second;
}
std::string jstring(const JObject* obj, const char* key, const std::string& fallback = {}) {
    if (const auto* v = member(obj, key)) if (const auto* s = std::get_if<std::string>(&v->data)) return *s; return fallback;
}
double jnumber(const JObject* obj, const char* key, double fallback) {
    if (const auto* v = member(obj, key)) if (const auto* n = std::get_if<double>(&v->data)) return *n; return fallback;
}
bool jbool(const JObject* obj, const char* key, bool fallback) {
    if (const auto* v = member(obj, key)) if (const auto* b = std::get_if<bool>(&v->data)) return *b; return fallback;
}
std::string json_escape(std::string_view text) {
    std::ostringstream out;
    for (unsigned char c : text) {
        switch (c) {
            case '"': out << "\\\""; break; case '\\': out << "\\\\"; break; case '\b': out << "\\b"; break; case '\f': out << "\\f"; break; case '\n': out << "\\n"; break; case '\r': out << "\\r"; break; case '\t': out << "\\t"; break;
            default: if (c < 0x20) out << "\\u" << std::hex << std::setw(4) << std::setfill('0') << int(c) << std::dec; else out << static_cast<char>(c);
        }
    }
    return out.str();
}
void q(std::ostream& out, std::string_view value) { out << '"' << json_escape(value) << '"'; }

} // namespace

double timeline_duration(const Project& project) {
    if (project.cards.empty()) return 0.0;
    constexpr std::uint32_t opening_ends[] = {120, 240, 360, 528};
    constexpr std::uint32_t outro_frames = 409;
    const auto count = project.cards.size();
    const std::uint32_t content_frames = count <= 4 ? opening_ends[count - 1] : static_cast<std::uint32_t>(528 + (count - 4) * 214);
    const double minimum = static_cast<double>(content_frames + outro_frames) / std::max<std::uint32_t>(1, project.settings.fps);
    return project.settings.auto_length ? minimum : std::max(project.settings.custom_length_seconds, minimum);
}

std::string summary(const Project& project) {
    std::ostringstream out;
    out << (project.name.empty() ? "Untitled" : project.name) << " • " << project.cards.size() << " cards • " << project.settings.width << 'x' << project.settings.height << " @ " << project.settings.fps << " FPS";
    return out.str();
}

std::string base64_encode(const std::string& input) {
    std::string out; int val = 0, bits = -6;
    for (unsigned char c : input) { val = (val << 8) + c; bits += 8; while (bits >= 0) { out.push_back(kBase64[(val >> bits) & 0x3F]); bits -= 6; } }
    if (bits > -6) out.push_back(kBase64[((val << 8) >> (bits + 8)) & 0x3F]);
    return out;
}

std::string base64_decode(const std::string& input) {
    std::array<int, 256> table{}; table.fill(-1); for (int i = 0; i < 64; ++i) table[static_cast<unsigned char>(kBase64[i])] = i;
    std::size_t encoded_size = input.size(); while (encoded_size > 0 && input[encoded_size - 1] == '=') --encoded_size;
    const std::size_t padding_size = input.size() - encoded_size;
    if (padding_size > 2 || input.find('=') < encoded_size) throw std::runtime_error("Invalid base64 data in project file");
    std::string out; std::uint32_t val = 0; int bits = -8;
    for (std::size_t index = 0; index < encoded_size; ++index) { const int mapped = table[static_cast<unsigned char>(input[index])]; if (mapped < 0) throw std::runtime_error("Invalid base64 data in project file"); val = (val << 6) + mapped; bits += 6; if (bits >= 0) { out.push_back(static_cast<char>((val >> bits) & 0xFF)); bits -= 8; } }
    return out;
}

bool save_ccx(const Project& source, const std::filesystem::path& path, std::string* error) {
    Project project = source; normalize(project);
    std::ofstream out(path, std::ios::binary);
    if (!out) { if (error) *error = "Could not write " + path.string(); return false; }
    const auto& s = project.settings;
    out << "CCX1\n";
    puts(out, "project.name", project.name); puts(out, "project.model_id", s.model_id); put(out, "project.model_revision", std::to_string(s.model_revision));
    put(out, "project.width", std::to_string(s.width)); put(out, "project.height", std::to_string(s.height)); put(out, "project.fps", std::to_string(s.fps));
    put(out, "project.auto_length", bool_text(s.auto_length)); put(out, "project.custom_length_seconds", std::to_string(s.custom_length_seconds));
    put(out, "project.credits_enabled", bool_text(s.credits_enabled)); put(out, "project.show_badges", bool_text(s.show_badges));
#define PUTS(name) puts(out, "project." #name, s.name)
    PUTS(credits_top_text); PUTS(credits_heading); PUTS(credits_project_name); PUTS(credits_created_with_label); PUTS(credits_created_with_value); PUTS(credits_design_label); PUTS(credits_design_value); PUTS(credits_footer);
    PUTS(end_best_label); PUTS(end_newest_label); PUTS(end_credit_label); PUTS(end_credit_value); PUTS(intro_mode); PUTS(intro_video); PUTS(soundtrack); PUTS(encoder_preference); PUTS(encoder_preset); PUTS(font_family); PUTS(font_file); PUTS(font_title); PUTS(font_description); PUTS(font_badge); PUTS(font_credits); PUTS(image_fit_mode);
#undef PUTS
    put(out, "project.soundtrack_volume", std::to_string(s.soundtrack_volume)); put(out, "project.soundtrack_loop", bool_text(s.soundtrack_loop)); put(out, "project.soundtrack_offset_seconds", std::to_string(s.soundtrack_offset_seconds)); put(out, "project.soundtrack_fade_out_seconds", std::to_string(s.soundtrack_fade_out_seconds)); put(out, "project.encoder_crf", std::to_string(s.encoder_crf));
    put(out, "cards.count", std::to_string(project.cards.size()));
    for (std::size_t i = 0; i < project.cards.size(); ++i) {
        const auto& c = project.cards[i]; const auto p = "card." + std::to_string(i) + ".";
        puts(out, p + "title", c.title); puts(out, p + "value", c.value); puts(out, p + "badge_header", c.badge_header); puts(out, p + "description", c.description); puts(out, p + "image", c.image); puts(out, p + "id", c.id);
        put(out, p + "image_x", std::to_string(c.image_x)); put(out, p + "image_y", std::to_string(c.image_y)); put(out, p + "image_scale", std::to_string(c.image_scale)); put(out, p + "image_rotation", std::to_string(c.image_rotation)); put(out, p + "image_crop_left", std::to_string(c.image_crop_left)); put(out, p + "image_crop_top", std::to_string(c.image_crop_top)); put(out, p + "image_crop_right", std::to_string(c.image_crop_right)); put(out, p + "image_crop_bottom", std::to_string(c.image_crop_bottom)); puts(out, p + "image_layer", c.image_layer);
    }
    return true;
}

bool load_ccx(Project& project, const std::filesystem::path& path, std::string* error) {
    try {
        std::ifstream in(path, std::ios::binary); if (!in) throw std::runtime_error("Could not open " + path.string());
        std::string line; if (!std::getline(in, line)) throw std::runtime_error("Unsupported project interchange file"); remove_carriage_return(line); if (line != "CCX1") throw std::runtime_error("Unsupported project interchange file");
        std::map<std::string, std::string> values; while (std::getline(in, line)) { remove_carriage_return(line); const auto eq = line.find('='); if (eq != std::string::npos) values[line.substr(0, eq)] = line.substr(eq + 1); }
        Project loaded; auto& s = loaded.settings;
        loaded.name = decoded(values, "project.name", "Untitled"); s.model_id = decoded(values, "project.model_id", s.model_id); s.model_revision = number(values, "project.model_revision", s.model_revision);
        s.width = number(values, "project.width", s.width); s.height = number(values, "project.height", s.height); s.fps = number(values, "project.fps", s.fps); s.auto_length = number(values, "project.auto_length", int(s.auto_length)) != 0; s.custom_length_seconds = number(values, "project.custom_length_seconds", s.custom_length_seconds); s.credits_enabled = number(values, "project.credits_enabled", int(s.credits_enabled)) != 0; s.show_badges = number(values, "project.show_badges", int(s.show_badges)) != 0;
#define GETS(name) s.name = decoded(values, "project." #name, s.name)
        GETS(credits_top_text); GETS(credits_heading); GETS(credits_project_name); GETS(credits_created_with_label); GETS(credits_created_with_value); GETS(credits_design_label); GETS(credits_design_value); GETS(credits_footer); GETS(end_best_label); GETS(end_newest_label); GETS(end_credit_label); GETS(end_credit_value); GETS(intro_mode); GETS(intro_video); GETS(soundtrack); GETS(encoder_preference); GETS(encoder_preset); GETS(font_family); GETS(font_file); GETS(font_title); GETS(font_description); GETS(font_badge); GETS(font_credits); GETS(image_fit_mode);
#undef GETS
        s.soundtrack_volume = number(values, "project.soundtrack_volume", s.soundtrack_volume); s.soundtrack_loop = number(values, "project.soundtrack_loop", int(s.soundtrack_loop)) != 0; s.soundtrack_offset_seconds = number(values, "project.soundtrack_offset_seconds", s.soundtrack_offset_seconds); s.soundtrack_fade_out_seconds = number(values, "project.soundtrack_fade_out_seconds", s.soundtrack_fade_out_seconds); s.encoder_crf = number(values, "project.encoder_crf", s.encoder_crf);
        const auto count = number<std::size_t>(values, "cards.count", 0); if (count > 2000) throw std::runtime_error("Projects are limited to 2000 cards"); loaded.cards.clear(); loaded.cards.reserve(count);
        for (std::size_t i = 0; i < count; ++i) { const auto p = "card." + std::to_string(i) + "."; Card c; c.title = decoded(values,p+"title"); c.value=decoded(values,p+"value"); c.badge_header=decoded(values,p+"badge_header"); c.description=decoded(values,p+"description"); c.image=decoded(values,p+"image"); c.id=decoded(values,p+"id",new_card_id()); c.image_x=number(values,p+"image_x",0.0); c.image_y=number(values,p+"image_y",0.0); c.image_scale=number(values,p+"image_scale",1.0); c.image_rotation=number(values,p+"image_rotation",0.0); c.image_crop_left=number(values,p+"image_crop_left",0.0); c.image_crop_top=number(values,p+"image_crop_top",0.0); c.image_crop_right=number(values,p+"image_crop_right",0.0); c.image_crop_bottom=number(values,p+"image_crop_bottom",0.0); c.image_layer=decoded(values,p+"image_layer","behind"); loaded.cards.push_back(std::move(c)); }
        normalize(loaded); const auto base = path.parent_path(); for (auto& c : loaded.cards) resolve_relative(c.image, base); resolve_relative(s.intro_video, base); resolve_relative(s.soundtrack, base); resolve_relative(s.font_file, base); loaded.project_path = path; project = std::move(loaded); return true;
    } catch (const std::exception& ex) { if (error) *error = ex.what(); return false; }
}

bool save_project_json(const Project& source, const std::filesystem::path& path, std::string* error) {
    try {
        Project p = source; normalize(p); std::ofstream out(path, std::ios::binary); if (!out) throw std::runtime_error("Could not write " + path.string()); const auto& s = p.settings;
        out << "{\n  \"version\": 6,\n  \"name\": "; q(out,p.name); out << ",\n  \"cards\": [\n";
        for (std::size_t i=0;i<p.cards.size();++i) { const auto& c=p.cards[i]; out << "    {\"id\":";q(out,c.id);out<<",\"title\":";q(out,c.title);out<<",\"value\":";q(out,c.value);out<<",\"badge_header\":";q(out,c.badge_header);out<<",\"description\":";q(out,c.description);out<<",\"image\":";q(out,c.image);out<<",\"image_x\":"<<c.image_x<<",\"image_y\":"<<c.image_y<<",\"image_scale\":"<<c.image_scale<<",\"image_rotation\":"<<c.image_rotation<<",\"image_crop_left\":"<<c.image_crop_left<<",\"image_crop_top\":"<<c.image_crop_top<<",\"image_crop_right\":"<<c.image_crop_right<<",\"image_crop_bottom\":"<<c.image_crop_bottom<<",\"image_layer\":";q(out,c.image_layer);out<<"}"<<(i+1<p.cards.size()?",":"")<<"\n"; }
        out << "  ],\n  \"settings\": {\n";
        out << "    \"width\": "<<s.width<<", \"height\": "<<s.height<<", \"fps\": "<<s.fps<<",\n    \"auto_length\": "<<(s.auto_length?"true":"false")<<", \"custom_length_seconds\": "<<s.custom_length_seconds<<",\n    \"show_badges\": "<<(s.show_badges?"true":"false")<<", \"credits_enabled\": "<<(s.credits_enabled?"true":"false")<<",\n    \"intro_mode\": ";q(out,s.intro_mode);out<<", \"intro_video\": ";q(out,s.intro_video);out<<",\n    \"soundtrack\": ";q(out,s.soundtrack);out<<", \"soundtrack_volume\": "<<s.soundtrack_volume<<", \"soundtrack_loop\": "<<(s.soundtrack_loop?"true":"false")<<",\n    \"encoder_preference\": ";q(out,s.encoder_preference);out<<", \"font_family\": ";q(out,s.font_family);out<<", \"font_file\": ";q(out,s.font_file);out<<",\n    \"encoder_preset\": ";q(out,s.encoder_preset);out<<", \"encoder_crf\": "<<s.encoder_crf<<"\n  }\n}\n"; return true;
    } catch (const std::exception& ex) { if (error) *error=ex.what(); return false; }
}

bool load_project_json(Project& project, const std::filesystem::path& path, std::string* error) {
    try {
        std::ifstream in(path,std::ios::binary); if(!in) throw std::runtime_error("Could not open " + path.string()); std::string text((std::istreambuf_iterator<char>(in)),{}); JValue root=JsonParser(text).parse(); const JObject* obj=root.object(); if(!obj) throw std::runtime_error("Project root must be a JSON object");
        Project p; p.name=jstring(obj,"name","Untitled"); if(const auto* cardsValue=member(obj,"cards")) if(const auto* arr=cardsValue->array()) { p.cards.clear(); if(arr->size()>2000) throw std::runtime_error("Projects are limited to 2000 cards"); for(const auto& item:*arr) { const auto* o=item.object(); if(!o) continue; Card c; c.id=jstring(o,"id",new_card_id()); c.title=jstring(o,"title"); c.value=jstring(o,"value"); c.badge_header=jstring(o,"badge_header",jstring(o,"badgeHeader")); c.description=jstring(o,"description"); c.image=jstring(o,"image"); c.image_x=jnumber(o,"image_x",0); c.image_y=jnumber(o,"image_y",0); c.image_scale=jnumber(o,"image_scale",1); c.image_rotation=jnumber(o,"image_rotation",0); c.image_crop_left=jnumber(o,"image_crop_left",0); c.image_crop_top=jnumber(o,"image_crop_top",0); c.image_crop_right=jnumber(o,"image_crop_right",0); c.image_crop_bottom=jnumber(o,"image_crop_bottom",0); c.image_layer=jstring(o,"image_layer","behind"); p.cards.push_back(std::move(c)); } }
        const JObject* s=nullptr; if(const auto* v=member(obj,"settings")) s=v->object(); if(s) { p.settings.width=static_cast<std::uint32_t>(jnumber(s,"width",1920)); p.settings.height=static_cast<std::uint32_t>(jnumber(s,"height",1080)); p.settings.fps=static_cast<std::uint32_t>(jnumber(s,"fps",60)); p.settings.auto_length=jbool(s,"auto_length",true); p.settings.custom_length_seconds=jnumber(s,"custom_length_seconds",90); p.settings.show_badges=jbool(s,"show_badges",true); p.settings.credits_enabled=jbool(s,"credits_enabled",true); p.settings.intro_mode=jstring(s,"intro_mode","renderer"); p.settings.intro_video=jstring(s,"intro_video"); p.settings.soundtrack=jstring(s,"soundtrack"); p.settings.soundtrack_volume=jnumber(s,"soundtrack_volume",.75); p.settings.soundtrack_loop=jbool(s,"soundtrack_loop",true); p.settings.encoder_preference=jstring(s,"encoder_preference","auto"); p.settings.font_family=jstring(s,"font_family"); p.settings.font_file=jstring(s,"font_file"); p.settings.encoder_preset=jstring(s,"encoder_preset","faster"); p.settings.encoder_crf=static_cast<std::uint32_t>(jnumber(s,"encoder_crf",18)); }
        normalize(p); const auto base=path.parent_path(); for(auto& c:p.cards)resolve_relative(c.image,base);resolve_relative(p.settings.intro_video,base);resolve_relative(p.settings.soundtrack,base);resolve_relative(p.settings.font_file,base);p.project_path=path;project=std::move(p);return true;
    } catch(const std::exception& ex){if(error)*error=ex.what();return false;}
}

bool load_project_auto(Project& project, const std::filesystem::path& path, std::string* error) {
    std::ifstream in(path,std::ios::binary); if(!in){if(error)*error="Could not open " + path.string();return false;} char c=0; while(in.get(c)){if(!std::isspace(static_cast<unsigned char>(c)))break;} in.close(); return c=='{' ? load_project_json(project,path,error) : load_ccx(project,path,error);
}

} // namespace cubical
