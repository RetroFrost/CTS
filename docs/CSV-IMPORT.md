# CSV import

Cubical Compare can import comparison data directly from a `.csv` file.

## Supported CSV fields

The first row is treated as the header row. Field names are matched automatically, so the exact column order is not important.

Common fields include:

- **Badge Value** / **Value**
- **Badge Label** / **Unit**
- **Title**
- **Description**
- **Image** / **Artwork**
- **Image URL**
- **URL**
- **Web URL**
- **Web Image URL**
- **Web Artwork URL**
- **Artwork URL**
- **Image Link**

All fields are optional. Each non-empty data row becomes a card.

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

