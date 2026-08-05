#include <gtk/gtk.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <iomanip>
#include <sstream>
#include <filesystem>
#include <fstream>
#include <memory>
#include <iostream>
#include <string>
#include <thread>
#include "cubical/process.hpp"
#include "cubical/project.hpp"

namespace fs = std::filesystem;

namespace {
struct AppState {
    cubical::Project project;
    int selected{0};
    std::string selected_card_id;
    bool loading{false};
    bool busy{false};
    bool task_is_export{false};
    GtkWidget* window{};
    GtkWidget* status{};
    GtkWidget* card_list{};
    GtkWidget* preview{};
    GtkWidget* play_button{};
    GtkWidget* seek{};
    GtkWidget* time_label{};
    GtkWidget* sheet_rows{};
    GtkWidget* sheet_columns{};
    GtkWidget* sheet_start{};
    GtkWidget* title{};
    GtkWidget* value{};
    GtkWidget* description{};
    GtkWidget* image{};
    GtkWidget* project_name{};
    GtkWidget* credits_enabled{};
    std::vector<GtkWidget*> text_fields;
    GtkWidget* soundtrack{};
    GtkWidget* loop{};
    GtkWidget* volume{};
    GtkWidget* offset{};
    GtkWidget* fade{};
    GtkWidget* task_window{};
    GtkWidget* task_label{};
    GtkWidget* task_progress{};
    GtkWidget* task_cancel{};
    guint task_timer{};
    fs::path task_progress_path;
    fs::path task_cancel_path;
    GtkWidget* width{};
    GtkWidget* height{};
    GtkWidget* fps{};
    GtkWidget* preset{};
    GtkWidget* crf{};
    std::vector<GtkWidget*> font_fields;
    fs::path working_ccx;
    fs::path preview_path;
    bool playing{false};
    bool preview_rendering{false};
    bool preview_pending{false};
    bool updating_seek{false};
    double current_time{0.0};
    double duration{0.0};
    double pending_time{0.0};
    double play_anchor_time{0.0};
    std::chrono::steady_clock::time_point play_anchor{};
};

enum class TaskKind { ExportVideo, ImportSheet };

struct TaskResult {
    AppState* state{};
    TaskKind kind{TaskKind::ExportVideo};
    cubical::ProcessResult process;
    fs::path output_ccx;
    fs::path assets;
    std::string target_path;
};

struct PreviewResult {
    AppState* state{};
    fs::path image_path;
    fs::path project_snapshot;
    double time{0.0};
    std::string error;
};

enum class PickAction { OpenProject, SaveProject, ImportData, ImportSheet, CardImage, Soundtrack, FontTitle, FontDescription, FontBadge, FontCredits, ExportVideo };
struct PickContext { AppState* state; PickAction action; };
struct FontDialogContext { AppState* state; int index; };

const char* entry_text(GtkWidget* widget) { return gtk_editable_get_text(GTK_EDITABLE(widget)); }
void set_entry(GtkWidget* widget, const std::string& value) { gtk_editable_set_text(GTK_EDITABLE(widget), value.c_str()); }
void set_status(AppState* s, const std::string& text) { gtk_label_set_text(GTK_LABEL(s->status), text.c_str()); }

bool valid_mp4_file(const fs::path& path) {
    std::error_code ec;
    if (!fs::is_regular_file(path, ec) || fs::file_size(path, ec) < 256) return false;
    std::ifstream in(path, std::ios::binary);
    std::string header(64, '\0');
    in.read(header.data(), static_cast<std::streamsize>(header.size()));
    header.resize(static_cast<std::size_t>(std::max<std::streamsize>(0, in.gcount())));
    return header.find("ftyp") != std::string::npos;
}

int selected_index(AppState* s) {
    const int by_id = cubical::find_card_index_by_id(s->project, s->selected_card_id);
    if (by_id >= 0) { s->selected = by_id; return by_id; }
    if (s->selected >= 0 && s->selected < static_cast<int>(s->project.cards.size())) {
        s->selected_card_id = s->project.cards[static_cast<std::size_t>(s->selected)].id;
        return s->selected;
    }
    return -1;
}

void select_index(AppState* s, int index) {
    if (s->project.cards.empty()) { s->selected = -1; s->selected_card_id.clear(); return; }
    s->selected = std::clamp(index, 0, static_cast<int>(s->project.cards.size()) - 1);
    s->selected_card_id = s->project.cards[static_cast<std::size_t>(s->selected)].id;
}

void sync_project_from_ui(AppState* s) {
    if (s->loading) return;
    const int index = selected_index(s);
    if (index >= 0) {
        auto& card = s->project.cards[static_cast<std::size_t>(index)];
        card.title = entry_text(s->title);
        card.value = entry_text(s->value);
        card.description = entry_text(s->description);
        card.image = entry_text(s->image);
    }
    s->project.name = entry_text(s->project_name);
    auto& st = s->project.settings;
    st.credits_enabled = gtk_check_button_get_active(GTK_CHECK_BUTTON(s->credits_enabled));
    const std::vector<std::string*> text_targets = {
        &st.credits_top_text, &st.credits_heading, &st.credits_project_name,
        &st.credits_created_with_label, &st.credits_created_with_value,
        &st.credits_design_label, &st.credits_design_value, &st.credits_footer,
        &st.end_best_label, &st.end_newest_label, &st.end_credit_label, &st.end_credit_value,
    };
    for (std::size_t i = 0; i < text_targets.size(); ++i) *text_targets[i] = entry_text(s->text_fields[i]);
    st.soundtrack = entry_text(s->soundtrack);
    st.soundtrack_loop = gtk_check_button_get_active(GTK_CHECK_BUTTON(s->loop));
    st.soundtrack_volume = gtk_spin_button_get_value(GTK_SPIN_BUTTON(s->volume)) / 100.0;
    st.soundtrack_offset_seconds = gtk_spin_button_get_value(GTK_SPIN_BUTTON(s->offset));
    st.soundtrack_fade_out_seconds = gtk_spin_button_get_value(GTK_SPIN_BUTTON(s->fade));
    st.width = static_cast<std::uint32_t>(gtk_spin_button_get_value_as_int(GTK_SPIN_BUTTON(s->width)));
    st.height = static_cast<std::uint32_t>(gtk_spin_button_get_value_as_int(GTK_SPIN_BUTTON(s->height)));
    st.fps = static_cast<std::uint32_t>(gtk_spin_button_get_value_as_int(GTK_SPIN_BUTTON(s->fps)));
    st.encoder_preset = entry_text(s->preset);
    st.encoder_crf = static_cast<std::uint32_t>(gtk_spin_button_get_value_as_int(GTK_SPIN_BUTTON(s->crf)));
    st.font_title = entry_text(s->font_fields[0]);
    st.font_description = entry_text(s->font_fields[1]);
    st.font_badge = entry_text(s->font_fields[2]);
    st.font_credits = entry_text(s->font_fields[3]);
}

void rebuild_card_list(AppState* s) {
    const bool old_loading = s->loading;
    s->loading = true;
    GtkWidget* child = gtk_widget_get_first_child(s->card_list);
    while (child) {
        GtkWidget* next = gtk_widget_get_next_sibling(child);
        gtk_list_box_remove(GTK_LIST_BOX(s->card_list), child);
        child = next;
    }
    for (std::size_t i = 0; i < s->project.cards.size(); ++i) {
        const auto& card = s->project.cards[i];
        const std::string label = std::to_string(i + 1) + ". " + (card.title.empty() ? "Untitled card" : card.title);
        GtkWidget* row = gtk_list_box_row_new();
        g_object_set_data_full(G_OBJECT(row), "cubical-card-id", g_strdup(card.id.c_str()), g_free);
        GtkWidget* text = gtk_label_new(label.c_str());
        gtk_label_set_xalign(GTK_LABEL(text), 0.0f);
        gtk_widget_set_margin_top(text, 7); gtk_widget_set_margin_bottom(text, 7);
        gtk_widget_set_margin_start(text, 8); gtk_widget_set_margin_end(text, 8);
        gtk_list_box_row_set_child(GTK_LIST_BOX_ROW(row), text);
        gtk_list_box_append(GTK_LIST_BOX(s->card_list), row);
    }
    if (!s->project.cards.empty()) {
        int index = cubical::find_card_index_by_id(s->project, s->selected_card_id);
        if (index < 0) index = std::clamp(s->selected, 0, static_cast<int>(s->project.cards.size()) - 1);
        select_index(s, index);
        if (auto* row = gtk_list_box_get_row_at_index(GTK_LIST_BOX(s->card_list), s->selected))
            gtk_list_box_select_row(GTK_LIST_BOX(s->card_list), row);
    }
    s->loading = old_loading;
}

void load_selected_card(AppState* s) {
    s->loading = true;
    const int index = selected_index(s);
    if (index >= 0) {
        const auto& card = s->project.cards[static_cast<std::size_t>(index)];
        set_entry(s->title, card.title); set_entry(s->value, card.value);
        set_entry(s->description, card.description); set_entry(s->image, card.image);
    }
    s->loading = false;
}

void load_project_ui(AppState* s) {
    s->loading = true;
    set_entry(s->project_name, s->project.name);
    auto& st = s->project.settings;
    gtk_check_button_set_active(GTK_CHECK_BUTTON(s->credits_enabled), st.credits_enabled);
    const std::vector<const std::string*> values = {
        &st.credits_top_text, &st.credits_heading, &st.credits_project_name,
        &st.credits_created_with_label, &st.credits_created_with_value,
        &st.credits_design_label, &st.credits_design_value, &st.credits_footer,
        &st.end_best_label, &st.end_newest_label, &st.end_credit_label, &st.end_credit_value,
    };
    for (std::size_t i = 0; i < values.size(); ++i) set_entry(s->text_fields[i], *values[i]);
    set_entry(s->soundtrack, st.soundtrack);
    gtk_check_button_set_active(GTK_CHECK_BUTTON(s->loop), st.soundtrack_loop);
    gtk_spin_button_set_value(GTK_SPIN_BUTTON(s->volume), st.soundtrack_volume * 100.0);
    gtk_spin_button_set_value(GTK_SPIN_BUTTON(s->offset), st.soundtrack_offset_seconds);
    gtk_spin_button_set_value(GTK_SPIN_BUTTON(s->fade), st.soundtrack_fade_out_seconds);
    gtk_spin_button_set_value(GTK_SPIN_BUTTON(s->width), st.width);
    gtk_spin_button_set_value(GTK_SPIN_BUTTON(s->height), st.height);
    gtk_spin_button_set_value(GTK_SPIN_BUTTON(s->fps), st.fps);
    set_entry(s->preset, st.encoder_preset);
    gtk_spin_button_set_value(GTK_SPIN_BUTTON(s->crf), st.encoder_crf);
    set_entry(s->font_fields[0], st.font_title); set_entry(s->font_fields[1], st.font_description);
    set_entry(s->font_fields[2], st.font_badge); set_entry(s->font_fields[3], st.font_credits);
    s->loading = false;
    rebuild_card_list(s);
    load_selected_card(s);
    set_status(s, cubical::summary(s->project));
}

bool write_working(AppState* s) {
    sync_project_from_ui(s);
    std::string error;
    if (!cubical::save_ccx(s->project, s->working_ccx, &error)) { set_status(s, error); return false; }
    return true;
}

void fields_changed(GtkEditable*, gpointer data) {
    auto* s = static_cast<AppState*>(data);
    if (s->loading) return;
    sync_project_from_ui(s);
    const int index = selected_index(s);
    if (index >= 0) {
        if (auto* row = gtk_list_box_get_selected_row(GTK_LIST_BOX(s->card_list))) {
            if (auto* label = gtk_list_box_row_get_child(row)) {
                const auto& card = s->project.cards[static_cast<std::size_t>(index)];
                const std::string row_text = std::to_string(index + 1) + ". " + (card.title.empty() ? "Untitled card" : card.title);
                gtk_label_set_text(GTK_LABEL(label), row_text.c_str());
            }
        }
    }
    set_status(s, cubical::summary(s->project));
}

void card_selected(GtkListBox*, GtkListBoxRow* row, gpointer data) {
    auto* s = static_cast<AppState*>(data);
    if (!row || s->loading) return;
    sync_project_from_ui(s);
    const char* id = static_cast<const char*>(g_object_get_data(G_OBJECT(row), "cubical-card-id"));
    if (id) s->selected_card_id = id;
    const int index = cubical::find_card_index_by_id(s->project, s->selected_card_id);
    if (index >= 0) s->selected = index;
    load_selected_card(s);
}

GtkWidget* form_entry(GtkWidget* grid, int row, const char* label, AppState* s, bool multiline = false) {
    GtkWidget* l = gtk_label_new(label); gtk_label_set_xalign(GTK_LABEL(l), 0.0f);
    gtk_grid_attach(GTK_GRID(grid), l, 0, row, 1, 1);
    GtkWidget* entry = gtk_entry_new();
    gtk_widget_set_hexpand(entry, TRUE);
    gtk_grid_attach(GTK_GRID(grid), entry, 1, row, 1, 1);
    g_signal_connect(entry, "changed", G_CALLBACK(fields_changed), s);
    return entry;
}

void reset_player(AppState* s);

GtkWidget* spin(GtkWidget* grid, int row, const char* label, double min, double max, double step) {
    GtkWidget* l = gtk_label_new(label); gtk_label_set_xalign(GTK_LABEL(l), 0.0f);
    gtk_grid_attach(GTK_GRID(grid), l, 0, row, 1, 1);
    GtkWidget* w = gtk_spin_button_new_with_range(min, max, step);
    gtk_widget_set_hexpand(w, TRUE); gtk_grid_attach(GTK_GRID(grid), w, 1, row, 1, 1);
    return w;
}

void add_card(GtkButton*, gpointer data) {
    auto* s = static_cast<AppState*>(data); sync_project_from_ui(s);
    s->project.cards.push_back({"New card", "", "", ""}); select_index(s, static_cast<int>(s->project.cards.size()) - 1);
    rebuild_card_list(s); load_selected_card(s); reset_player(s);
}
void remove_card(GtkButton*, gpointer data) {
    auto* s = static_cast<AppState*>(data);
    if (s->busy || s->project.cards.size() <= 1) return;
    sync_project_from_ui(s);

    // Resolve the card from the actual selected list row at click time. Titles are not
    // identities, so an empty/Untitled card can never fall back to card 1.
    auto* row = gtk_list_box_get_selected_row(GTK_LIST_BOX(s->card_list));
    const char* row_id = row
        ? static_cast<const char*>(g_object_get_data(G_OBJECT(row), "cubical-card-id"))
        : nullptr;
    const std::string remove_id = row_id ? row_id : s->selected_card_id;
    const int next = cubical::erase_card_by_id(s->project, remove_id);
    if (next < 0) { set_status(s, "The selected card could not be resolved."); return; }
    select_index(s, next);
    rebuild_card_list(s); load_selected_card(s); reset_player(s);
}
void trim_cards(GtkButton*, gpointer data) {
    auto* s = static_cast<AppState*>(data); sync_project_from_ui(s);
    int keep = selected_index(s) + 1;
    if (keep > 0 && keep < static_cast<int>(s->project.cards.size())) {
        s->project.cards.resize(static_cast<std::size_t>(keep)); rebuild_card_list(s); load_selected_card(s);
        reset_player(s);
        set_status(s, "Trimmed project to " + std::to_string(keep) + " cards");
    }
}

std::string format_time(double seconds) {
    seconds = std::max(0.0, seconds);
    const int total = static_cast<int>(seconds);
    const int minutes = total / 60;
    const int secs = total % 60;
    const int tenths = static_cast<int>((seconds - total) * 10.0 + 0.5) % 10;
    std::ostringstream out;
    out << std::setfill('0') << std::setw(2) << minutes << ':' << std::setw(2) << secs << '.' << tenths;
    return out.str();
}

void update_player_ui(AppState* s) {
    s->current_time = std::clamp(s->current_time, 0.0, std::max(0.0, s->duration));
    s->updating_seek = true;
    gtk_range_set_range(GTK_RANGE(s->seek), 0.0, std::max(0.01, s->duration));
    gtk_range_set_value(GTK_RANGE(s->seek), s->current_time);
    s->updating_seek = false;
    const std::string label = format_time(s->current_time) + " / " + format_time(s->duration);
    gtk_label_set_text(GTK_LABEL(s->time_label), label.c_str());
    gtk_button_set_label(GTK_BUTTON(s->play_button), s->playing ? "Pause" : "Play");
}

void start_preview_render(AppState* s, double requested_time);

static gboolean preview_ready(gpointer data) {
    std::unique_ptr<PreviewResult> payload(static_cast<PreviewResult*>(data));
    AppState* s = payload->state;
    s->preview_rendering = false;

    if (payload->error.empty() && fs::exists(payload->image_path)) {
        GError* error = nullptr;
        GdkTexture* texture = gdk_texture_new_from_filename(payload->image_path.string().c_str(), &error);
        if (texture) {
            gtk_picture_set_paintable(GTK_PICTURE(s->preview), GDK_PAINTABLE(texture));
            g_object_unref(texture);
            std::error_code ignored;
            if (!s->preview_path.empty()) fs::remove(s->preview_path, ignored);
            s->preview_path = payload->image_path;
        } else {
            payload->error = error ? error->message : "GTK could not decode the rendered preview.";
            if (error) g_error_free(error);
        }
    }

    std::error_code ignored;
    fs::remove(payload->project_snapshot, ignored);
    if (!payload->error.empty()) {
        fs::remove(payload->image_path, ignored);
        set_status(s, payload->error);
    } else if (!s->playing) {
        set_status(s, "Preview frame matches exported output at " + format_time(payload->time));
    }

    if (s->preview_pending) {
        const double next = s->pending_time;
        s->preview_pending = false;
        start_preview_render(s, next);
    }
    return G_SOURCE_REMOVE;
}

void start_preview_render(AppState* s, double requested_time) {
    if (s->preview_rendering) {
        s->preview_pending = true;
        s->pending_time = requested_time;
        return;
    }
    s->preview_rendering = true;
    const auto snapshot = cubical::temporary_path("cubical-compare-preview-project", ".ccx");
    const auto output = cubical::temporary_path("cubical-compare-preview", ".png");
    std::error_code copy_error;
    fs::copy_file(s->working_ccx, snapshot, fs::copy_options::overwrite_existing, copy_error);
    if (copy_error) {
        s->preview_rendering = false;
        set_status(s, "Could not prepare preview project: " + copy_error.message());
        return;
    }

    std::thread([s, snapshot, output, requested_time]() {
        const auto result = cubical::run_engine({
            "render-preview", snapshot.string(), output.string(),
            "--time", std::to_string(requested_time)
        });
        auto* payload = new PreviewResult{
            s, output, snapshot, requested_time,
            result.exit_code == 0 && fs::exists(output)
                ? std::string{}
                : (result.output.empty() ? "Preview rendering failed." : result.output)
        };
        g_idle_add(preview_ready, payload);
    }).detach();
}

void request_preview(AppState* s, double requested_time, bool sync_project = true) {
    if (sync_project && !write_working(s)) return;
    s->duration = cubical::timeline_duration(s->project);
    s->current_time = std::clamp(requested_time, 0.0, std::max(0.0, s->duration));
    update_player_ui(s);
    start_preview_render(s, s->current_time);
}

void reset_player(AppState* s) {
    s->playing = false;
    s->duration = cubical::timeline_duration(s->project);
    s->current_time = 0.0;
    update_player_ui(s);
    if (write_working(s)) start_preview_render(s, 0.0);
}

void play_clicked(GtkButton*, gpointer data) {
    auto* s = static_cast<AppState*>(data);
    if (s->playing) {
        s->playing = false;
        update_player_ui(s);
        return;
    }
    if (!write_working(s)) return;
    s->duration = cubical::timeline_duration(s->project);
    if (s->current_time >= s->duration) s->current_time = 0.0;
    s->playing = true;
    s->play_anchor_time = s->current_time;
    s->play_anchor = std::chrono::steady_clock::now();
    update_player_ui(s);
    start_preview_render(s, s->current_time);
}

void restart_clicked(GtkButton*, gpointer data) {
    auto* s = static_cast<AppState*>(data);
    s->playing = false;
    request_preview(s, 0.0, true);
}

void seek_changed(GtkRange* range, gpointer data) {
    auto* s = static_cast<AppState*>(data);
    if (s->updating_seek) return;
    const double value = gtk_range_get_value(range);
    s->current_time = value;
    if (s->playing) {
        s->play_anchor_time = value;
        s->play_anchor = std::chrono::steady_clock::now();
    }
    request_preview(s, value, true);
}

static gboolean playback_tick(gpointer data) {
    auto* s = static_cast<AppState*>(data);
    if (!s->playing) return G_SOURCE_CONTINUE;
    const auto now = std::chrono::steady_clock::now();
    const double elapsed = std::chrono::duration<double>(now - s->play_anchor).count();
    s->current_time = s->play_anchor_time + elapsed;
    if (s->current_time >= s->duration) {
        s->current_time = s->duration;
        s->playing = false;
    }
    update_player_ui(s);
    start_preview_render(s, s->current_time);
    return G_SOURCE_CONTINUE;
}

static gboolean task_progress_tick(gpointer data) {
    auto* s = static_cast<AppState*>(data);
    if (!s->busy || !s->task_progress) return G_SOURCE_REMOVE;
    int percent = 0, done = 0, total = 0;
    std::ifstream in(s->task_progress_path);
    if (in) in >> percent >> done >> total;
    percent = std::clamp(percent, 0, 100);
    if (percent > 0 || total > 0) {
        gtk_progress_bar_set_fraction(GTK_PROGRESS_BAR(s->task_progress), percent / 100.0);
        const std::string bar_text = std::to_string(percent) + "%";
        gtk_progress_bar_set_text(GTK_PROGRESS_BAR(s->task_progress), bar_text.c_str());
        if (done > 0 && total > 0 && s->task_label) {
            const std::string message = s->task_is_export
                ? "Rendering frame " + std::to_string(done) + " of " + std::to_string(total) + "…"
                : "Preparing image " + std::to_string(done) + " of " + std::to_string(total) + "…";
            gtk_label_set_text(GTK_LABEL(s->task_label), message.c_str());
        }
    } else {
        gtk_progress_bar_pulse(GTK_PROGRESS_BAR(s->task_progress));
    }
    return G_SOURCE_CONTINUE;
}

static gboolean block_task_close(GtkWindow*, gpointer) { return TRUE; }

void cancel_task(GtkButton*, gpointer data) {
    auto* s = static_cast<AppState*>(data);
    if (!s->busy) return;
    std::ofstream(s->task_cancel_path) << "cancel";
    gtk_label_set_text(GTK_LABEL(s->task_label), "Cancelling…");
    gtk_widget_set_sensitive(s->task_cancel, FALSE);
}

bool begin_task(AppState* s, bool export_task, const char* title, const char* message) {
    if (s->busy) { set_status(s, "Another operation is already running."); return false; }
    s->busy = true;
    s->task_is_export = export_task;
    s->playing = false;
    update_player_ui(s);
    s->task_progress_path = cubical::temporary_path("cubical-compare-progress", ".txt");
    s->task_cancel_path = cubical::temporary_path("cubical-compare-cancel", ".flag");
    std::ofstream(s->task_progress_path) << 0;
    s->task_window = gtk_window_new();
    gtk_window_set_title(GTK_WINDOW(s->task_window), title);
    gtk_window_set_transient_for(GTK_WINDOW(s->task_window), GTK_WINDOW(s->window));
    gtk_window_set_modal(GTK_WINDOW(s->task_window), TRUE);
    gtk_window_set_destroy_with_parent(GTK_WINDOW(s->task_window), TRUE);
    gtk_window_set_resizable(GTK_WINDOW(s->task_window), FALSE);
    gtk_window_set_default_size(GTK_WINDOW(s->task_window), 430, 150);
    g_signal_connect(s->task_window, "close-request", G_CALLBACK(block_task_close), nullptr);
    GtkWidget* box = gtk_box_new(GTK_ORIENTATION_VERTICAL, 12);
    gtk_widget_set_margin_top(box, 18); gtk_widget_set_margin_bottom(box, 18);
    gtk_widget_set_margin_start(box, 18); gtk_widget_set_margin_end(box, 18);
    s->task_label = gtk_label_new(message);
    gtk_label_set_wrap(GTK_LABEL(s->task_label), TRUE);
    s->task_progress = gtk_progress_bar_new();
    gtk_progress_bar_set_show_text(GTK_PROGRESS_BAR(s->task_progress), TRUE);
    gtk_progress_bar_set_text(GTK_PROGRESS_BAR(s->task_progress), "Starting…");
    gtk_progress_bar_set_pulse_step(GTK_PROGRESS_BAR(s->task_progress), 0.08);
    s->task_cancel = gtk_button_new_with_label("Cancel");
    g_signal_connect(s->task_cancel, "clicked", G_CALLBACK(cancel_task), s);
    gtk_box_append(GTK_BOX(box), s->task_label);
    gtk_box_append(GTK_BOX(box), s->task_progress);
    gtk_box_append(GTK_BOX(box), s->task_cancel);
    gtk_window_set_child(GTK_WINDOW(s->task_window), box);
    gtk_window_present(GTK_WINDOW(s->task_window));
    s->task_timer = g_timeout_add(100, task_progress_tick, s);
    return true;
}

void finish_task(AppState* s, const std::string& status) {
    if (s->task_timer) { g_source_remove(s->task_timer); s->task_timer = 0; }
    if (s->task_window) { gtk_window_destroy(GTK_WINDOW(s->task_window)); s->task_window = nullptr; }
    s->task_label = nullptr; s->task_progress = nullptr; s->task_cancel = nullptr;
    std::error_code ignored;
    fs::remove(s->task_progress_path, ignored); fs::remove(s->task_cancel_path, ignored);
    s->busy = false;
    s->task_is_export = false;
    set_status(s, status);
}

static gboolean task_ready(gpointer data) {
    std::unique_ptr<TaskResult> payload(static_cast<TaskResult*>(data));
    AppState* s = payload->state;
    std::string status;
    if (payload->process.exit_code == 0) {
        if (payload->kind == TaskKind::ImportSheet) {
            std::string error;
            if (cubical::load_ccx(s->project, payload->output_ccx, &error)) {
                select_index(s, 0); load_project_ui(s); reset_player(s);
                status = payload->process.output.empty() ? "Image sheet imported." : payload->process.output;
            } else status = error.empty() ? "The imported image-sheet project could not be loaded." : error;
        } else {
            const fs::path exported(payload->target_path);
            status = valid_mp4_file(exported)
                ? "Exported " + exported.string()
                : "Export failed: no usable MP4 was created.";
        }
    } else status = payload->process.output.empty() ? "Operation failed." : payload->process.output;
    std::error_code ignored;
    if (!payload->output_ccx.empty()) fs::remove(payload->output_ccx, ignored);
    finish_task(s, status);
    return G_SOURCE_REMOVE;
}

void pick_response(GtkNativeDialog* dialog, int response, gpointer data) {
    std::unique_ptr<PickContext> ctx(static_cast<PickContext*>(data));
    auto* s = ctx->state;
    if (response != GTK_RESPONSE_ACCEPT) { g_object_unref(dialog); return; }
    GFile* file = gtk_file_chooser_get_file(GTK_FILE_CHOOSER(dialog));
    char* raw = file ? g_file_get_path(file) : nullptr;
    std::string path = raw ? raw : "";
    if (raw) g_free(raw); if (file) g_object_unref(file); g_object_unref(dialog);
    if (path.empty()) return;

    if (ctx->action == PickAction::CardImage) { set_entry(s->image, path); sync_project_from_ui(s); request_preview(s, s->current_time); return; }
    if (ctx->action == PickAction::Soundtrack) { set_entry(s->soundtrack, path); sync_project_from_ui(s); set_status(s, "Soundtrack replaced: " + path); return; }
    if (ctx->action >= PickAction::FontTitle && ctx->action <= PickAction::FontCredits) {
        int i = static_cast<int>(ctx->action) - static_cast<int>(PickAction::FontTitle); set_entry(s->font_fields[i], path); return;
    }
    if (!write_working(s)) return;

    if (ctx->action == PickAction::OpenProject) {
        std::string load_error;
        bool loaded = false;
        const fs::path selected_path(path);
        if (selected_path.extension() == ".ccx") {
            loaded = cubical::load_ccx(s->project, selected_path, &load_error);
        } else {
            const auto result = cubical::run_engine({"project-to-ccx", path, s->working_ccx.string()});
            if (result.exit_code == 0) loaded = cubical::load_ccx(s->project, s->working_ccx, &load_error);
            else load_error = result.output;
        }
        if (loaded) {
            s->project.project_path = selected_path; select_index(s, 0);
            load_project_ui(s); reset_player(s);
        } else set_status(s, load_error.empty() ? "Could not open the selected project." : load_error);
    } else if (ctx->action == PickAction::SaveProject) {
        const fs::path selected_path(path);
        if (selected_path.extension() == ".ccx") {
            std::string save_error;
            if (cubical::save_ccx(s->project, selected_path, &save_error)) {
                s->project.project_path = selected_path; set_status(s, "Saved " + path);
            } else set_status(s, save_error);
        } else {
            auto result = cubical::run_engine({"ccx-to-project", s->working_ccx.string(), path});
            if (result.exit_code == 0) { s->project.project_path = selected_path; set_status(s, "Saved " + path); }
            else set_status(s, result.output);
        }
    } else if (ctx->action == PickAction::ImportData) {
        auto out = cubical::temporary_path("cubical-import", ".ccx");
        auto result = cubical::run_engine({"import-data", s->working_ccx.string(), path, out.string()});
        if (result.exit_code == 0 && cubical::load_ccx(s->project, out)) { select_index(s, 0); load_project_ui(s); reset_player(s); }
        else set_status(s, result.output);
        std::error_code ec; fs::remove(out, ec);
    } else if (ctx->action == PickAction::ImportSheet) {
        auto out = cubical::temporary_path("cubical-sheet", ".ccx");
        auto assets = cubical::temporary_path("cubical-compare-sheet-assets", "");
        int rows = gtk_spin_button_get_value_as_int(GTK_SPIN_BUTTON(s->sheet_rows));
        int columns = gtk_spin_button_get_value_as_int(GTK_SPIN_BUTTON(s->sheet_columns));
        int start = std::max(0, gtk_spin_button_get_value_as_int(GTK_SPIN_BUTTON(s->sheet_start)) - 1);
        if (!begin_task(s, false, "Importing image sheet", "Detecting the grid and preparing card artwork…")) return;
        std::vector<std::string> command = {"import-sheet", s->working_ccx.string(), path, out.string(), assets.string(), "--expected", std::to_string(s->project.cards.size()), "--start", std::to_string(start), "--fit", "cts_card", "--progress-file", s->task_progress_path.string(), "--cancel-file", s->task_cancel_path.string()};
        if (rows > 0 && columns > 0) command.insert(command.end(), {"--rows", std::to_string(rows), "--columns", std::to_string(columns)});
        auto* state = s;
        std::thread([state, command = std::move(command), out, assets]() mutable {
            auto result = cubical::run_engine(command);
            g_idle_add(task_ready, new TaskResult{state, TaskKind::ImportSheet, std::move(result), out, assets, {}});
        }).detach();
    } else if (ctx->action == PickAction::ExportVideo) {
        fs::path export_path(path);
        if (export_path.extension() != ".mp4") export_path.replace_extension(".mp4");
        path = export_path.string();
        if (!begin_task(s, true, "Exporting MP4", "Starting the real frame renderer and FFmpeg encoder…")) return;
        auto input = s->working_ccx; auto* state = s;
        const auto progress = s->task_progress_path; const auto cancel = s->task_cancel_path;
        std::thread([state, input, path, progress, cancel]() {
            auto result = cubical::run_engine({"export", input.string(), path, "--fast", "--progress-file", progress.string(), "--cancel-file", cancel.string()});
            g_idle_add(task_ready, new TaskResult{state, TaskKind::ExportVideo, std::move(result), {}, {}, path});
        }).detach();
    }

}

void choose(AppState* s, PickAction action, GtkFileChooserAction chooser_action, const char* title) {
    if (s->busy) { set_status(s, "Finish or cancel the current operation first."); return; }
    auto* chooser = gtk_file_chooser_native_new(title, GTK_WINDOW(s->window), chooser_action,
        chooser_action == GTK_FILE_CHOOSER_ACTION_SAVE ? "Save" : "Open", "Cancel");
    auto* ctx = new PickContext{s, action};
    g_signal_connect(chooser, "response", G_CALLBACK(pick_response), ctx);
    gtk_native_dialog_show(GTK_NATIVE_DIALOG(chooser));
}

void choose_open(GtkButton*, gpointer data) { choose(static_cast<AppState*>(data), PickAction::OpenProject, GTK_FILE_CHOOSER_ACTION_OPEN, "Open Cubical Compare project"); }
void choose_save(GtkButton*, gpointer data) { choose(static_cast<AppState*>(data), PickAction::SaveProject, GTK_FILE_CHOOSER_ACTION_SAVE, "Save Cubical Compare project"); }
void choose_data(GtkButton*, gpointer data) { choose(static_cast<AppState*>(data), PickAction::ImportData, GTK_FILE_CHOOSER_ACTION_OPEN, "Insert spreadsheet data"); }
void choose_sheet(GtkButton*, gpointer data) { choose(static_cast<AppState*>(data), PickAction::ImportSheet, GTK_FILE_CHOOSER_ACTION_OPEN, "Import image sheet"); }
void choose_image(GtkButton*, gpointer data) { choose(static_cast<AppState*>(data), PickAction::CardImage, GTK_FILE_CHOOSER_ACTION_OPEN, "Choose card image"); }
void choose_soundtrack(GtkButton*, gpointer data) { choose(static_cast<AppState*>(data), PickAction::Soundtrack, GTK_FILE_CHOOSER_ACTION_OPEN, "Choose soundtrack"); }
void choose_export(GtkButton*, gpointer data) { choose(static_cast<AppState*>(data), PickAction::ExportVideo, GTK_FILE_CHOOSER_ACTION_SAVE, "Export MP4"); }

void new_project(GtkButton*, gpointer data) {
    auto* s = static_cast<AppState*>(data);
    s->project = cubical::Project{};
    s->project.project_path.clear();
    select_index(s, 0);
    load_project_ui(s);
    reset_player(s);
}

void reuse_title_font(GtkButton*, gpointer data) {
    auto* s = static_cast<AppState*>(data);
    const std::string font = entry_text(s->font_fields[0]);
    for (auto* field : s->font_fields) set_entry(field, font);
    sync_project_from_ui(s);
    request_preview(s, s->current_time);
}

void font_dialog_response(GtkDialog* dialog, int response, gpointer data) {
    std::unique_ptr<FontDialogContext> ctx(static_cast<FontDialogContext*>(data));
    if (response == GTK_RESPONSE_OK) {
        char* selected = gtk_font_chooser_get_font(GTK_FONT_CHOOSER(dialog));
        if (selected) {
            PangoFontDescription* description = pango_font_description_from_string(selected);
            const char* family = description ? pango_font_description_get_family(description) : nullptr;
            set_entry(ctx->state->font_fields[ctx->index], family && *family ? family : selected);
            if (description) pango_font_description_free(description);
            g_free(selected);
        }
    }
    gtk_window_destroy(GTK_WINDOW(dialog));
}

void list_system_fonts(GtkButton* button, gpointer data) {
    auto* s = static_cast<AppState*>(data);
    const int index = GPOINTER_TO_INT(g_object_get_data(G_OBJECT(button), "font-index"));
    GtkWidget* dialog = gtk_font_chooser_dialog_new("List system fonts", GTK_WINDOW(s->window));
    const char* current = entry_text(s->font_fields[index]);
    if (current && *current) gtk_font_chooser_set_font(GTK_FONT_CHOOSER(dialog), current);
    auto* context = new FontDialogContext{s, index};
    g_signal_connect(dialog, "response", G_CALLBACK(font_dialog_response), context);
    gtk_window_present(GTK_WINDOW(dialog));
}

GtkWidget* make_scrolled(GtkWidget* child) {
    GtkWidget* scroll = gtk_scrolled_window_new();
    gtk_scrolled_window_set_policy(GTK_SCROLLED_WINDOW(scroll), GTK_POLICY_AUTOMATIC, GTK_POLICY_AUTOMATIC);
    gtk_scrolled_window_set_child(GTK_SCROLLED_WINDOW(scroll), child); return scroll;
}

void activate(GtkApplication* app, gpointer data) {
    auto* s = static_cast<AppState*>(data);
    s->window = gtk_application_window_new(app);
    gtk_window_set_title(GTK_WINDOW(s->window), "Cubical Compare");
    gtk_window_set_default_size(GTK_WINDOW(s->window), 1180, 660);
    gtk_widget_set_size_request(s->window, 900, 580);

    GtkWidget* root = gtk_box_new(GTK_ORIENTATION_VERTICAL, 8);
    gtk_widget_set_margin_top(root, 10); gtk_widget_set_margin_bottom(root, 10);
    gtk_widget_set_margin_start(root, 10); gtk_widget_set_margin_end(root, 10);
    gtk_window_set_child(GTK_WINDOW(s->window), root);

    GtkWidget* toolbar = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 6);
    struct Button { const char* label; GCallback cb; } buttons[] = {
        {"New", G_CALLBACK(new_project)}, {"Open", G_CALLBACK(choose_open)}, {"Save", G_CALLBACK(choose_save)},
        {"Click to Insert Data", G_CALLBACK(choose_data)}, {"Image Sheet", G_CALLBACK(choose_sheet)},
        {"Export MP4", G_CALLBACK(choose_export)},
    };
    for (auto& b : buttons) { GtkWidget* w = gtk_button_new_with_label(b.label); g_signal_connect(w, "clicked", b.cb, s); gtk_box_append(GTK_BOX(toolbar), w); }
    gtk_box_append(GTK_BOX(root), toolbar);

