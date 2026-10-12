package io.github.retrofrost.cts.android.renderer3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.rendering.BitmapSourceCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.min

/** Native player for CCRNDR03 scenes wrapping the legacy ribbon-exact timeline.
 * Raster animation comes from the package's SmartCard and SmartBadge v2 sequences.
 * Live project text and artwork are composited between the base and overlay plates.
 */
internal class RendererV3RibbonPlayer(
    context: Context,
    private val bundle: RendererV3Package,
    private val project: CtsProject,
) : Closeable {
    private val legacy = bundle.wrappedRibbon ?: error("Renderer3 has no wrapped ribbon timeline")
    private val badgeManifest = bundle.smartBadge
    private val resources = bundle.root.optJSONObject("resources") ?: JSONObject()
    private val nodes = buildMap<Int, JSONObject> {
        val objects = bundle.root.optJSONArray("objects") ?: JSONArray()
        for (index in 0 until objects.length()) {
            val obj = objects.optJSONObject(index) ?: continue
            if (obj.optString("kind") == "smartCard") {
                val cardIndex = obj.optInt("cardIndex", -1)
                if (cardIndex >= 0) put(cardIndex, obj)
            }
        }
    }
    private data class Key(val frame: Int, val value: Float, val easing: String)
    private val tracks = buildMap<String, List<Key>> {
        val input = legacy.optJSONArray("tracks") ?: JSONArray()
        for (i in 0 until input.length()) {
            val track = input.optJSONObject(i) ?: continue
            val name = track.optString("target")
            val entries = track.optJSONArray("keyframes") ?: continue
            val keys = (0 until entries.length()).mapNotNull { j ->
                entries.optJSONObject(j)?.let {
                    Key(it.optInt("timeMs"), it.optDouble("value").toFloat(), it.optString("easing", "linear"))
                }
            }.sortedBy { it.frame }
            if (keys.isNotEmpty()) put(name, keys)
        }
    }
    private val pitch = legacy.optDouble("slotPitch", 477.0).toFloat()
    private val inset = legacy.optDouble("bodyInset", 1.0).toFloat()
    private val bodyWidth = legacy.optDouble("bodyWidth", 470.0).toFloat()
    private val startFrame = legacy.optInt("continuousStartFrame", 528)
    private val stepFrames = max(1, legacy.optInt("continuousStepFrames", 214))
    private val referenceCards = max(4, legacy.optInt("canonicalCardCount", 78))
    private val referenceFrames = legacy.optInt("canonicalFrameCount", bundle.selection.frames)
    private val starts = legacy.optJSONArray("openingStarts") ?: JSONArray()
    private val ends = legacy.optJSONArray("openingEnds") ?: JSONArray()
    private val outroFrames = legacy.optInt("endWipeFrames", 0) + legacy.optInt("endRiseFrames", 0) +
        legacy.optInt("endHoldFrames", 300) + legacy.optInt("fadeFrames", 62) +
        legacy.optInt("blackTailFrames", 41)
    private val frames = frameCount(project.cards.size)
    private val sequences = object : LinkedHashMap<String, SmartSequenceV3>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SmartSequenceV3>?): Boolean =
            size > 12
    }
    private val art = BitmapSourceCache(context, 3)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val plates = object : LinkedHashMap<String, Bitmap>(10, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            if (size <= 9) return false
            eldest?.value?.takeUnless(Bitmap::isRecycled)?.recycle()
            return true
        }
    }
    private val w = bundle.selection.width.toFloat()
    private val h = bundle.selection.height.toFloat()
    fun durationSeconds(): Float = frames.toFloat() / bundle.selection.fps.coerceAtLeast(1)
    private fun frameCount(count: Int): Int {
        if (count == 0) return 1
        val pad = (referenceFrames.toLong() - outroFrames - startFrame -
            (referenceCards - 4).toLong() * stepFrames).coerceAtLeast(0)
        val opening = if (count < 4) ends.optInt(count - 1, startFrame) else startFrame
        return (opening + max(0, count - 4).toLong() * stepFrames + pad + outroFrames)
            .coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }
    private fun motion(name: String, frame: Int, windowed: Boolean = false): Float? {
        val list = tracks[name] ?: return null
        if (windowed && (frame < list.first().frame || frame > list.last().frame)) return null
        if (frame <= list.first().frame) return list.first().value
        if (frame >= list.last().frame) return list.last().value
        var low = 1; var high = list.lastIndex
        while (low < high) {
            val mid = (low + high) ushr 1
            if (list[mid].frame < frame) low = mid + 1 else high = mid
        }
        val a = list[low - 1]; val b = list[low]
        val t = ((frame - a.frame).toFloat() / max(1, b.frame - a.frame)).coerceIn(0f, 1f)
        val eased = when (b.easing.lowercase()) {
            "ease-in", "easein" -> t*t
            "ease-out", "easeout" -> 1 - (1-t)*(1-t)
            "ease-in-out", "easeinout", "smoothstep" -> t*t*(3-2*t)
            "cubic-in" -> t*t*t
            "cubic-out" -> 1-(1-t)*(1-t)*(1-t)
            "hold", "step" -> 0f
            else -> t
        }
        return a.value + (b.value - a.value)*eased
    }
    private fun cardStart(index: Int): Int = if (index < starts.length()) starts.optInt(index)
        else startFrame + max(0, index - 4) * stepFrames

    private fun positions(frame: Int): Map<Int, Float> {
        val count = project.cards.size
        if (count == 0) return emptyMap()
        if (frame >= startFrame && count > 4) {
            val segment = (frame - startFrame) / 512
            var scroll = motion("ribbon.scroll.$segment", frame, true)
                ?: (frame-startFrame).toFloat()/stepFrames*pitch
            val refEnd = referenceFrames - outroFrames - 1
            val last = motion("ribbon.scroll.${(refEnd-startFrame)/512}",refEnd)
            if (last != null) scroll = min(scroll, max(0f,last+(count-referenceCards)*pitch))
            val first = max(0, (scroll/pitch).toInt()-1)
            val final = min(count-1, ((scroll+w)/pitch).toInt()+1)
            return (first..final).mapNotNull { i ->
                val x = i*pitch-scroll
                if (x > -pitch && x < w+pitch) i to x else null
            }.toMap()
        }
        val active = (0 until min(4,count)).lastOrNull { frame >= cardStart(it) } ?: return emptyMap()
        val result = LinkedHashMap<Int,Float>()
        for (i in 0 until active) result[i] = i*pitch
        val local = frame-cardStart(active)
        val progress = (local.toFloat()/max(1,legacy.optInt("bodySlideFrames",80))).coerceIn(0f,1f)
        val eased = progress*progress*(3-2*progress)
        result[active] = motion("ribbon.open.$active.card.x",local)
            ?: if (active==0) -pitch+pitch*eased else (active-1)*pitch+pitch*eased
        return result
    }
    @Synchronized fun render(canvas: Canvas, seconds: Float, outWidth: Int, outHeight: Int) {
        val frame = (seconds.coerceAtLeast(0f)*bundle.selection.fps).toInt().coerceIn(0,frames-1)
        canvas.drawColor(legacy.optInt("backgroundColor", Color.rgb(18,18,18)))
        canvas.save()
        try {
            canvas.scale(outWidth/w,outHeight/h)
            val contentEnd = max(0,frames-outroFrames)
            if (frame >= contentEnd) {
                val fadeStart = legacy.optInt("endWipeFrames",0) +
                    legacy.optInt("endRiseFrames",0) + legacy.optInt("endHoldFrames",300)
                val fade = (frame-contentEnd-fadeStart).toFloat()/max(1,legacy.optInt("fadeFrames",62))
                if (fade > 0) {
                    paint.reset()
                    paint.color = Color.BLACK
                    paint.alpha = (255*fade.coerceIn(0f,1f)).toInt()
                    canvas.drawRect(0f,0f,w,h,paint)
                }
                return
            }
            val active = positions(frame)
            for ((index,x) in active) drawCard(canvas,index,x,frame)
            for ((index,x) in active) drawBadge(canvas,index,x,frame)
        } finally { canvas.restore() }
    }
    private fun sequence(path: String, nested: Boolean): SmartSequenceV3 {
        sequences[path]?.let { return it }
        val seq = if (nested) SmartSequenceV3.fromNestedZip(
            bundle.asset(path) ?: error("Missing SmartBadge pack: $path")
        ) else SmartSequenceV3.fromSidecars(bundle,path)
        sequences[path] = seq
        return seq
    }
    private fun bitmap(seq: SmartSequenceV3, path: String): Bitmap? {
        val key = System.identityHashCode(seq).toString() + "/" + path
        plates[key]?.let { if (!it.isRecycled) return it }
        val bytes = seq.frameAsset(path) ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
        if (opts.outWidth<=0 || opts.outHeight<=0) return null
        var sample = 1
        while (max(opts.outWidth,opts.outHeight)/(sample*2)>=1536) sample*=2
        val image = BitmapFactory.decodeByteArray(bytes,0,bytes.size,
            BitmapFactory.Options().apply { inSampleSize=sample }) ?: return null
        plates[key] = image
        return image
    }
    private fun plate(canvas: Canvas, seq: SmartSequenceV3, path: String?) {
        if (path==null) return
        val bitmap = bitmap(seq,path) ?: return
        paint.reset()
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        canvas.drawBitmap(bitmap,null,RectF(0f,0f,seq.width.toFloat(),seq.height.toFloat()),paint)
    }
    private fun drawCard(canvas: Canvas,index: Int,x: Float,frame: Int) {
        val card = project.cards.getOrNull(index) ?: return
        val node = nodes[index] ?: nodes[4] ?: return
        val props = node.optJSONObject("properties") ?: JSONObject()
        val resource = resources.optJSONObject(node.optString("resource")) ?: return
        val seq = sequence(resource.optString("sequenceRoot"),false)
        // Each measured SmartCard declares its own source start frame. In particular,
        // card 5 starts at 703 rather than the fallback 742. Using a generated
        // step here desynchronises the base, live jsparse text and its badge.
        val authoredStart = if (index in nodes) node.optInt("frame", cardStart(index))
            else cardStart(index)
        val local = frame-authoredStart+props.optInt("sequenceOffset",resource.optInt("sequenceOffset",0))
        val selected = seq.select(local) ?: return
        val drawWidth = props.optDouble("drawWidth",resource.optDouble("drawWidth",seq.width.toDouble())).toFloat()
        val drawHeight = props.optDouble("drawHeight",resource.optDouble("drawHeight",seq.height.toDouble())).toFloat()
        canvas.save()
        try {
            canvas.translate(x+props.optDouble("drawX",resource.optDouble("drawX",inset.toDouble())).toFloat(),
                props.optDouble("drawY",resource.optDouble("drawY",0.0)).toFloat())
            canvas.scale(drawWidth/seq.width,drawHeight/seq.height)
            plate(canvas,seq,selected.asset)
            val artwork = seq.artwork
            if (artwork!=null) {
                val dest = rectAt(artwork,"dest","destRects",selected.template)
                val clip = rectAt(artwork,"clip","clipRects",selected.template) ?: dest
                val alpha = numeric(artwork,"alphas",selected.template,1f)
                val image = art.load(card.imageSubcard.source)
                if (dest!=null && clip!=null && alpha>0f && image!=null) {
                    canvas.save()
                    canvas.clipRect(clip)
                    paint.reset()
                    paint.isAntiAlias=true
                    paint.isFilterBitmap=true
                    paint.alpha=(alpha.coerceIn(0f,1f)*255).toInt()
                    val scale = min(image.width/max(1f,dest.width()),image.height/max(1f,dest.height()))
                    val sw=dest.width()*scale;val sh=dest.height()*scale
                    canvas.drawBitmap(image,Rect(((image.width-sw)/2).toInt(),
                        ((image.height-sh)/2).toInt(),((image.width+sw)/2).toInt(),
                        ((image.height+sh)/2).toInt()),dest,paint)
                    canvas.restore()
                }
            }
            for (field in seq.fields) drawField(canvas,field,selected.template,card)
            plate(canvas,seq,seq.overlayAsset(selected.template))
        } finally { canvas.restore() }
    }
    private fun drawBadge(canvas: Canvas,index: Int,x: Float,frame: Int) {
        if (!project.showHexagons) return
        val card = project.cards.getOrNull(index) ?: return
        if (card.badgePrimary.isBlank() && card.badgeSecondary.isBlank()) return
        val config = badgeManifest ?: return
        val choice = config.optJSONObject("cards")?.opt(index.toString())
        val selection = choice as? JSONObject
        val packId = when (choice) {
            is JSONObject -> choice.optString("pack",config.optString("defaultPack"))
            is String -> choice
            else -> config.optString("defaultPack")
        }
        val source = config.optJSONObject("packs")?.opt(packId)
        val pack = source as? JSONObject ?: JSONObject()
        val zip = if (source is String) source else pack.optString("asset")
        if (zip.isBlank()) return
        val seq = sequence(zip,true)
        val start = selection?.optInt("startFrame",cardStart(index)) ?: cardStart(index)
        val elapsed = frame-start+(selection?.optInt("sequenceOffset",0) ?: 0)
        val hold = selection?.optBoolean("settledHold",pack.optBoolean("settledHold",false))
            ?: pack.optBoolean("settledHold",false)
        val frameSelection = seq.select(elapsed,hold) ?: return
        fun option(key: String, default: Double) =
            selection?.optDouble(key,pack.optDouble(key,default)) ?: pack.optDouble(key,default)
        val dx=option("drawX",0.0).toFloat();val dy=option("drawY",0.0).toFloat()
        val dw=option("drawWidth",seq.width.toDouble()).toFloat()
        val dh=option("drawHeight",seq.height.toDouble()).toFloat()
        val motion=selection?.optString("entryMotion",pack.optString("entryMotion"))
            ?: pack.optString("entryMotion")
        val anchor=selection?.optString("entryAnchor",pack.optString("entryAnchor"))
            ?: pack.optString("entryAnchor")
        var correction=0f
        if (motion=="top-to-final" || anchor=="final-x") {
            val field=seq.fields.firstOrNull { it.optString("source")=="value" } ?: seq.fields.firstOrNull()
            val from=field?.let { rectAt(it,"rect","rects",frameSelection.template) }
            val final=seq.finalFrame()?.template?.let { n -> field?.let { rectAt(it,"rect","rects",n) } }
            if (from!=null && final!=null) correction=final.centerX()-from.centerX()
        }
        canvas.save()
        try {
            if (index<4 && pack.optBoolean("clipOpeningToCard",true)) {
                canvas.clipRect(index*pitch+inset,0f,index*pitch+inset+bodyWidth,h)
            }
            canvas.translate(x+dx+correction,dy)
            canvas.scale(dw/seq.width,dh/seq.height)
            plate(canvas,seq,frameSelection.asset)
            for (field in seq.fields) drawField(canvas,field,frameSelection.template,card)
            plate(canvas,seq,seq.overlayAsset(frameSelection.template))
        } finally { canvas.restore() }
    }
    private fun rectAt(source: JSONObject, constant: String, tracked: String, frame: Int): RectF? {
        val track=source.optJSONArray(tracked)
        val item=if(track!=null && track.length()>0) track.opt(min(frame,track.length()-1))
            else source.opt(constant)
        val v=item as? JSONArray ?: return null
        if (v.length()<4) return null
        val x=v.optDouble(0).toFloat();val y=v.optDouble(1).toFloat()
        val width=v.optDouble(2).toFloat();val height=v.optDouble(3).toFloat()
        if (!width.isFinite() || !height.isFinite() || width<=0f || height<=0f) return null
        return RectF(x,y,x+width,y+height)
    }
    private fun numeric(source: JSONObject,key: String,index: Int,default: Float): Float {
        val track=source.optJSONArray(key) ?: return default
        if (track.length()==0) return default
        return track.optDouble(min(index,track.length()-1),default.toDouble()).toFloat()
            .takeIf { it.isFinite() } ?: default
    }
    private fun drawField(canvas: Canvas,field: JSONObject,template: Int,card: CtsCard) {
        val rect=rectAt(field,"rect","rects",template) ?: return
        val label=field.optString("source",field.optString("name")).lowercase()
        val words=card.badgeSecondary.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val old=words.lastOrNull()?.equals("old",true)==true
        val value=when(label) {
            "title" -> card.title
            "description" -> card.description
            "value","number","primary" -> card.badgePrimary
            "unit","secondary" -> if(old) words.dropLast(1).joinToString(" ") else card.badgeSecondary
            "header" -> if(old) "OLD" else ""
            else -> ""
        }.trim()
        if (value.isEmpty()) return
        val alpha=numeric(field,"alphas",template,1f).coerceIn(0f,1f)
        if (alpha<=0f) return
        paint.reset()
        paint.isAntiAlias=true
        val color=try { Color.parseColor(field.optString("color","#ffffff")) }
            catch (_: IllegalArgumentException) { Color.WHITE }
        paint.color=color
        paint.alpha=(Color.alpha(color)*alpha).toInt().coerceIn(0,255)
        paint.typeface=if(field.optBoolean("bold",false)) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textAlign=when(field.optString("align","center")) {
            "left" -> Paint.Align.LEFT
            "right" -> Paint.Align.RIGHT
            else -> Paint.Align.CENTER
        }
        val size=field.optDouble("fontSize",36.0).toFloat()
        paint.textSize=max(1f,size)
        val lines=wrapText(value,rect.width(),paint)
        val height=lines.size*size*1.15f
        paint.textSize=max(1f,min(size,size*min(1f,rect.height()/max(1f,height))))
        val lineHeight=paint.textSize*1.12f
        val total=lines.size*lineHeight
        val anchor=when(paint.textAlign) {
            Paint.Align.LEFT -> rect.left
            Paint.Align.RIGHT -> rect.right
            else -> rect.centerX()
        }
        val start=when(field.optString("verticalAlign","center")) {
            "top" -> rect.top+paint.textSize
            "bottom" -> rect.bottom-total+paint.textSize
            else -> rect.centerY()-total/2+paint.textSize
        }
        canvas.save()
        try {
            val rotation=numeric(field,"rotations",template,0f)
            if(rotation!=0f) canvas.rotate(rotation,rect.centerX(),rect.centerY())
            canvas.clipRect(rect)
            for ((i,line) in lines.withIndex()) canvas.drawText(line,anchor,start+i*lineHeight,paint)
        } finally { canvas.restore() }
    }
    private fun wrapText(raw: String,available: Float,paint: Paint): List<String> {
        if(paint.measureText(raw)<=available) return listOf(raw)
        val lines=ArrayList<String>();var line=""
        for (word in raw.split(Regex("\\s+"))) {
            val attempt=if(line.isBlank()) word else "$line $word"
            if(line.isNotBlank() && paint.measureText(attempt)>available) {
                lines+=line;line=word
            } else line=attempt
        }
        if(line.isNotBlank()) lines+=line
        return lines.take(5).ifEmpty { listOf(raw) }
    }
    /** Maps shortened project timing back onto the authored reference outro. */
    fun authoredOverlayTiming(outputFrame: Int): Pair<Int, String> {
        val contentEnd = max(0, frames - outroFrames)
        return if (outputFrame >= contentEnd)
            (referenceFrames - outroFrames + outputFrame - contentEnd).coerceIn(0, referenceFrames - 1) to "outro"
        else outputFrame to "foreground"
    }

    /** Special overlay objects use the same SmartSequence player and jsparse compositor. */
    fun drawAuthoredOverlay(
        canvas: Canvas, type: String, resource: JSONObject, props: Map<String, Any?>,
        objectFrame: Int, sourceFrame: Int, alpha: Float,
    ) {
        val root = resource.optString("sequenceRoot")
        if (root.isBlank()) return
        val seq = sequence(root, false)
        val explicit = props["sequenceFrame"] as? Number
        val current = explicit?.toInt() ?: (sourceFrame - objectFrame +
            resource.optInt("sequenceOffset", 0))
        val frame = seq.select(current) ?: return
        val card = project.cards.lastOrNull() ?: return
        fun value(key: String, default: Float): Float = when (val raw = props[key]) {
            is Number -> raw.toFloat()
            else -> resource.optDouble(key, default.toDouble()).toFloat()
        }
        val x = value("baseX", 0f) + value("drawX", 0f)
        val y = value("drawY", 0f)
        val width = value("drawWidth", seq.width.toFloat())
        val height = value("drawHeight", seq.height.toFloat())
        if (width <= 0f || height <= 0f) return
        canvas.save()
        try {
            canvas.translate(x, y)
            canvas.scale(width / seq.width, height / seq.height)
            if (alpha < .999f) {
                paint.reset()
                paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
                canvas.saveLayer(RectF(0f, 0f, seq.width.toFloat(), seq.height.toFloat()), paint)
            }
            plate(canvas, seq, frame.asset)
            if (type == "smart-card-animation") {
                val artwork = seq.artwork
                val dest = artwork?.let { rectAt(it, "dest", "destRects", frame.template) }
                val clip = artwork?.let { rectAt(it, "clip", "clipRects", frame.template) } ?: dest
                val image = art.load(card.imageSubcard.source)
                if (dest != null && clip != null && image != null) {
                    canvas.save()
                    canvas.clipRect(clip)
                    paint.reset()
                    paint.isAntiAlias = true
                    paint.isFilterBitmap = true
                    val cropScale = min(image.width / max(1f, dest.width()),
                        image.height / max(1f, dest.height()))
                    val sw = dest.width() * cropScale; val sh = dest.height() * cropScale
                    canvas.drawBitmap(image, Rect(((image.width-sw)/2).toInt(),
                        ((image.height-sh)/2).toInt(), ((image.width+sw)/2).toInt(),
                        ((image.height+sh)/2).toInt()), dest, paint)
                    canvas.restore()
                }
            }
            for (field in seq.fields) drawField(canvas,field,frame.template,card)
            plate(canvas,seq,seq.overlayAsset(frame.template))
            if (alpha < .999f) canvas.restore()
        } finally { canvas.restore() }
    }

    override fun close() {
        art.close()
        plates.values.forEach { if(!it.isRecycled) it.recycle() }
        plates.clear()
        sequences.clear()
    }
}
