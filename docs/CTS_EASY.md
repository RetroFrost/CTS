# CTS Easy

CTS Easy keeps the existing renderer, spreadsheet engine, direct preview editing, soundtrack mixer, and FFmpeg exporter, but puts the normal creator workflow first:

1. **Click to Insert Data**
2. **Choose music**
3. **Set the target video length**
4. **Export video**

Illustrated Cards is the default template. The spreadsheet remains directly available, while Models, layered Audio controls, badge animation, mapping, and other detailed controls stay behind **Advanced**.

## Target video length

A custom target does **not** slow down or speed up the complete rendered video. CTS keeps these parts at their normal speed:

- card entrance timing;
- final hold;
- final fade.

Only the interval used to scroll from one card to the next is recalculated. A shorter target makes cards scroll sooner; a longer target gives each horizontal card step more time.

When every card already fits in the viewport, there is no horizontal scrolling to retime, so CTS keeps the automatic duration.

## Music

The Easy music picker selects one soundtrack, enables looping, and adds a short fade-out so it follows the video length automatically. Open **Advanced → Audio** for multiple layers, delayed starts, trimming, independent volume, looping, and fades.