    GtkWidget* workspace = gtk_paned_new(GTK_ORIENTATION_HORIZONTAL);
    gtk_widget_set_vexpand(workspace, TRUE);
    gtk_paned_set_position(GTK_PANED(workspace), 620);

    GtkWidget* preview_column = gtk_box_new(GTK_ORIENTATION_VERTICAL, 6);
    s->preview = gtk_picture_new();
    gtk_picture_set_can_shrink(GTK_PICTURE(s->preview), TRUE);
    gtk_picture_set_content_fit(GTK_PICTURE(s->preview), GTK_CONTENT_FIT_CONTAIN);
    gtk_widget_set_size_request(s->preview, 480, 270);
    gtk_widget_set_hexpand(s->preview, TRUE);
    gtk_widget_set_vexpand(s->preview, TRUE);
    gtk_box_append(GTK_BOX(preview_column), s->preview);

    GtkWidget* player = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 6);
    s->play_button = gtk_button_new_with_label("Play");
    GtkWidget* restart = gtk_button_new_with_label("Restart");
    s->seek = gtk_scale_new_with_range(GTK_ORIENTATION_HORIZONTAL, 0.0, 1.0, 0.01);
    gtk_scale_set_draw_value(GTK_SCALE(s->seek), FALSE);
    gtk_widget_set_hexpand(s->seek, TRUE);
    s->time_label = gtk_label_new("00:00.0 / 00:00.0");
    g_signal_connect(s->play_button, "clicked", G_CALLBACK(play_clicked), s);
    g_signal_connect(restart, "clicked", G_CALLBACK(restart_clicked), s);
    g_signal_connect(s->seek, "value-changed", G_CALLBACK(seek_changed), s);
    gtk_box_append(GTK_BOX(player), s->play_button);
    gtk_box_append(GTK_BOX(player), restart);
    gtk_box_append(GTK_BOX(player), s->seek);
    gtk_box_append(GTK_BOX(player), s->time_label);
    gtk_box_append(GTK_BOX(preview_column), player);

    GtkWidget* sheet_row = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 5);
    gtk_box_append(GTK_BOX(sheet_row), gtk_label_new("Sheet rows"));
    s->sheet_rows = gtk_spin_button_new_with_range(0, 20, 1); gtk_widget_set_size_request(s->sheet_rows, 65, -1); gtk_box_append(GTK_BOX(sheet_row), s->sheet_rows);
    gtk_box_append(GTK_BOX(sheet_row), gtk_label_new("Columns"));
    s->sheet_columns = gtk_spin_button_new_with_range(0, 20, 1); gtk_widget_set_size_request(s->sheet_columns, 65, -1); gtk_box_append(GTK_BOX(sheet_row), s->sheet_columns);
    gtk_box_append(GTK_BOX(sheet_row), gtk_label_new("Start card"));
    s->sheet_start = gtk_spin_button_new_with_range(1, 10000, 1); gtk_spin_button_set_value(GTK_SPIN_BUTTON(s->sheet_start), 1); gtk_widget_set_size_request(s->sheet_start, 80, -1); gtk_box_append(GTK_BOX(sheet_row), s->sheet_start);
    gtk_box_append(GTK_BOX(preview_column), sheet_row);
    gtk_paned_set_start_child(GTK_PANED(workspace), preview_column);

    GtkWidget* editor_paned = gtk_paned_new(GTK_ORIENTATION_HORIZONTAL);
    gtk_paned_set_position(GTK_PANED(editor_paned), 210);
    GtkWidget* left = gtk_box_new(GTK_ORIENTATION_VERTICAL, 5);
    gtk_widget_set_size_request(left, 170, -1);
    s->card_list = gtk_list_box_new(); g_signal_connect(s->card_list, "row-selected", G_CALLBACK(card_selected), s);
    GtkWidget* card_scroll = make_scrolled(s->card_list); gtk_widget_set_vexpand(card_scroll, TRUE);
    gtk_box_append(GTK_BOX(left), card_scroll);
    GtkWidget* card_actions = gtk_box_new(GTK_ORIENTATION_VERTICAL, 4);
    GtkWidget* add = gtk_button_new_with_label("Add card"); GtkWidget* rem = gtk_button_new_with_label("Remove"); GtkWidget* trim = gtk_button_new_with_label("Trim after selected");
    g_signal_connect(add, "clicked", G_CALLBACK(add_card), s); g_signal_connect(rem, "clicked", G_CALLBACK(remove_card), s); g_signal_connect(trim, "clicked", G_CALLBACK(trim_cards), s);
    gtk_box_append(GTK_BOX(card_actions), add); gtk_box_append(GTK_BOX(card_actions), rem); gtk_box_append(GTK_BOX(card_actions), trim); gtk_box_append(GTK_BOX(left), card_actions);
    gtk_paned_set_start_child(GTK_PANED(editor_paned), left);

    GtkWidget* notebook = gtk_notebook_new();
    GtkWidget* card_grid = gtk_grid_new(); gtk_grid_set_row_spacing(GTK_GRID(card_grid), 7); gtk_grid_set_column_spacing(GTK_GRID(card_grid), 8);
    gtk_widget_set_margin_top(card_grid, 10); gtk_widget_set_margin_start(card_grid, 10); gtk_widget_set_margin_end(card_grid, 10);
    s->title = form_entry(card_grid, 0, "Title", s); s->value = form_entry(card_grid, 1, "Value / badge", s);
    s->description = form_entry(card_grid, 2, "Description (blank hides band)", s); s->image = form_entry(card_grid, 3, "Image", s);
    GtkWidget* image_button = gtk_button_new_with_label("Choose image…"); g_signal_connect(image_button, "clicked", G_CALLBACK(choose_image), s); gtk_grid_attach(GTK_GRID(card_grid), image_button, 1, 4, 1, 1);
    gtk_notebook_append_page(GTK_NOTEBOOK(notebook), make_scrolled(card_grid), gtk_label_new("Card"));

    GtkWidget* text_grid = gtk_grid_new(); gtk_grid_set_row_spacing(GTK_GRID(text_grid), 7); gtk_grid_set_column_spacing(GTK_GRID(text_grid), 8);
    gtk_widget_set_margin_top(text_grid, 10); gtk_widget_set_margin_start(text_grid, 10); gtk_widget_set_margin_end(text_grid, 10);
    int r = 0; s->project_name = form_entry(text_grid, r++, "Project title (optional)", s);
    s->credits_enabled = gtk_check_button_new_with_label("Show opening credits"); gtk_grid_attach(GTK_GRID(text_grid), s->credits_enabled, 1, r++, 1, 1);
    const char* labels[] = {"Credits top text", "Credits heading", "Credits project/name", "Created-with label", "Created-with value", "Design label", "Design value", "Credits footer", "End left label", "End right label", "End credit label", "End credit value"};
    for (auto* label : labels) s->text_fields.push_back(form_entry(text_grid, r++, label, s));
    gtk_notebook_append_page(GTK_NOTEBOOK(notebook), make_scrolled(text_grid), gtk_label_new("Visible text"));

    GtkWidget* audio_grid = gtk_grid_new(); gtk_grid_set_row_spacing(GTK_GRID(audio_grid), 8); gtk_grid_set_column_spacing(GTK_GRID(audio_grid), 8); gtk_widget_set_margin_top(audio_grid, 10); gtk_widget_set_margin_start(audio_grid, 10);
    s->soundtrack = form_entry(audio_grid, 0, "Soundtrack", s); GtkWidget* audio_button = gtk_button_new_with_label("Choose / replace…"); g_signal_connect(audio_button, "clicked", G_CALLBACK(choose_soundtrack), s); gtk_grid_attach(GTK_GRID(audio_grid), audio_button, 1, 1, 1, 1);
    s->loop = gtk_check_button_new_with_label("Loop until video ends"); gtk_grid_attach(GTK_GRID(audio_grid), s->loop, 1, 2, 1, 1);
    s->volume = spin(audio_grid, 3, "Volume %", 0, 100, 1); s->offset = spin(audio_grid, 4, "Start inside track (s)", 0, 86400, 0.1); s->fade = spin(audio_grid, 5, "Fade out (s)", 0, 30, 0.1);
    gtk_notebook_append_page(GTK_NOTEBOOK(notebook), make_scrolled(audio_grid), gtk_label_new("Soundtrack"));

    GtkWidget* out_grid = gtk_grid_new(); gtk_grid_set_row_spacing(GTK_GRID(out_grid), 8); gtk_grid_set_column_spacing(GTK_GRID(out_grid), 8); gtk_widget_set_margin_top(out_grid, 10); gtk_widget_set_margin_start(out_grid, 10); gtk_widget_set_margin_end(out_grid, 10);
    s->width = spin(out_grid, 0, "Width", 640, 7680, 1); s->height = spin(out_grid, 1, "Height", 360, 4320, 1); s->fps = spin(out_grid, 2, "FPS", 24, 60, 1);
    s->preset = form_entry(out_grid, 3, "Encoder preset", s); s->crf = spin(out_grid, 4, "CRF", 0, 51, 1);
    const char* font_labels[] = {"Title font (file or family)", "Description font (file or family)", "Badge font (file or family)", "Credits font (file or family)"};
    for (int i = 0; i < 4; ++i) {
        GtkWidget* label = gtk_label_new(font_labels[i]); gtk_label_set_xalign(GTK_LABEL(label), 0.0f); gtk_grid_attach(GTK_GRID(out_grid), label, 0, 5 + i, 1, 1);
        GtkWidget* entry = gtk_entry_new(); gtk_widget_set_hexpand(entry, TRUE); gtk_grid_attach(GTK_GRID(out_grid), entry, 1, 5 + i, 1, 1); g_signal_connect(entry, "changed", G_CALLBACK(fields_changed), s); s->font_fields.push_back(entry);
        GtkWidget* list = gtk_button_new_with_label("List system fonts…"); g_object_set_data(G_OBJECT(list), "font-index", GINT_TO_POINTER(i)); g_signal_connect(list, "clicked", G_CALLBACK(list_system_fonts), s); gtk_grid_attach(GTK_GRID(out_grid), list, 2, 5 + i, 1, 1);
    }
    GtkWidget* reuse = gtk_button_new_with_label("Reuse title font for all fields"); g_signal_connect(reuse, "clicked", G_CALLBACK(reuse_title_font), s); gtk_grid_attach(GTK_GRID(out_grid), reuse, 1, 9, 2, 1);
    gtk_notebook_append_page(GTK_NOTEBOOK(notebook), make_scrolled(out_grid), gtk_label_new("Appearance & output"));

    gtk_paned_set_end_child(GTK_PANED(editor_paned), notebook);
    gtk_paned_set_end_child(GTK_PANED(workspace), editor_paned);
    gtk_box_append(GTK_BOX(root), workspace);
    s->status = gtk_label_new("Ready"); gtk_label_set_xalign(GTK_LABEL(s->status), 0.0f); gtk_box_append(GTK_BOX(root), s->status);

    g_signal_connect(s->window, "close-request", G_CALLBACK(+[](GtkWindow*, gpointer data) -> gboolean {
        auto* state = static_cast<AppState*>(data);
        if (!state->busy) return FALSE;
        std::ofstream(state->task_cancel_path) << "cancel";
        set_status(state, "Cancelling the current operation before closing…");
        return TRUE;
    }), s);
    load_project_ui(s);
    gtk_window_present(GTK_WINDOW(s->window));
    g_timeout_add(100, playback_tick, s);
    g_idle_add([](gpointer data) -> gboolean {
        reset_player(static_cast<AppState*>(data));
        return G_SOURCE_REMOVE;
    }, s);
}
}

