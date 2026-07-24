#include <gtk/gtk.h>
#include "cubical/project.hpp"
#include <memory>

namespace {
struct AppState {
    cubical::Project project;
    GtkWidget* status{};
};

void set_status(AppState* state, const char* text) {
    gtk_label_set_text(GTK_LABEL(state->status), text);
}

void sheet_response(GtkNativeDialog* dialog, int response, gpointer data) {
    auto* state = static_cast<AppState*>(data);
    if (response == GTK_RESPONSE_ACCEPT) {
        GFile* file = gtk_file_chooser_get_file(GTK_FILE_CHOOSER(dialog));
        if (file) {
            char* path = g_file_get_path(file);
            if (path) {
                set_status(state, path);
                g_free(path);
            }
            g_object_unref(file);
        }
    }
    g_object_unref(dialog);
}

void open_sheet(GtkButton*, gpointer data) {
    auto* chooser = gtk_file_chooser_native_new(
        "Import image sheet", nullptr, GTK_FILE_CHOOSER_ACTION_OPEN, "Open", "Cancel");
    g_signal_connect(chooser, "response", G_CALLBACK(sheet_response), data);
    gtk_native_dialog_show(GTK_NATIVE_DIALOG(chooser));
}

void activate(GtkApplication* app, gpointer data) {
    auto* state = static_cast<AppState*>(data);
    GtkWidget* window = gtk_application_window_new(app);
    gtk_window_set_title(GTK_WINDOW(window), "Cubical Create");
    gtk_window_set_default_size(GTK_WINDOW(window), 1100, 720);

    GtkWidget* root = gtk_box_new(GTK_ORIENTATION_VERTICAL, 10);
    gtk_widget_set_margin_top(root, 14);
    gtk_widget_set_margin_bottom(root, 14);
    gtk_widget_set_margin_start(root, 14);
    gtk_widget_set_margin_end(root, 14);
    gtk_window_set_child(GTK_WINDOW(window), root);

    GtkWidget* title = gtk_label_new("Cubical Create");
    gtk_widget_add_css_class(title, "title-1");
    gtk_widget_set_halign(title, GTK_ALIGN_START);
    gtk_box_append(GTK_BOX(root), title);

    state->status = gtk_label_new(cubical::summary(state->project).c_str());
    gtk_widget_set_halign(state->status, GTK_ALIGN_START);
    gtk_box_append(GTK_BOX(root), state->status);

    GtkWidget* preview = gtk_drawing_area_new();
    gtk_widget_set_hexpand(preview, TRUE);
    gtk_widget_set_vexpand(preview, TRUE);
    gtk_widget_add_css_class(preview, "card");
    gtk_box_append(GTK_BOX(root), preview);

    GtkWidget* actions = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 8);
    GtkWidget* insert = gtk_button_new_with_label("Insert Data");
    GtkWidget* sheet = gtk_button_new_with_label("Image Sheet");
    GtkWidget* soundtrack = gtk_button_new_with_label("Soundtrack");
    GtkWidget* controls = gtk_button_new_with_label("Project Controls");
    GtkWidget* export_button = gtk_button_new_with_label("Export");
    gtk_widget_add_css_class(insert, "suggested-action");
    gtk_box_append(GTK_BOX(actions), insert);
    gtk_box_append(GTK_BOX(actions), sheet);
    gtk_box_append(GTK_BOX(actions), soundtrack);
    gtk_box_append(GTK_BOX(actions), controls);
    gtk_box_append(GTK_BOX(actions), export_button);
    gtk_box_append(GTK_BOX(root), actions);
    g_signal_connect(sheet, "clicked", G_CALLBACK(open_sheet), state);

    gtk_window_present(GTK_WINDOW(window));
}
}

int main(int argc, char** argv) {
    auto state = std::make_unique<AppState>();
    GtkApplication* app = gtk_application_new("network.cubical.Create", G_APPLICATION_DEFAULT_FLAGS);
    g_signal_connect(app, "activate", G_CALLBACK(activate), state.get());
    int result = g_application_run(G_APPLICATION(app), argc, argv);
    g_object_unref(app);
    return result;
}
