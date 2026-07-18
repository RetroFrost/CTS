# CTS — Comparison Timeline Studio

![Version](https://img.shields.io/badge/version-0.4.5-6d55f7)
![Python](https://img.shields.io/badge/Python-3.10%2B-3776ab)
![Qt](https://img.shields.io/badge/UI-PySide6-41cd52)
![License](https://img.shields.io/badge/license-CC0-lightgrey)

CTS is a free desktop editor for creating continuously scrolling comparison videos.
Edit cards directly on the rendered preview, fill hundreds of cards from CSV/XLSX data,
add a soundtrack, and export a finished H.264/AAC MP4 through FFmpeg.

CTS 0.4.5 keeps the complete 0.3.5 editing engine and workflow, but presents it inside
an original professional editing-suite workspace with direct manipulation: project data and
tools stay together in a compact Project panel, while the rendered result gets a larger
Program Monitor with in-place editing and movable/resizable text and images.

The interface automatically uses a compact layout on 1366×768 laptops: the window fits
the available desktop area, the preview scales down without losing its 16:9 canvas, and
the Models panel scrolls when its advanced controls do not fit vertically.

![CTS visual models](docs/models-preview.png)

## Why CTS?

Comparison videos are visually repetitive but surprisingly annoying to build by hand.
CTS keeps the design consistent while making the content fast to edit:

- Use the prominent **Click to Insert Data** action to paste a complete comparison table.
- Use one **Import file** action for UTF-8 CSV or XLSX data.
- Click a badge, title, description, image, or artwork directly in the preview.
- Right-click text or images to move and resize them with four-corner transform handles.
- See transformed content update while dragging and select it again at its new position.
- Paste a copied image URL straight into a card from its image menu.
- Switch between three built-in visual models without rebuilding the project.
- Choose a project-wide output font from the fonts installed on the system.
- Resize images manually in every visual model.
- Choose Beach or five additional built-in Illustrated Cards backgrounds.
- Resize Illustrated hexagons manually or let typed text adjust artwork and badge sizing automatically.
- Hide hexagons globally and let each model reflow its remaining content into the freed space.
- Split one large image strip into card artwork using divider detection.
- Layer, trim, delay, loop, fade, and mix multiple soundtrack files for export.
- Preview the exact deterministic renderer used for export.
- Export with visible stage progress, frame count, percentage, ETA, and cancellation.
- Receive readable errors instead of raw encoder or spreadsheet failures.

## Visual models

Each model prepares only the fields its layout actually uses. Cells may remain blank;
CTS never invents card content.

| Model | Prepared fields | Native viewport |
| --- | --- | ---: |
| Reference Detail | Badge Date / Value, Title, Description, Image | 4 cards |
| Illustrated Cards | Badge Value, Badge Label, Title, Artwork | 3 cards |
| Classic Compact | Value, Unit, Title, Image | 4 cards |

Switching models migrates compatible values and preserves non-empty extra spreadsheet
fields. The in-app field guide explains exactly where every value appears.
Long badge strings shrink and wrap safely, while ordinary words stay intact whenever the
available model width can accommodate them.

## 0.4.5 visual controls

The Models panel includes visual settings that affect both the Program Monitor and MP4
export:

- **Font** selects from the fonts installed on the computer, with CTS Default as the safe fallback.
- **Illustrated background** offers Beach, Sunset, Forest, Lavender, Night, and Blueprint Grid.
- **Image scale** manually zooms artwork from 50% to 200% in every model.
- **Illustrated hexagon** manually scales the red badge from 60% to 160%.
- **Auto-size artwork and hexagon from typed value** gives longer badge text more room and makes a small compensating artwork adjustment.
- **Show hexagons** controls badge visibility across every model and switches to a model-specific no-badge composition instead of leaving empty space.

Illustrated Cards use a subtle depth treatment on both the red hexagon and white title bar.
Transparent PNG/WebP artwork keeps its alpha, so a selected Illustrated background can
remain visible behind the character or object. Opaque full-card artwork still covers the
background at 100% scale, matching previous behavior.

These visual settings are saved in `.cts.json` project files. Older 0.3.5 projects open
with safe defaults: CTS Default font, Beach background, 100% image/hexagon scale, and
visible hexagons.

## Direct editing and transforms

The Program Monitor is the main visual editor. Text editing is truly in-place: the
rendered value is temporarily removed and a borderless caret appears inside that exact
visual region—no floating input box. The active text and underline automatically switch
between vivid cyan and deep violet so the typing indicator remains visible over dark or
light fields.

- Left-click the red badge to edit its large value or smaller label/unit.
- Left-click the white strip to edit the title.
- Left-click the muted panel in Reference Detail to edit the description.
- Left-click an image area to choose a file, paste an image URL, type a local path/URL, or clear it.
- Right-click visible text or an image to open its object menu.
- Choose **Transform text box** or **Transform image**, then drag inside the purple box to move it.
- Drag any of the four corner handles to resize while the content updates live.
- Press **Esc**, click outside the selected box, click the black monitor margin, or choose **Deselect object** to leave transform mode.
- Moved text and images remain selectable at their transformed positions.
- Choose **Reset position and size** to restore the selected object to the model default.
- Press **Enter** to apply an inline edit or **Esc** to cancel it.
- Click **Add card** beside playback to create and reveal another card.

Transform overrides are stored per card in CTS project files and used by both Program
Monitor rendering and MP4 export.

The **Badge reveal** checkbox lives in the Program Monitor's **Sequence** row. With it
enabled, each new red badge fades in after its card slides into the right-most slot, like
the reference video. Badge geometry stays fixed while the strip moves. Disable it to show
badges immediately. The setting is saved per project.

Hit-testing follows the real animated positions, including partially scrolled cards and
objects moved away from their original model regions. Every visual edit updates the
underlying spreadsheet table immediately.

## Spreadsheet and image-strip workflow

One table row is one card. Use **Click to Insert Data** to paste a complete table, type
into the grid, paste individual cells, or use **Import file** with CSV/XLSX data.
Recognizable fields are mapped automatically; unusual headers can be assigned from
**Models → Advanced mapping** or by right-clicking a field.

CSV import supports UTF-8 (including BOM-marked files) and quoted fields containing
commas. XLSX import continues to support active-sheet data, workbook-relative paths, and
embedded images.

Images may be:

- absolute or workbook-relative local paths;
- HTTP(S) URLs;
- embedded XLSX images;
- selected through the preview or row image picker;
- cuts from a horizontal or vertical image strip.

The strip importer detects uniform dividers at least two pixels thick, previews every
cut, rejects silent count mismatches, and also offers equal slicing.

## Soundtrack

Each Audio row is an independent export layer with:

- timeline start time;
- Trim In and optional Trim Out;
- per-track volume;
- Fade In and Fade Out;
- trimmed-region looping;
- project master volume.

FFmpeg resamples layers to 48 kHz, mixes and limits them, then writes AAC at 256 kb/s.
Projects without soundtrack rows export as video-only MP4 files.

## Install on Ubuntu or Debian

Install the operating-system dependencies:

```bash
sudo apt update
sudo apt install python3-venv ffmpeg fonts-urw-base35
```

Clone and run CTS inside a virtual environment:

```bash
git clone https://github.com/RetroFrost/CTS.git
cd CTS
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
python run.py
```

Later launches only need:

```bash
cd CTS
source .venv/bin/activate
python run.py
```

After `pip install .`, the packaged launcher is also available:

```bash
comparison-timeline-studio
```

Do not use `sudo pip` or `--break-system-packages`.

## Timing and export

Automatic timing follows the established comparison-video motion:

- cards enter two seconds apart until the viewport fills;
- the strip moves one card width every 3⅓ seconds;
- the final viewport holds for two seconds;
- the picture fades over 0.8 seconds.

You can enter any custom total duration. CTS scales the complete animation to fit, so a
shorter duration intentionally increases card speed and a longer duration slows it down.

The default export is 1920×1080 at 30 FPS using H.264 (`yuv420p`) and optional AAC audio.

## Development

```bash
python -m unittest discover -s tests -v
python tools/render_models_qa.py
python tools/export_smoke_test.py
python tools/export_soundtrack_smoke.py
```

The current suite covers generic data import, model schemas, project migration, timing,
direct-edit hit regions and editor rectangles, scrolling positions, fixed/bouncing
hexagons, image-strip detection, rendering, and soundtrack filter generation. Real
FFmpeg smoke tests validate both silent and mixed H.264/AAC output.

### Source layout

```text
comparison_studio/
  app.py                    Application entry point
  premiere_ui.py            Editing-workspace shell and styling
  studio_ui.py              Backgrounds, fonts, scaling, and enhanced renderer
  word_safe_fit.py          Whole-word-safe badge fitting
  illustrated_shadow_polish.py  Illustrated depth treatment
  direct_transform.py       Per-card text/image transforms
  live_transform.py         Real-time move and resize feedback
  deselect_fix.py           Predictable transform deselection
  reselect_fix.py           Transformed-position hit-testing
  optional_hexagons.py      Global badge visibility and model reflow
  ui.py                     Proven spreadsheet, editor, and project widgets
  data.py                   Tables, models, projects, CSV/XLSX data support
  renderer.py               Deterministic base renderer and hit-testing
  exporter.py               Progress-aware FFmpeg export worker
  soundtrack.py             Audio probing and export filter graph construction
  strip_splitter.py         Divider detection and image extraction
  tests/                     Standard-library unittest suite
  tools/                     Visual and FFmpeg QA scripts
```

## Documentation

The project wiki is being built at the [CTS Wiki](https://github.com/RetroFrost/CTS/wiki).
Use it for longer tutorials, model-specific examples, spreadsheet templates, and release
notes as they are added.

## Roadmap

CTS 0.4.5 promotes the editing-workspace redesign into a direct-manipulation release while
keeping the proven comparison renderer and data-first workflow. Future work can continue
to add optional editor tools without making basic comparison generation harder.

## License

CTS is released under [CC0 1.0 Universal](LICENSE).
