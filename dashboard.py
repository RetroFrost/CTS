#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
from pathlib import Path
import queue
import sys
import threading
import traceback
import uuid
import tkinter as tk
from tkinter import colorchooser, filedialog, messagebox, simpledialog, ttk

APP_ROOT = Path(__file__).resolve().parent
ENGINE_ROOT = APP_ROOT / "engine"
if ENGINE_ROOT.is_dir():
    sys.path.insert(0, str(ENGINE_ROOT))
else:
    # Source-tree development layout.
    candidate = APP_ROOT / "engine"
    sys.path.insert(0, str(candidate))

from PIL import Image, ImageTk  # type: ignore
from ccengine.assets import collect_project_assets
from ccengine.exporter import ExportCancelled, VideoExporter
from ccengine.image_sheet_core import ImageSheetProcessor, export_sheet_crops
from ccengine.importers import load_spreadsheet, parse_pasted_data
from ccengine.model_registry import get_model, list_models
from ccengine.models import Card, Project, ProjectSettings
from ccengine.renderer import FrameRenderer
from ccengine.timing import card_start_frames, total_duration, total_frame_count
from ccengine.validation import ENCODER_PRESETS, normalize_project
from engine_cli import read_ccx, write_ccx

VERSION = "1.0.6"
ACCENT = "#2f6fed"
ACCENT_DARK = "#2359c7"
BORDER = "#dce1ea"
TEXT = "#172033"
MUTED = "#657089"
PANEL = "#ffffff"
SURFACE = "#f5f7fb"
PREVIEW_BG = "#17202d"

MODEL_BY_NAME = {model.display_name: model.id for model in list_models()}
NAME_BY_MODEL = {model.id: model.display_name for model in list_models()}


