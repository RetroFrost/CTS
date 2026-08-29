from pathlib import Path

script = Path(__file__).with_name("apply_relationships_precision_support.py")
source = script.read_text()
old = 'badge_text_end = "    private fun drawBadgeLine(\\n"'
new = 'badge_text_end = "    private fun drawBadgeLine(canvas: Canvas, text: String, x: Float, y: Float, preferredSize: Float, minimumSize: Float, maxWidth: Float) {\\n"'
if old not in source:
    raise RuntimeError("Badge-line patch marker was not found in the precision patch script")
source = source.replace(old, new, 1)
namespace = {"__file__": str(script), "__name__": "__main__"}
exec(compile(source, str(script), "exec"), namespace)
