# CTS Easy

The desktop app now follows the Android app's workflow instead of placing a simplified panel on top of the old editing workspace.

## Normal workflow

The main window is organized in the same order as Android:

1. **Program Monitor**
2. **Playback timeline**
3. **Bottom action sheet**

The action sheet contains the complete normal workflow:

1. **Click to Insert Data**
2. **Music**
3. **Video length**
4. **Export Video**

Illustrated Cards is the default template. New projects start empty, so CTS does not show several meaningless blank cards before data is inserted.

## Insert data

There is one permanent data action. It opens a single sheet where the user can:

- paste copied spreadsheet cells;
- paste CSV, TSV, or semicolon-separated text;
- use one **Import CSV / XLSX** file action.

The first row becomes the field names and every later row becomes one card. Separate permanent XLSX and paste buttons are intentionally removed from the normal workflow.

## Fix Something

The spreadsheet, model controls, image-strip tools, detailed soundtrack mixer, mapping, and visual customization are hidden by default. **Fix Something** opens them as an optional lower sheet. Closing it returns to the monitor-first creation workflow.

Direct Program Monitor editing is also gated by Fix Something, preventing accidental edits while previewing or exporting.

## Target video length

A custom target does **not** slow down or speed up the complete rendered video. CTS keeps these parts at their normal speed:

- card entrance timing;
- final hold;
- final fade.

Only the interval used to scroll from one card to the next is recalculated. A shorter target makes cards scroll sooner; a longer target gives each horizontal card step more time.

When every card already fits in the viewport, there is no horizontal scrolling to retime, so CTS keeps the automatic duration.

## Music

The main Music action selects one soundtrack, enables looping, and adds a short fade-out so it follows the video length automatically. Open **Fix Something → Audio** for multiple layers, delayed starts, trimming, independent volume, looping, and fades.