class DashboardApp(tk.Tk):
    def __init__(self, *, self_test: bool = False) -> None:
        super().__init__()
        self.title(f"Cubical Compare {VERSION}")
        self.geometry("1360x820")
        self.minsize(1050, 650)
        self.configure(bg=SURFACE)
        self.option_add("*tearOff", False)

        self.project = self._new_project()
        self.project_path: Path | None = None
        self.selected_index = 0
        self.renderer = FrameRenderer()
        self.preview_photo: ImageTk.PhotoImage | None = None
        self.preview_after: str | None = None
        self.field_after: str | None = None
        self.exporter: VideoExporter | None = None
        self.export_cancel = threading.Event()
        self.sheet_cancel = threading.Event()
        self.sheet_import_active = False
        self.data_import_active = False
        self.worker_events: queue.Queue[tuple[str, object]] = queue.Queue()
        self.dirty = False
        self.loading_fields = False
        self.self_test = self_test

        self._make_style()
        self._make_variables()
        self._make_ui()
        self._bind_shortcuts()
        self._refresh_all()
        self.after(100, self._poll_worker_events)
        self.protocol("WM_DELETE_WINDOW", self._on_close)

    @staticmethod
    def _new_project(model_id: str = "what-males-learn-at-each-age") -> Project:
        model = get_model(model_id)
        return Project(
            name="Untitled Comparison",
            cards=[Card(title="Card 1", value="1", description="", image="")],
            settings=ProjectSettings(
                model_id=model.id,
                model_revision=model.revision,
                width=model.width,
                height=model.height,
                fps=model.fps,
            ),
        )

    def _make_style(self) -> None:
        style = ttk.Style(self)
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure("App.TFrame", background=SURFACE)
        style.configure("Panel.TFrame", background=PANEL, relief="flat")
        style.configure("Top.TFrame", background=PANEL)
        style.configure("Title.TLabel", background=PANEL, foreground=TEXT, font=("Sans", 11, "bold"))
        style.configure("Body.TLabel", background=PANEL, foreground=TEXT, font=("Sans", 10))
        style.configure("Muted.TLabel", background=PANEL, foreground=MUTED, font=("Sans", 9))
        style.configure("TopTitle.TLabel", background=PANEL, foreground=TEXT, font=("Sans", 11, "bold"))
        style.configure("Primary.TButton", background=ACCENT, foreground="white", borderwidth=0, padding=(15, 9), font=("Sans", 10, "bold"))
        style.map("Primary.TButton", background=[("active", ACCENT_DARK), ("pressed", ACCENT_DARK)])
        style.configure("Toolbar.TButton", background=PANEL, foreground=TEXT, bordercolor=BORDER, padding=(12, 8), font=("Sans", 9, "bold"))
        style.map("Toolbar.TButton", background=[("active", "#edf2ff")])
        style.configure("Small.TButton", background=PANEL, foreground=TEXT, bordercolor=BORDER, padding=(8, 5))
        style.configure("Danger.TButton", background=PANEL, foreground="#b42318", bordercolor="#f1c2bd", padding=(8, 5))
        style.configure("Treeview", background=PANEL, fieldbackground=PANEL, foreground=TEXT, rowheight=40, borderwidth=0)
        style.configure("Treeview.Heading", background="#f1f4f9", foreground=MUTED, font=("Sans", 9, "bold"), relief="flat")
        style.map("Treeview", background=[("selected", "#e8f0ff")], foreground=[("selected", TEXT)])
        style.configure("TNotebook", background=PANEL, borderwidth=0)
        style.configure("TNotebook.Tab", background="#eef2f7", foreground=MUTED, padding=(13, 7), font=("Sans", 9, "bold"))
        style.map("TNotebook.Tab", background=[("selected", PANEL)], foreground=[("selected", ACCENT)])
        style.configure("TEntry", padding=6)
        style.configure("TCombobox", padding=5)
        style.configure("Horizontal.TProgressbar", background=ACCENT, troughcolor="#e9edf4", borderwidth=0)

    def _make_variables(self) -> None:
        self.title_var = tk.StringVar()
        self.value_var = tk.StringVar()
        self.image_var = tk.StringVar()
        self.model_var = tk.StringVar()
        self.project_name_var = tk.StringVar()
        self.credits_enabled_var = tk.BooleanVar(value=True)
        self.soundtrack_var = tk.StringVar()
        self.soundtrack_volume_var = tk.DoubleVar(value=75.0)
        self.soundtrack_loop_var = tk.BooleanVar(value=True)
        self.soundtrack_offset_var = tk.DoubleVar(value=0.0)
        self.soundtrack_fade_var = tk.DoubleVar(value=0.75)
        self.encoder_preset_var = tk.StringVar(value="faster")
        self.image_fit_var = tk.StringVar(value="cover")
        self.font_title_var = tk.StringVar()
        self.font_description_var = tk.StringVar()
        self.font_badge_var = tk.StringVar()
        self.font_credits_var = tk.StringVar()
        self.image_scale_var = tk.DoubleVar(value=1.0)
        self.image_x_var = tk.DoubleVar(value=0.0)
        self.image_y_var = tk.DoubleVar(value=0.0)
        self.image_rotation_var = tk.DoubleVar(value=0.0)
        self.image_layer_var = tk.StringVar(value="behind")
        self.status_var = tk.StringVar(value="Ready")
        self.duration_var = tk.StringVar(value="")
        self.source_var = tk.StringVar(value="No imported data")
        self.progress_var = tk.DoubleVar(value=0.0)
        self.credits_project_var = tk.StringVar(value="Cubical Compare")
        self.credits_top_var = tk.StringVar(value="Values are estimates and may vary.")
        self.end_credit_var = tk.StringVar(value="Cubical Compare")
        self.preview_frame_var = tk.StringVar(value="")

    def _make_ui(self) -> None:
        outer = ttk.Frame(self, style="App.TFrame")
        outer.pack(fill="both", expand=True)

        top = ttk.Frame(outer, style="Top.TFrame", padding=(18, 12))
        top.pack(fill="x")
        ttk.Button(top, text="＋  New Comparison", style="Primary.TButton", command=self.new_project).pack(side="left")
        ttk.Button(top, text="Open", style="Toolbar.TButton", command=self.open_project).pack(side="left", padx=(12, 0))
        ttk.Button(top, text="Save", style="Toolbar.TButton", command=self.save_project).pack(side="left", padx=(8, 0))
        self.import_button = ttk.Button(top, text="Import Data", style="Toolbar.TButton", command=self.import_data)
        self.import_button.pack(side="left", padx=(8, 0))
        self.image_sheet_button = ttk.Button(top, text="Image Sheet", style="Toolbar.TButton", command=self.import_image_sheet)
        self.image_sheet_button.pack(side="left", padx=(8, 0))
        ttk.Button(top, text="Paste Data", style="Toolbar.TButton", command=self.paste_data).pack(side="left", padx=(8, 0))

        ttk.Label(top, text="Cubical Compare", style="TopTitle.TLabel").pack(side="left", expand=True)
        ttk.Button(top, text="▶  Preview", style="Toolbar.TButton", command=self.render_preview).pack(side="right", padx=(8, 0))
        self.export_button = ttk.Button(top, text="⇩  Export", style="Primary.TButton", command=self.export_video)
        self.export_button.pack(side="right", padx=(8, 0))

        ttk.Separator(outer).pack(fill="x")

        workspace = ttk.Panedwindow(outer, orient="vertical")
        workspace.pack(fill="both", expand=True)
        upper = ttk.Panedwindow(workspace, orient="horizontal")
        lower = ttk.Frame(workspace, style="Panel.TFrame", padding=(12, 8))
        workspace.add(upper, weight=4)
        workspace.add(lower, weight=2)

        self._make_cards_panel(upper)
        self._make_editor_panel(upper)
        self._make_preview_panel(upper)
        self._make_lower_panel(lower)

        status = ttk.Frame(outer, style="Top.TFrame", padding=(12, 6))
        status.pack(fill="x")
        ttk.Label(status, textvariable=self.status_var, style="Muted.TLabel").pack(side="left")
        self.progress = ttk.Progressbar(status, style="Horizontal.TProgressbar", variable=self.progress_var, maximum=100, length=230)
        self.progress.pack(side="right", padx=(8, 0))
        self.cancel_button = ttk.Button(status, text="Cancel", style="Danger.TButton", command=self.cancel_active_job)
        ttk.Label(status, textvariable=self.duration_var, style="Muted.TLabel").pack(side="right", padx=(0, 12))

    def _panel_header(self, parent: tk.Widget, title: str, trailing: tk.Widget | None = None) -> ttk.Frame:
        frame = ttk.Frame(parent, style="Panel.TFrame", padding=(12, 10))
        ttk.Label(frame, text=title, style="Title.TLabel").pack(side="left")
        if trailing is not None:
            trailing.pack(in_=frame, side="right")
        return frame

    def _make_cards_panel(self, parent: ttk.Panedwindow) -> None:
        panel = ttk.Frame(parent, style="Panel.TFrame", width=280)
        parent.add(panel, weight=1)
        header = ttk.Frame(panel, style="Panel.TFrame", padding=(12, 10))
        header.pack(fill="x")
        ttk.Label(header, text="Comparison Cards", style="Title.TLabel").pack(side="left")
        ttk.Button(header, text="＋", style="Small.TButton", width=3, command=self.add_card).pack(side="right")

        self.cards_tree = ttk.Treeview(panel, show="tree", selectmode="browse")
        self.cards_tree.pack(fill="both", expand=True, padx=8)
        self.cards_tree.bind("<<TreeviewSelect>>", self._on_card_selected)

        tools = ttk.Frame(panel, style="Panel.TFrame", padding=8)
        tools.pack(fill="x")
        ttk.Button(tools, text="↑", style="Small.TButton", width=4, command=lambda: self.move_card(-1)).pack(side="left")
        ttk.Button(tools, text="↓", style="Small.TButton", width=4, command=lambda: self.move_card(1)).pack(side="left", padx=(5, 0))
        ttk.Button(tools, text="Duplicate", style="Small.TButton", command=self.duplicate_card).pack(side="left", padx=(8, 0))
        ttk.Button(tools, text="Delete", style="Danger.TButton", command=self.delete_card).pack(side="right")
        ttk.Label(panel, textvariable=self.duration_var, style="Muted.TLabel", padding=(12, 8)).pack(fill="x")

    def _make_editor_panel(self, parent: ttk.Panedwindow) -> None:
        panel = ttk.Frame(parent, style="Panel.TFrame", width=560)
        parent.add(panel, weight=2)
        ttk.Label(panel, text="Card Content", style="Title.TLabel", padding=(14, 12)).pack(anchor="w")

        canvas = tk.Canvas(panel, bg=PANEL, highlightthickness=0)
        scrollbar = ttk.Scrollbar(panel, orient="vertical", command=canvas.yview)
        self.editor_inner = ttk.Frame(canvas, style="Panel.TFrame", padding=(14, 4, 14, 16))
        inner_window = canvas.create_window((0, 0), window=self.editor_inner, anchor="nw")
        canvas.configure(yscrollcommand=scrollbar.set)
        canvas.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")
        self.editor_inner.bind("<Configure>", lambda _e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.bind("<Configure>", lambda e: canvas.itemconfigure(inner_window, width=e.width))

        self._label(self.editor_inner, "Title")
        self.title_entry = ttk.Entry(self.editor_inner, textvariable=self.title_var)
        self.title_entry.pack(fill="x", pady=(0, 10))

        self._label(self.editor_inner, "Value Badge")
        self.value_entry = ttk.Entry(self.editor_inner, textvariable=self.value_var)
        self.value_entry.pack(fill="x", pady=(0, 10))

        self._label(self.editor_inner, "Description")
        self.description_text = tk.Text(self.editor_inner, height=7, wrap="word", relief="solid", borderwidth=1, highlightthickness=0, font=("Sans", 10), fg=TEXT, bg="white", insertbackground=TEXT)
        self.description_text.pack(fill="x", pady=(0, 10))
        self.description_text.bind("<<Modified>>", self._on_description_modified)

        self._label(self.editor_inner, "Image")
        image_row = ttk.Frame(self.editor_inner, style="Panel.TFrame")
        image_row.pack(fill="x", pady=(0, 10))
        ttk.Entry(image_row, textvariable=self.image_var).pack(side="left", fill="x", expand=True)
        ttk.Button(image_row, text="Change Image…", style="Small.TButton", command=self.choose_image).pack(side="left", padx=(8, 0))
        ttk.Button(image_row, text="Clear", style="Small.TButton", command=lambda: self.image_var.set("")).pack(side="left", padx=(5, 0))

        transform_box = ttk.LabelFrame(self.editor_inner, text="Image Transform", padding=10)
        transform_box.pack(fill="x", pady=(0, 10))
        self._scale_row(transform_box, "Scale", self.image_scale_var, 0.2, 3.0, 0.05, 0)
        self._scale_row(transform_box, "Horizontal", self.image_x_var, -800, 800, 1, 1)
        self._scale_row(transform_box, "Vertical", self.image_y_var, -600, 600, 1, 2)
        self._scale_row(transform_box, "Rotation", self.image_rotation_var, -180, 180, 1, 3)
        ttk.Label(transform_box, text="Layer", style="Body.TLabel").grid(row=4, column=0, sticky="w", pady=4)
        ttk.Combobox(transform_box, textvariable=self.image_layer_var, values=("behind", "front"), state="readonly", width=14).grid(row=4, column=1, sticky="ew", pady=4)
        transform_box.columnconfigure(1, weight=1)
        ttk.Button(transform_box, text="Reset transform", style="Small.TButton", command=self.reset_transform).grid(row=5, column=1, sticky="e", pady=(7, 0))

        for variable in (self.title_var, self.value_var, self.image_var, self.image_scale_var, self.image_x_var, self.image_y_var, self.image_rotation_var, self.image_layer_var):
            variable.trace_add("write", self._on_field_var)

    def _make_preview_panel(self, parent: ttk.Panedwindow) -> None:
        panel = ttk.Frame(parent, style="Panel.TFrame", width=500)
        parent.add(panel, weight=2)
        header = ttk.Frame(panel, style="Panel.TFrame", padding=(14, 10))
        header.pack(fill="x")
        ttk.Label(header, text="Live Preview", style="Title.TLabel").pack(side="left")
        ttk.Label(header, textvariable=self.preview_frame_var, style="Muted.TLabel").pack(side="right")

        preview_frame = tk.Frame(panel, bg=PREVIEW_BG, padx=14, pady=14)
        preview_frame.pack(fill="both", expand=True, padx=12, pady=(0, 12))
        self.preview_label = tk.Label(preview_frame, bg=PREVIEW_BG, fg="white", text="Rendering preview…", font=("Sans", 12))
        self.preview_label.pack(fill="both", expand=True)
        self.preview_label.bind("<Configure>", lambda _e: self._schedule_preview(150))

    def _make_lower_panel(self, parent: ttk.Frame) -> None:
        notebook = ttk.Notebook(parent)
        notebook.pack(fill="both", expand=True)

        imported = ttk.Frame(notebook, style="Panel.TFrame", padding=6)
        settings = ttk.Frame(notebook, style="Panel.TFrame", padding=12)
        soundtrack = ttk.Frame(notebook, style="Panel.TFrame", padding=12)
        appearance = ttk.Frame(notebook, style="Panel.TFrame", padding=12)
        credits = ttk.Frame(notebook, style="Panel.TFrame", padding=12)
        notebook.add(imported, text="Imported Data")
        notebook.add(settings, text="Model & Export")
        notebook.add(soundtrack, text="Soundtrack")
        notebook.add(appearance, text="Fonts & Images")
        notebook.add(credits, text="Credits")

        source_row = ttk.Frame(imported, style="Panel.TFrame", padding=(4, 2, 4, 7))
        source_row.pack(fill="x")
        ttk.Label(source_row, text="Data Source:", style="Muted.TLabel").pack(side="left")
        ttk.Label(source_row, textvariable=self.source_var, style="Body.TLabel").pack(side="left", padx=(6, 0))
        ttk.Button(source_row, text="Refresh", style="Small.TButton", command=self._refresh_imported_table).pack(side="right")

        self.data_tree = ttk.Treeview(imported, columns=("title", "value", "description", "image"), show="headings", height=7)
        for key, heading, width in (("title", "Title", 180), ("value", "Value", 100), ("description", "Description", 360), ("image", "Image", 260)):
            self.data_tree.heading(key, text=heading)
            self.data_tree.column(key, width=width, stretch=True)
        self.data_tree.pack(fill="both", expand=True)

        self._form_entry(settings, "Project name", self.project_name_var, 0)
        ttk.Label(settings, text="Official model", style="Body.TLabel").grid(row=1, column=0, sticky="w", padx=(0, 12), pady=5)
        self.model_combo = ttk.Combobox(settings, textvariable=self.model_var, values=tuple(MODEL_BY_NAME), state="readonly", width=42)
        self.model_combo.grid(row=1, column=1, sticky="ew", pady=5)
        self.model_combo.bind("<<ComboboxSelected>>", self._on_model_changed)
        ttk.Label(settings, text="Output", style="Body.TLabel").grid(row=2, column=0, sticky="w", padx=(0, 12), pady=5)
        ttk.Label(settings, text="1920 × 1080 · 60 FPS · locked to reference", style="Muted.TLabel").grid(row=2, column=1, sticky="w", pady=5)
        ttk.Label(settings, text="Encoder quality", style="Body.TLabel").grid(row=3, column=0, sticky="w", padx=(0, 12), pady=5)
        self.crf_var = tk.IntVar(value=18)
        ttk.Spinbox(settings, from_=12, to=32, textvariable=self.crf_var, width=8, command=self._settings_changed).grid(row=3, column=1, sticky="w", pady=5)
        ttk.Label(settings, text="Lower values produce higher quality and larger files.", style="Muted.TLabel").grid(row=4, column=1, sticky="w")
        ttk.Label(settings, text="Encoder preset", style="Body.TLabel").grid(row=5, column=0, sticky="w", padx=(0, 12), pady=5)
        ttk.Combobox(settings, textvariable=self.encoder_preset_var, values=ENCODER_PRESETS, state="readonly", width=18).grid(row=5, column=1, sticky="w", pady=5)
        settings.columnconfigure(1, weight=1)
        self.project_name_var.trace_add("write", self._on_project_name)
        self.crf_var.trace_add("write", lambda *_: self._settings_changed())
        self.encoder_preset_var.trace_add("write", lambda *_: self._settings_changed())

        self._form_entry(soundtrack, "Audio file", self.soundtrack_var, 0, browse=self.choose_soundtrack)
        ttk.Label(soundtrack, text="Volume", style="Body.TLabel").grid(row=1, column=0, sticky="w", padx=(0, 12), pady=7)
        ttk.Scale(soundtrack, from_=0, to=100, variable=self.soundtrack_volume_var, command=lambda _v: self._soundtrack_changed()).grid(row=1, column=1, sticky="ew", pady=7)
        self.volume_label = ttk.Label(soundtrack, text="75%", style="Muted.TLabel")
        self.volume_label.grid(row=1, column=2, padx=(8, 0))
        ttk.Checkbutton(soundtrack, text="Loop soundtrack to the full video", variable=self.soundtrack_loop_var, command=self._soundtrack_changed).grid(row=2, column=1, sticky="w", pady=7)
        ttk.Label(soundtrack, text="Start offset (s)", style="Body.TLabel").grid(row=3, column=0, sticky="w", padx=(0, 12), pady=7)
        ttk.Spinbox(soundtrack, from_=0, to=36000, increment=0.1, textvariable=self.soundtrack_offset_var, width=10).grid(row=3, column=1, sticky="w", pady=7)
        ttk.Label(soundtrack, text="Fade-out (s)", style="Body.TLabel").grid(row=4, column=0, sticky="w", padx=(0, 12), pady=7)
        ttk.Spinbox(soundtrack, from_=0, to=120, increment=0.1, textvariable=self.soundtrack_fade_var, width=10).grid(row=4, column=1, sticky="w", pady=7)
        soundtrack.columnconfigure(1, weight=1)
        self.soundtrack_var.trace_add("write", lambda *_: self._soundtrack_changed())
        self.soundtrack_offset_var.trace_add("write", lambda *_: self._soundtrack_changed())
        self.soundtrack_fade_var.trace_add("write", lambda *_: self._soundtrack_changed())

        ttk.Label(appearance, text="Image fit", style="Body.TLabel").grid(row=0, column=0, sticky="w", padx=(0, 12), pady=5)
        ttk.Combobox(appearance, textvariable=self.image_fit_var, values=("cover", "contain"), state="readonly", width=18).grid(row=0, column=1, sticky="w", pady=5)
        self._form_entry(appearance, "Title font", self.font_title_var, 1, browse=lambda: self.choose_font(self.font_title_var))
        self._form_entry(appearance, "Description font", self.font_description_var, 2, browse=lambda: self.choose_font(self.font_description_var))
        self._form_entry(appearance, "Badge font", self.font_badge_var, 3, browse=lambda: self.choose_font(self.font_badge_var))
        self._form_entry(appearance, "Credits font", self.font_credits_var, 4, browse=lambda: self.choose_font(self.font_credits_var))
        appearance.columnconfigure(1, weight=1)
        for variable in (self.image_fit_var, self.font_title_var, self.font_description_var, self.font_badge_var, self.font_credits_var):
            variable.trace_add("write", lambda *_: self._appearance_changed())

        ttk.Checkbutton(credits, text="Show opening credits", variable=self.credits_enabled_var, command=self._credits_changed).grid(row=0, column=0, columnspan=2, sticky="w", pady=(0, 8))
        self._form_entry(credits, "Project credit", self.credits_project_var, 1)
        self._form_entry(credits, "Top note", self.credits_top_var, 2)
        self._form_entry(credits, "End-screen credit", self.end_credit_var, 3)
        credits.columnconfigure(1, weight=1)
        for variable in (self.credits_project_var, self.credits_top_var, self.end_credit_var):
            variable.trace_add("write", lambda *_: self._credits_changed())

    @staticmethod
    def _label(parent: tk.Widget, text: str) -> None:
        ttk.Label(parent, text=text, style="Body.TLabel").pack(anchor="w", pady=(1, 4))

    def _form_entry(self, parent: ttk.Frame, label: str, variable: tk.Variable, row: int, browse=None) -> None:
        ttk.Label(parent, text=label, style="Body.TLabel").grid(row=row, column=0, sticky="w", padx=(0, 12), pady=5)
        entry = ttk.Entry(parent, textvariable=variable)
        entry.grid(row=row, column=1, sticky="ew", pady=5)
        if browse is not None:
            ttk.Button(parent, text="Browse…", style="Small.TButton", command=browse).grid(row=row, column=2, padx=(8, 0), pady=5)
        parent.columnconfigure(1, weight=1)

    def _scale_row(self, parent: ttk.LabelFrame, text: str, variable: tk.DoubleVar, low: float, high: float, resolution: float, row: int) -> None:
        ttk.Label(parent, text=text, style="Body.TLabel").grid(row=row, column=0, sticky="w", pady=4)
        scale = ttk.Scale(parent, from_=low, to=high, variable=variable)
        scale.grid(row=row, column=1, sticky="ew", padx=(8, 8), pady=4)
        spin = ttk.Spinbox(parent, from_=low, to=high, increment=resolution, textvariable=variable, width=8)
        spin.grid(row=row, column=2, pady=4)

    def _bind_shortcuts(self) -> None:
        self.bind_all("<Control-n>", lambda _e: self.new_project())
        self.bind_all("<Control-o>", lambda _e: self.open_project())
        self.bind_all("<Control-s>", lambda _e: self.save_project())
        self.bind_all("<Control-Shift-S>", lambda _e: self.save_project_as())
        self.bind_all("<Control-i>", lambda _e: self.import_data())
        self.bind_all("<Control-e>", lambda _e: self.export_video())
        self.bind_all("<Delete>", lambda _e: self.delete_card())

    def _on_field_var(self, *_args) -> None:
        if self.loading_fields:
            return
        if self.field_after:
            self.after_cancel(self.field_after)
        self.field_after = self.after(100, self._commit_fields)

    def _on_description_modified(self, _event=None) -> None:
        if self.loading_fields:
            self.description_text.edit_modified(False)
            return
        if self.description_text.edit_modified():
            self.description_text.edit_modified(False)
            self._on_field_var()

    def _commit_fields(self) -> None:
        self.field_after = None
        if not (0 <= self.selected_index < len(self.project.cards)):
            return
        card = self.project.cards[self.selected_index]
        card.title = self.title_var.get().strip()
        card.value = self.value_var.get().strip()
        card.description = self.description_text.get("1.0", "end-1c")
        card.image = self.image_var.get().strip()
        card.image_scale = max(0.05, float(self.image_scale_var.get()))
        card.image_x = float(self.image_x_var.get())
        card.image_y = float(self.image_y_var.get())
        card.image_rotation = float(self.image_rotation_var.get())
        card.image_layer = "front" if self.image_layer_var.get() == "front" else "behind"
        self._mark_dirty()
        self._refresh_card_tree(keep_selection=True)
        self._refresh_imported_table()
        self._schedule_preview(250)

    def _mark_dirty(self) -> None:
        self.dirty = True
        self._update_title()

    def _update_title(self) -> None:
        name = self.project.name or "Untitled Comparison"
        marker = " •" if self.dirty else ""
        self.title(f"{name}{marker} — Cubical Compare {VERSION}")

    def _refresh_all(self) -> None:
        normalize_project(self.project)
        previous_loading = self.loading_fields
        self.loading_fields = True
        try:
            self.project_name_var.set(self.project.name)
            self.model_var.set(NAME_BY_MODEL.get(self.project.settings.model_id, self.project.settings.model_id))
            self.crf_var.set(self.project.settings.encoder_crf)
            self.credits_enabled_var.set(self.project.settings.credits_enabled)
            self.credits_project_var.set(self.project.settings.credits_project_name)
            self.credits_top_var.set(self.project.settings.credits_top_text)
            self.end_credit_var.set(self.project.settings.end_credit_value)
            self.soundtrack_var.set(self.project.settings.soundtrack)
            self.soundtrack_volume_var.set(self.project.settings.soundtrack_volume * 100)
            self.soundtrack_loop_var.set(self.project.settings.soundtrack_loop)
            self.soundtrack_offset_var.set(self.project.settings.soundtrack_offset_seconds)
            self.soundtrack_fade_var.set(self.project.settings.soundtrack_fade_out_seconds)
            self.encoder_preset_var.set(self.project.settings.encoder_preset)
            self.image_fit_var.set(self.project.settings.image_fit_mode)
            self.font_title_var.set(self.project.settings.font_title)
            self.font_description_var.set(self.project.settings.font_description)
            self.font_badge_var.set(self.project.settings.font_badge)
            self.font_credits_var.set(self.project.settings.font_credits)
            self.volume_label.configure(text=f"{int(self.project.settings.soundtrack_volume * 100)}%")
        finally:
            self.loading_fields = previous_loading
        self._refresh_card_tree()
        self._load_card_fields()
        self._refresh_imported_table()
        self._refresh_duration()
        self._update_title()
        self._schedule_preview(80)

    def _refresh_card_tree(self, keep_selection: bool = False) -> None:
        selected = self.selected_index if keep_selection else min(self.selected_index, max(0, len(self.project.cards) - 1))
        for item in self.cards_tree.get_children():
            self.cards_tree.delete(item)
        for index, card in enumerate(self.project.cards):
            title = card.title or f"Card {index + 1}"
            subtitle = card.description.replace("\n", " ").strip()
            if len(subtitle) > 44:
                subtitle = subtitle[:41] + "…"
            text = f"{index + 1}.  {title}"
            if card.value:
                text += f"    [{card.value}]"
            if subtitle:
                text += f"\n     {subtitle}"
            self.cards_tree.insert("", "end", iid=str(index), text=text)
        if self.project.cards:
            self.selected_index = max(0, min(selected, len(self.project.cards) - 1))
            self.cards_tree.selection_set(str(self.selected_index))
            self.cards_tree.see(str(self.selected_index))

    def _on_card_selected(self, _event=None) -> None:
        selection = self.cards_tree.selection()
        if not selection:
            return
        try:
            index = int(selection[0])
        except ValueError:
            return
        if index == self.selected_index:
            return
        self._commit_fields()
        self.selected_index = index
        self._load_card_fields()
        self._schedule_preview(40)

    def _load_card_fields(self) -> None:
        self.loading_fields = True
        try:
            if not self.project.cards:
                for var in (self.title_var, self.value_var, self.image_var):
                    var.set("")
                self.description_text.delete("1.0", "end")
                return
            card = self.project.cards[self.selected_index]
            self.title_var.set(card.title)
            self.value_var.set(card.value)
            self.image_var.set(card.image)
            self.description_text.delete("1.0", "end")
            self.description_text.insert("1.0", card.description)
            self.description_text.edit_modified(False)
            self.image_scale_var.set(card.image_scale)
            self.image_x_var.set(card.image_x)
            self.image_y_var.set(card.image_y)
            self.image_rotation_var.set(card.image_rotation)
            self.image_layer_var.set(card.image_layer)
        finally:
            self.loading_fields = False

    def _refresh_imported_table(self) -> None:
        for item in self.data_tree.get_children():
            self.data_tree.delete(item)
        for index, card in enumerate(self.project.cards):
            self.data_tree.insert("", "end", iid=f"data-{index}", values=(card.title, card.value, card.description.replace("\n", " · "), card.image))
        self.source_var.set(self.source_var.get() if self.source_var.get() != "No imported data" else f"Current project · {len(self.project.cards)} rows")

    def _refresh_duration(self) -> None:
        frames = total_frame_count(self.project) if self.project.cards else 0
        seconds = total_duration(self.project) if self.project.cards else 0.0
        minutes, sec = divmod(int(round(seconds)), 60)
        self.duration_var.set(f"{len(self.project.cards)} cards · {minutes:02d}:{sec:02d} total · {frames:,} frames")

    def _schedule_preview(self, delay: int = 250) -> None:
        if self.preview_after:
            self.after_cancel(self.preview_after)
        self.preview_after = self.after(delay, self.render_preview)

    def _preview_frame(self) -> int:
        if not self.project.cards:
            return 0
        starts = card_start_frames(self.project)
        index = min(self.selected_index, len(starts) - 1)
        start = starts[index]
        end = starts[index + 1] if index + 1 < len(starts) else max(start + 1, total_frame_count(self.project))
        # The card body has settled by roughly the middle of its cadence.
        return min(end - 1, start + max(1, min(96, (end - start) // 2)))

    def render_preview(self) -> None:
        self.preview_after = None
        self._commit_fields()
        try:
            width = max(320, self.preview_label.winfo_width() - 8)
            height = max(180, self.preview_label.winfo_height() - 8)
            ratio = 16 / 9
            if width / height > ratio:
                width = int(height * ratio)
            else:
                height = int(width / ratio)
            width = min(width, 960)
            height = min(height, 540)
            frame = self._preview_frame()
            seconds = frame / self.project.settings.fps
            image = self.renderer.render(self.project, seconds, (width, height))
            self.preview_photo = ImageTk.PhotoImage(image)
            self.preview_label.configure(image=self.preview_photo, text="")
            self.preview_frame_var.set(f"Frame {frame:,} · {seconds:.2f}s")
            self.status_var.set("Preview updated")
        except Exception as exc:
            self.preview_label.configure(image="", text=f"Preview failed\n{exc}")
            self.status_var.set(f"Preview failed: {exc}")

    def add_card(self) -> None:
        self._commit_fields()
        self.project.cards.append(Card(title=f"Card {len(self.project.cards) + 1}", value=str(len(self.project.cards) + 1)))
        self.selected_index = len(self.project.cards) - 1
        self._mark_dirty()
        self._refresh_all()

    def duplicate_card(self) -> None:
        if not self.project.cards:
            return
        self._commit_fields()
        card = self.project.cards[self.selected_index]
        duplicate = Card.from_mapping({
            "title": f"{card.title} Copy",
            "value": card.value,
            "description": card.description,
            "image": card.image,
            "image_x": card.image_x,
            "image_y": card.image_y,
            "image_scale": card.image_scale,
            "image_rotation": card.image_rotation,
            "image_layer": card.image_layer,
        })
        self.project.cards.insert(self.selected_index + 1, duplicate)
        self.selected_index += 1
        self._mark_dirty()
        self._refresh_all()

    def delete_card(self) -> None:
        if not self.project.cards:
            return
        title = self.project.cards[self.selected_index].title or f"Card {self.selected_index + 1}"
        if not messagebox.askyesno("Delete card", f"Delete “{title}”?", parent=self):
            return
        del self.project.cards[self.selected_index]
        if not self.project.cards:
            self.project.cards.append(Card(title="Card 1", value="1"))
        self.selected_index = min(self.selected_index, len(self.project.cards) - 1)
        self._mark_dirty()
        self._refresh_all()

    def move_card(self, offset: int) -> None:
        if not self.project.cards:
            return
        target = self.selected_index + offset
        if target < 0 or target >= len(self.project.cards):
            return
        self._commit_fields()
        self.project.cards[self.selected_index], self.project.cards[target] = self.project.cards[target], self.project.cards[self.selected_index]
        self.selected_index = target
        self._mark_dirty()
        self._refresh_all()

    def reset_transform(self) -> None:
        self.loading_fields = True
        try:
            self.image_scale_var.set(1.0)
            self.image_x_var.set(0.0)
            self.image_y_var.set(0.0)
            self.image_rotation_var.set(0.0)
            self.image_layer_var.set("behind")
        finally:
            self.loading_fields = False
        self._commit_fields()

    def choose_image(self) -> None:
        path = filedialog.askopenfilename(parent=self, title="Choose card image", filetypes=[("Images", "*.png *.jpg *.jpeg *.webp *.bmp *.gif"), ("All files", "*")])
        if path:
            self.image_var.set(path)

    def choose_soundtrack(self) -> None:
        path = filedialog.askopenfilename(parent=self, title="Choose soundtrack", filetypes=[("Audio", "*.mp3 *.wav *.m4a *.aac *.ogg *.flac"), ("All files", "*")])
        if path:
            self.soundtrack_var.set(path)

    def choose_font(self, variable: tk.StringVar) -> None:
        path = filedialog.askopenfilename(parent=self, title="Choose font", filetypes=[("Font files", "*.ttf *.otf *.ttc"), ("All files", "*")])
        if path:
            variable.set(path)

    def _on_project_name(self, *_args) -> None:
        if self.loading_fields:
            return
        self.project.name = self.project_name_var.get().strip()
        self._mark_dirty()

    def _settings_changed(self) -> None:
        if self.loading_fields:
            return
        try:
            self.project.settings.encoder_crf = int(self.crf_var.get())
        except (ValueError, tk.TclError):
            return
        preset = self.encoder_preset_var.get().strip().lower()
        if preset in ENCODER_PRESETS:
            self.project.settings.encoder_preset = preset
        self._mark_dirty()

    def _soundtrack_changed(self) -> None:
        if self.loading_fields:
            return
        try:
            volume = float(self.soundtrack_volume_var.get())
            offset = float(self.soundtrack_offset_var.get())
            fade = float(self.soundtrack_fade_var.get())
        except (ValueError, tk.TclError):
            return
        self.project.settings.soundtrack = self.soundtrack_var.get().strip()
        self.project.settings.soundtrack_volume = max(0.0, min(1.0, volume / 100.0))
        self.project.settings.soundtrack_loop = bool(self.soundtrack_loop_var.get())
        self.project.settings.soundtrack_offset_seconds = max(0.0, offset)
        self.project.settings.soundtrack_fade_out_seconds = max(0.0, fade)
        self.volume_label.configure(text=f"{int(volume)}%")
        self._mark_dirty()

    def _appearance_changed(self) -> None:
        if self.loading_fields:
            return
        settings = self.project.settings
        settings.image_fit_mode = "contain" if self.image_fit_var.get() == "contain" else "cover"
        settings.font_title = self.font_title_var.get().strip()
        settings.font_description = self.font_description_var.get().strip()
        settings.font_badge = self.font_badge_var.get().strip()
        settings.font_credits = self.font_credits_var.get().strip()
        self.renderer = FrameRenderer()
        self._mark_dirty()
        self._schedule_preview(250)

    def _credits_changed(self) -> None:
        if self.loading_fields:
            return
        s = self.project.settings
        s.credits_enabled = bool(self.credits_enabled_var.get())
        s.credits_project_name = self.credits_project_var.get()
        s.credits_top_text = self.credits_top_var.get()
        s.end_credit_value = self.end_credit_var.get()
        self._mark_dirty()
        self._schedule_preview(250)

    def _on_model_changed(self, _event=None) -> None:
        model_id = MODEL_BY_NAME.get(self.model_var.get())
        if not model_id or model_id == self.project.settings.model_id:
            return
        model = get_model(model_id)
        s = self.project.settings
        s.model_id = model.id
        s.model_revision = model.revision
        s.width, s.height, s.fps = model.width, model.height, model.fps
        normalize_project(self.project)
        self._mark_dirty()
        self._refresh_duration()
        self._schedule_preview(50)

    def new_project(self) -> None:
        if not self._confirm_discard():
            return
        model_name = simpledialog.askstring(
            "New comparison",
            "Official model:\n\n1 — What Males Learn At Each Age\n2 — Types Of Relationships",
            initialvalue="1",
            parent=self,
        )
        if model_name is None:
            return
        model_id = "types-of-relationships" if model_name.strip().lower() in {"2", "relationships", "types of relationships"} else "what-males-learn-at-each-age"
        self.project = self._new_project(model_id)
        self.project_path = None
        self.selected_index = 0
        self.source_var.set("No imported data")
        self.dirty = False
        self._refresh_all()

    def _confirm_discard(self) -> bool:
        if not self.dirty:
            return True
        answer = messagebox.askyesnocancel("Unsaved changes", "Save the current comparison before continuing?", parent=self)
        if answer is None:
            return False
        if answer:
            return bool(self.save_project())
        return True

    def open_project(self) -> None:
        if not self._confirm_discard():
            return
        path = filedialog.askopenfilename(parent=self, title="Open Cubical Compare project", filetypes=[("Cubical Compare", "*.ccx *.json *.cubical"), ("All files", "*")])
        if not path:
            return
        try:
            source = Path(path)
            project = read_ccx(source) if source.suffix.lower() == ".ccx" else Project.load(source)
            self.project = project
            self.project_path = source
            self.selected_index = 0
            self.source_var.set(f"Project: {source.name}")
            self.dirty = False
            self._refresh_all()
            self.status_var.set(f"Opened {source.name}")
        except Exception as exc:
            messagebox.showerror("Could not open project", str(exc), parent=self)

    def save_project(self) -> bool:
        if self.project_path is None:
            return self.save_project_as()
        return self._save_to(self.project_path)

    def save_project_as(self) -> bool:
        path = filedialog.asksaveasfilename(parent=self, title="Save Cubical Compare project", defaultextension=".ccx", filetypes=[("Cubical Compare project", "*.ccx"), ("JSON project", "*.json")])
        if not path:
            return False
        return self._save_to(Path(path))

    def _save_to(self, path: Path) -> bool:
        self._commit_fields()
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            # Always save a portable copy so local images, soundtrack files and
            # custom fonts travel with the project instead of depending on old
            # absolute paths.  The editable in-memory project keeps its resolved
            # paths; only the serialized copy is made relative.
            portable = collect_project_assets(self.project, path)
            if path.suffix.lower() == ".ccx":
                write_ccx(portable, path)
            else:
                portable.save(path)
            self.project_path = path
            self.dirty = False
            self._update_title()
            self.status_var.set(f"Saved {path.name} with portable assets")
            return True
        except Exception as exc:
            messagebox.showerror("Could not save project", str(exc), parent=self)
            return False

    def import_data(self) -> None:
        if self.data_import_active:
            return
        path = filedialog.askopenfilename(parent=self, title="Import comparison data", filetypes=[("Spreadsheet data", "*.csv *.xlsx *.xlsm"), ("CSV", "*.csv"), ("Excel", "*.xlsx *.xlsm"), ("All files", "*")])
        if not path:
            return
        self.data_import_active = True
        self.import_button.state(["disabled"])
        self.status_var.set(f"Importing {Path(path).name}…")

        def worker() -> None:
            try:
                cards = load_spreadsheet(path)
                self.worker_events.put(("data_done", (cards, Path(path).name)))
            except Exception as exc:
                self.worker_events.put(("data_error", (exc, traceback.format_exc())))

        threading.Thread(target=worker, daemon=True).start()

    def _finish_data_import(self) -> None:
        self.data_import_active = False
        self.import_button.state(["!disabled"])

    def paste_data(self) -> None:
        dialog = tk.Toplevel(self)
        dialog.title("Paste comparison data")
        dialog.geometry("760x470")
        dialog.transient(self)
        dialog.grab_set()
        ttk.Label(dialog, text="Paste CSV, tab-separated, semicolon-separated, or pipe-separated rows.", padding=(14, 12)).pack(anchor="w")
        text = tk.Text(dialog, wrap="none", font=("Monospace", 10))
        text.pack(fill="both", expand=True, padx=14)
        text.insert("1.0", "title,value,description,image\nCard 1,1,Description,\n")
        buttons = ttk.Frame(dialog, padding=14)
        buttons.pack(fill="x")

        def apply() -> None:
            try:
                cards = parse_pasted_data(text.get("1.0", "end-1c"))
                self._apply_import(cards, "Pasted data")
                dialog.destroy()
            except Exception as exc:
                messagebox.showerror("Import failed", str(exc), parent=dialog)

        ttk.Button(buttons, text="Cancel", style="Toolbar.TButton", command=dialog.destroy).pack(side="right")
        ttk.Button(buttons, text="Import", style="Primary.TButton", command=apply).pack(side="right", padx=(0, 8))

    def _apply_import(self, cards: list[Card], source: str) -> None:
        if not cards:
            raise ValueError("No non-empty rows were found.")
        append = False
        if self.project.cards and not (len(self.project.cards) == 1 and self.project.cards[0].title == "Card 1" and not self.project.cards[0].description):
            append = messagebox.askyesno("Import data", "Append imported cards to the current project?\n\nChoose No to replace the current cards.", parent=self)
        if append:
            self.project.cards.extend(cards)
            self.selected_index = len(self.project.cards) - len(cards)
        else:
            self.project.cards = cards
            self.selected_index = 0
        self.source_var.set(f"{source} · {len(cards)} rows")
        self._mark_dirty()
        self._refresh_all()
        self.status_var.set(f"Imported {len(cards)} cards")

    def _working_asset_root(self) -> Path:
        if self.project_path is not None:
            root = self.project_path.parent / f"{self.project_path.stem}_assets" / "image-sheets"
        elif os.name == "nt":
            local = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
            root = local / "Cubical Compare" / "working-assets"
        else:
            data_home = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share"))
            root = data_home / "Cubical Compare" / "working-assets"
        root.mkdir(parents=True, exist_ok=True)
        return root

    def import_image_sheet(self) -> None:
        if self.sheet_import_active or self.exporter is not None:
            return
        if not self.project.cards:
            self.project.cards.append(Card(title="Card 1", value="1"))
            self.selected_index = 0

        sheet = filedialog.askopenfilename(
            parent=self,
            title="Import image sheet",
            filetypes=[
                ("Image files", "*.png *.jpg *.jpeg *.webp *.bmp *.tif *.tiff"),
                ("All files", "*"),
            ],
        )
        if not sheet:
            return

        start_one_based = simpledialog.askinteger(
            "Image Sheet",
            f"Start assigning images at which card?\n\n1 to {len(self.project.cards)}",
            parent=self,
            initialvalue=min(len(self.project.cards), self.selected_index + 1),
            minvalue=1,
            maxvalue=max(1, len(self.project.cards)),
        )
        if start_one_based is None:
            return
        start = start_one_based - 1
        assets = self._working_asset_root() / f"sheet-{uuid.uuid4().hex[:12]}"
        assets.mkdir(parents=True, exist_ok=True)

        self.sheet_cancel.clear()
        self.sheet_import_active = True
        self.image_sheet_button.state(["disabled"])
        self.progress_var.set(0)
        self.cancel_button.configure(text="Cancel image import")
        self.cancel_button.pack(side="right", padx=(8, 0))
        self.status_var.set("Analysing image sheet…")
        available = max(0, len(self.project.cards) - start)

        def worker() -> None:
            try:
                processor = ImageSheetProcessor.from_path(sheet, cancel_check=self.sheet_cancel.is_set)
                detection = processor.detect(preferred_count=max(1, available))
                if self.sheet_cancel.is_set():
                    self.worker_events.put(("sheet_cancelled", None))
                    return
                self.worker_events.put(("sheet_detected", (Path(sheet), start, assets, detection)))
            except RuntimeError as exc:
                if self.sheet_cancel.is_set() or "cancel" in str(exc).lower():
                    self.worker_events.put(("sheet_cancelled", None))
                else:
                    self.worker_events.put(("sheet_error", (exc, traceback.format_exc())))
            except Exception as exc:
                self.worker_events.put(("sheet_error", (exc, traceback.format_exc())))

        threading.Thread(target=worker, daemon=True).start()

    def _continue_image_sheet_import(self, sheet: Path, start: int, assets: Path, detection, create_extra: bool) -> None:
        # Snapshot the current cards so UI edits made after detection cannot race
        # with the crop worker. The result replaces the project only on success.
        cards = Project.from_dict(self.project.to_dict()).cards

        def progress(done: int, total: int) -> None:
            self.worker_events.put(("sheet_progress", (done, total)))

        def worker() -> None:
            try:
                updated, paths = export_sheet_crops(
                    sheet, assets, detection, cards,
                    start_card=start, create_missing=create_extra, fit_mode="cts_card",
                    target_size=(480, 830), progress=progress, cancel_check=self.sheet_cancel.is_set,
                )
                if self.sheet_cancel.is_set():
                    self.worker_events.put(("sheet_cancelled", None))
                    return
                self.worker_events.put(("sheet_done", (updated, paths, sheet.name, start)))
            except RuntimeError as exc:
                if self.sheet_cancel.is_set() or "cancel" in str(exc).lower():
                    self.worker_events.put(("sheet_cancelled", None))
                else:
                    self.worker_events.put(("sheet_error", (exc, traceback.format_exc())))
            except Exception as exc:
                self.worker_events.put(("sheet_error", (exc, traceback.format_exc())))

        self.status_var.set(f"Importing {detection.count} detected images…")
        threading.Thread(target=worker, daemon=True).start()

    def cancel_image_sheet_import(self) -> None:
        if not self.sheet_import_active:
            return
        self.sheet_cancel.set()
        self.status_var.set("Cancelling image import…")

    def export_video(self) -> None:
        if self.exporter is not None:
            return
        self._commit_fields()
        if not self.project.cards:
            messagebox.showwarning("Nothing to export", "Insert at least one card.", parent=self)
            return
        output = filedialog.asksaveasfilename(parent=self, title="Export MP4", defaultextension=".mp4", filetypes=[("MP4 video", "*.mp4")])
        if not output:
            return
        output_path = Path(output)
        project = Project.from_dict(self.project.to_dict())
        self.export_cancel.clear()
        self.exporter = VideoExporter()
        self.export_button.state(["disabled"])
        self.cancel_button.configure(text="Cancel export")
        self.cancel_button.pack(side="right", padx=(8, 0))
        self.progress_var.set(0)
        self.status_var.set("Preparing export…")

        def progress(done: int, total: int) -> None:
            self.worker_events.put(("progress", (done, total)))

        def worker() -> None:
            try:
                assert self.exporter is not None
                self.exporter.export(project, output_path, progress=progress, cancel_check=self.export_cancel.is_set)
                self.worker_events.put(("done", output_path))
            except ExportCancelled:
                self.worker_events.put(("cancelled", None))
            except Exception as exc:
                self.worker_events.put(("error", (exc, traceback.format_exc())))

        threading.Thread(target=worker, daemon=True).start()

    def cancel_export(self) -> None:
        self.export_cancel.set()
        if self.exporter is not None:
            self.exporter.cancel()
        self.status_var.set("Cancelling export…")

    def cancel_active_job(self) -> None:
        if self.exporter is not None:
            self.cancel_export()
        elif self.sheet_import_active:
            self.cancel_image_sheet_import()

    def _poll_worker_events(self) -> None:
        try:
            while True:
                kind, payload = self.worker_events.get_nowait()
                if kind == "data_done":
                    cards, source = payload  # type: ignore[misc]
                    self._finish_data_import()
                    try:
                        self._apply_import(cards, source)
                    except Exception as exc:
                        self.status_var.set(f"Import failed: {exc}")
                        messagebox.showerror("Import failed", str(exc), parent=self)
                elif kind == "data_error":
                    exc, details = payload  # type: ignore[misc]
                    self._finish_data_import()
                    self.status_var.set(f"Import failed: {exc}")
                    messagebox.showerror("Import failed", f"{exc}\n\n{details[-2500:]}", parent=self)
                elif kind == "sheet_detected":
                    sheet, start, assets, detection = payload  # type: ignore[misc]
                    available = max(0, len(self.project.cards) - start)
                    if detection.count > available:
                        answer = messagebox.askyesnocancel(
                            "Image Sheet",
                            f"Detected {detection.count} images ({detection.rows} × {detection.columns}).\n"
                            f"Only {available} existing cards remain from card {start + 1}.\n\n"
                            "Yes — create blank cards for the extra images\n"
                            "No — assign only to existing cards\n"
                            "Cancel — stop the import",
                            parent=self,
                        )
                        if answer is None:
                            self.sheet_cancel.set()
                            self._finish_image_sheet_import("Image import cancelled")
                            continue
                        create_extra = bool(answer)
                    else:
                        if not messagebox.askyesno(
                            "Image Sheet",
                            f"Detected {detection.count} images ({detection.rows} × {detection.columns}).\n\n"
                            f"Assign them starting at card {start + 1}?",
                            parent=self,
                        ):
                            self.sheet_cancel.set()
                            self._finish_image_sheet_import("Image import cancelled")
                            continue
                        create_extra = False
                    self._continue_image_sheet_import(sheet, start, assets, detection, create_extra)
                elif kind == "sheet_progress":
                    done, total = payload  # type: ignore[misc]
                    percent = 100 * done / max(1, total)
                    self.progress_var.set(percent)
                    self.status_var.set(f"Importing image {done} of {total} · {percent:.1f}%")
                elif kind == "sheet_done":
                    updated, paths, source_name, start = payload  # type: ignore[misc]
                    self.project.cards = updated
                    self.selected_index = min(start, max(0, len(self.project.cards) - 1))
                    self.source_var.set(f"Image sheet: {source_name} · {len(paths)} images")
                    self._mark_dirty()
                    self._refresh_all()
                    self.progress_var.set(100)
                    self._finish_image_sheet_import(f"Imported {len(paths)} images from {source_name}")
                elif kind == "sheet_cancelled":
                    self._finish_image_sheet_import("Image import cancelled")
                elif kind == "sheet_error":
                    exc, details = payload  # type: ignore[misc]
                    self._finish_image_sheet_import(f"Image import failed: {exc}")
                    messagebox.showerror("Image Sheet import failed", f"{exc}\n\n{details[-2500:]}", parent=self)
                elif kind == "progress":
                    done, total = payload  # type: ignore[misc]
                    percent = 100 * done / max(1, total)
                    self.progress_var.set(percent)
                    self.status_var.set(f"Exporting frame {done:,} of {total:,} · {percent:.1f}%")
                elif kind == "done":
                    path = payload
                    self.progress_var.set(100)
                    self.status_var.set(f"Exported {Path(path).name}")
                    messagebox.showinfo("Export complete", f"Video saved to:\n{path}", parent=self)
                    self._finish_export()
                elif kind == "cancelled":
                    self.status_var.set("Export cancelled")
                    self._finish_export()
                elif kind == "error":
                    exc, details = payload  # type: ignore[misc]
                    self.status_var.set(f"Export failed: {exc}")
                    messagebox.showerror("Export failed", f"{exc}\n\n{details[-2500:]}", parent=self)
                    self._finish_export()
        except queue.Empty:
            pass
        self.after(100, self._poll_worker_events)

    def _finish_image_sheet_import(self, status: str) -> None:
        self.sheet_import_active = False
        self.sheet_cancel.clear()
        self.image_sheet_button.state(["!disabled"])
        if self.exporter is None:
            self.cancel_button.pack_forget()
        self.status_var.set(status)

    def _finish_export(self) -> None:
        self.exporter = None
        self.export_cancel.clear()
        self.export_button.state(["!disabled"])
        self.cancel_button.pack_forget()

    def _on_close(self) -> None:
        if self.exporter is not None and not messagebox.askyesno("Export in progress", "Cancel the export and close Cubical Compare?", parent=self):
            return
        if self.sheet_import_active and not messagebox.askyesno("Image import in progress", "Cancel the image import and close Cubical Compare?", parent=self):
            return
        if self.exporter is not None:
            self.cancel_export()
        if self.sheet_import_active:
            self.cancel_image_sheet_import()
        if self._confirm_discard():
            self.destroy()

    def run_self_test(self, output: Path | None = None) -> int:
        self.update_idletasks()
        self.update()
        self.render_preview()
        self.update_idletasks()
        if output is not None:
            frame = self.renderer.render(self.project, self._preview_frame() / 60, (960, 540))
            frame.save(output)
        required = [
            "Comparison Cards", "Card Content", "Live Preview", "Imported Data",
            "Image Sheet", "Model & Export", "Soundtrack", "Fonts & Images", "What Males Learn At Each Age",
            "Types Of Relationships",
        ]
        print("Cubical Compare dashboard self-test OK")
        print("UI markers:", ", ".join(required))
        print("Model:", self.project.settings.model_id)
        print("Frames:", total_frame_count(self.project))
        self.destroy()
        return 0


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Cubical Compare desktop editor")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--preview-output", type=Path)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    app = DashboardApp(self_test=args.self_test)
    if args.self_test:
        app.after(200, lambda: app.run_self_test(args.preview_output))
    app.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