int main(int argc, char** argv) {
    if (argc > 1 && std::string(argv[1]) == "--self-test") {
        cubical::Project timing_project;
        timing_project.cards.assign(8, {"Card", "1", "", ""});
        if (std::abs(cubical::timeline_duration(timing_project) - 28.75) > 1e-9) return 3;
        cubical::Project deletion_project;
        deletion_project.cards = {{"First", "1", "", ""}, {"", "2", "", ""}, {"Third", "3", "", ""}};
        const std::string untitled_id = deletion_project.cards[1].id;
        if (cubical::erase_card_by_id(deletion_project, untitled_id) != 1
            || deletion_project.cards.size() != 2
            || deletion_project.cards[0].title != "First"
            || deletion_project.cards[1].title != "Third") return 4;
        const auto directory = cubical::temporary_path("cubical-compare-native-self-test", "");
        const auto result = cubical::run_engine({"self-test", "--directory", directory.string()});
        std::cout << result.output;
        std::error_code ignored; fs::remove_all(directory, ignored);
        return result.exit_code;
    }
    auto state = std::make_unique<AppState>();
    state->working_ccx = cubical::temporary_path("cubical-create", ".ccx");
    state->preview_path.clear();
    GtkApplication* app = gtk_application_new("network.cubical.Create", G_APPLICATION_DEFAULT_FLAGS);
    g_signal_connect(app, "activate", G_CALLBACK(activate), state.get());
    int result = g_application_run(G_APPLICATION(app), argc, argv);
    std::error_code ec; fs::remove(state->working_ccx, ec); fs::remove(state->preview_path, ec);
    g_object_unref(app); return result;
}
