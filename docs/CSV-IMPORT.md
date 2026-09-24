# CSV import

Cubical Compare can import comparison data directly from a `.csv` file.

## Comparison-table format

A CSV can mirror the familiar comparison-table layout:

    Highlight,Highlight Image,Title,Description,Image
    3.37 km²,highlight/batman.png,Batman: Arkham Knight,Gotham City was built with aggressive vertical density,maps/batman.png
    3.7 km²,highlight/assassins.png,Assassin's Creed Syndicat,Pushed city density further,maps/assassins.png

Highlight maps to the card's primary badge/highlight value. Title, Description, and Image map to the corresponding card fields. Highlight Image is optional; when an Image column exists, Image is preferred for the card artwork. If Image is absent, Highlight Image can be used as the artwork fallback.

## Complete CTS CSV schema

CTS has three visual card models, but they all resolve into the same five card fields:

| CTS card field | Canonical CSV header | Other supported headers |
|---|---|---|
| Primary badge value | `Badge Value` | `Badge Date / Value`, `Highlight`, `Value`, `Date`, `Uploaded`, `Upload Date`, `Uploaded Date`, `Year`, `Badge`, `Number`, `Amount`, `Age`, `Probability`, `Rank` |
| Secondary badge text | `Badge Label` | `Unit`, `Label`, `Badge Label / Unit`, `Small Label`, `Type`, `Metric` |
| Card title | `Title` | `Name`, `Heading`, `Card Title`, `Item`, `Subject` |
| Card description | `Description` | `Details`, `Summary`, `Text`, `Caption` |
| Card artwork | `Image` | `Image Path`, `Photo`, `Picture`, `Thumbnail`, `Artwork`, `Image URL`, `URL`, `Web URL`, `Web Image URL`, `Web Artwork URL`, `Artwork URL`, `Image Link`, `Highlight Image` |

All five fields are optional. CTS creates a card from every non-blank data row.

### Universal CTS template

This is the recommended portable CSV format because it covers every card field used by all CTS models:

```csv
Badge Value,Badge Label,Title,Description,Image
3.37,km²,Batman: Arkham Knight,Gotham City was built with aggressive vertical density,maps/batman.png
3.7,km²,Assassin's Creed Syndicat,Pushed city density further,maps/assassins.png
5.5,km²,Fortnite Battle Royale,The island shifts completely,maps/fortnite.png
8.12,km²,GTA III,Liberty City completely...,maps/gta3.png
9.11,km²,GTA Vice City,Over 38% of the calculated...,maps/gtavc.png
```

### Reference Timeline format

The Reference model can use its native field names directly:

```csv
Badge Date / Value,Title,Description,Image
3.37 km²,Batman: Arkham Knight,Gotham City was built with aggressive vertical density,maps/batman.png
3.7 km²,Assassin's Creed Syndicat,Pushed city density further,maps/assassins.png
```

### Illustrated Cards format

```csv
Badge Value,Badge Label,Title,Artwork
3.37,km²,Batman: Arkham Knight,maps/batman.png
3.7,km²,Assassin's Creed Syndicat,maps/assassins.png
```

### Classic Compact format

```csv
Value,Unit,Title,Image
3.37,km²,Batman: Arkham Knight,maps/batman.png
3.7,km²,Assassin's Creed Syndicat,maps/assassins.png
```

### Comparison-table compatibility

CTS also accepts the table-style names shown in comparison spreadsheets:

```csv
Highlight,Highlight Image,Title,Description,Image
3.37 km²,highlight/batman.png,Batman: Arkham Knight,Gotham City was built with aggressive vertical density,maps/batman.png
3.7 km²,highlight/assassins.png,Assassin's Creed Syndicat,Pushed city density further,maps/assassins.png
```

When both `Highlight Image` and `Image` are present, `Image` is preferred as the card artwork. If `Image` is absent, `Highlight Image` can supply the artwork.

## Local and web artwork

The artwork field can contain either a local file or a web URL.

Local paths are resolved relative to the CSV file:

```csv
Badge Value,Title,Image
3.37,Batman: Arkham Knight,images/batman.png
```

Web artwork is optional:

```csv
Badge Value,Title,Web URL
42,Remote card,https://example.com/card.png
```

A CSV can therefore be entirely local, entirely web-based, or use a mixture of local and web artwork rows.

If multiple artwork aliases are present, CTS prefers the canonical `Image` field before URL-style fallbacks.

## Importing

Use **Import CSV / XLSX** in the data dialog, or select the CSV through the spreadsheet workflow. Quoted CSV fields and UTF-8 CSV files are supported. The first row must contain field names.

