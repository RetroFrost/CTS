# CTS Easy

The desktop app uses a guided program-setup workflow instead of placing a simplified panel on top of the old editing workspace.

## Normal workflow

The main window keeps the preview visible while setup advances:

1. **Program Monitor**
2. **Playback timeline**
3. **Video setup wizard**

The setup wizard reveals one step at a time:

1. **Select spreadsheet**
2. **Style**
3. **Music**
4. **Length**
5. **Export**

Each screen contains one primary choice plus familiar **Back** and **Next** controls. A
progress trail shows the completed, current, and remaining setup steps. Music can be
skipped, and automatic timing can be accepted with **Review**.

The final screen presents **Export MP4** and **Manual editor** side by side. Manual editor
is an escape hatch for further changes, not another setup step.

Illustrated Cards is the default template. New projects start empty, so CTS does not show several meaningless blank cards before data is inserted.

## Select spreadsheet

The primary action opens the system file picker directly and accepts CSV, TSV, TXT, or XLSX.
After loading, CTS detects the fields and card count, maps the table to the active style,
jumps the Program Monitor to a fully revealed card frame instead of the black opening frame,
and advances to the Style screen.

The Manual editor includes a separate **Paste / edit table** action where the user can:

- paste copied spreadsheet cells;
- paste CSV, TSV, or semicolon-separated text;
- bulk-edit the current table;
- import another CSV or XLSX file.

CTS detects the field and card counts live, disables confirmation until at least one card
is ready, and labels the confirmation action with the exact number of cards it will create.
`Ctrl+Enter` accepts a detected table.

The first row becomes the field names and every later row becomes one card. Spreadsheet
selection stays simple in the normal workflow; paste and bulk editing remain available in
the Manual editor.

## Style

The Style screen opens a focused chooser for Illustrated Cards, Reference Detail, and
Classic Compact. Selecting a style remaps compatible spreadsheet fields and refreshes the
preview automatically, then advances to Music. Illustrated Cards remains the recommended
default and can be accepted with **Next**.

## Ready-to-export defaults

Music and custom timing are explicitly optional. CTS starts with automatic timing, keeps
Export disabled only until a card exists, and marks the project **Ready to export** as soon
as data has been created. The wizard still walks through every decision before showing the
final Export and Manual editor screen.

## Manual editor

The spreadsheet, model controls, image-strip tools, detailed soundtrack mixer, mapping, and visual customization are hidden by default. **Manual editor** opens them as an optional lower sheet. Closing it returns to the monitor-first creation workflow.

Direct Program Monitor editing is also gated by Manual editor, preventing accidental edits while previewing or exporting.

## Target video length

A custom target does **not** slow down or speed up the complete rendered video. CTS keeps these parts at their normal speed:

- card entrance timing;
- final hold;
- final fade.

Only the interval used to scroll from one card to the next is recalculated. A shorter target makes cards scroll sooner; a longer target gives each horizontal card step more time.

When every card already fits in the viewport, there is no horizontal scrolling to retime, so CTS keeps the automatic duration.

## Music

The main Music action selects one soundtrack, enables looping, and adds a short fade-out so it follows the video length automatically. Open **Manual editor → Audio** for multiple layers, delayed starts, trimming, independent volume, looping, and fades.
