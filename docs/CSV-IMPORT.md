# CSV import

Cubical Compare can import comparison data directly from a `.csv` file.

## Comparison-table format

A CSV can mirror the familiar comparison-table layout:

    Highlight,Highlight Image,Title,Description,Image
    3.37 km²,highlight/batman.png,Batman: Arkham Knight,Gotham City was built with aggressive vertical density,maps/batman.png
    3.7 km²,highlight/assassins.png,Assassin's Creed Syndicat,Pushed city density further,maps/assassins.png

Highlight maps to the card's primary badge/highlight value. Title, Description, and Image map to the corresponding card fields. Highlight Image is optional; when an Image column exists, Image is preferred for the card artwork. If Image is absent, Highlight Image can be used as the artwork fallback.

## Supported CSV fields

The first row is treated as the header row. Field names are matched automatically, so the exact column order is not important.

Common fields include:

- **Highlight** / **Badge Value** / **Value**
- **Badge Label** / **Unit**
- **Title**
- **Description**
- **Highlight Image** (optional artwork fallback)
- **Image** / **Artwork**
- **Image URL**
- **URL**
- **Web URL**
- **Web Image URL**
- **Web Artwork URL**
- **Artwork URL**
- **Image Link**

All fields are optional. Each non-empty data row becomes a card.

## Local or web images

The Image field can contain either a local path or a web URL.

For local artwork, paths are resolved relative to the CSV file:

    Highlight,Title,Image
    3.37 km²,Batman: Arkham Knight,images/batman.png

A CSV does not need a web URL column. Local-only artwork remains supported.

## Web artwork URLs

A web URL is optional. A CSV may use a URL as the artwork source instead of a local file path:

```csv
Badge Value,Title,Web URL
42,Remote card,https://example.com/card.png
```

A local-only CSV remains valid:

```csv
Badge Value,Title,Image
42,Local card,art/card.png
```

If both local and URL-style artwork columns are present, the automatically selected field mapping determines which column supplies the card artwork. The URL is retained as the card's artwork source and uses Cubical Compare's normal web-image resolution/cache path.

## Importing

Use **Import CSV / XLSX** in the data dialog, or select the CSV through the spreadsheet workflow. Quoted CSV fields and UTF-8 CSV files are supported. The first row must contain field names.

