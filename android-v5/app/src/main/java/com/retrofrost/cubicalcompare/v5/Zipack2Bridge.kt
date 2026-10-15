package com.retrofrost.cubicalcompare.v5

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Reads and writes the actual Windows Zipack2 v2 contact-sheet manifest. */
internal object Zipack2Bridge {
    const val TILE=363
    private const val GAP=8
    private const val MAX_CARDS=500
    private const val MAX_ARCHIVE=384L*1024*1024
    private const val MAX_ENTRY=96L*1024*1024
    private val valid=Regex("""[a-zA-Z0-9_./-]+""")
    private val sheetPattern=Regex("""artwork/.*(contact-sheet|contactsheet|sheet-).*\.(png|jpg|jpeg|webp|bmp)""")
    data class Imported(val project:StudioModel.Project,val count:Int)

    internal fun safe(name:String):Boolean =
        name.isNotEmpty()&&name.length<=240&&valid.matches(name)&&!name.startsWith('/')&&
        name.split('/').none{it.isBlank()||it=="."||it==".."}

    private fun bytes(input:InputStream,cap:Long):ByteArray {
        val stream=ByteArrayOutputStream()
        val buf=ByteArray(65536);var sum=0L
        while(true){val n=input.read(buf);if(n<0)break;sum+=n
            require(sum<=cap){"MegaPack entry exceeds its size limit"}
            stream.write(buf,0,n)
        }
        return stream.toByteArray()
    }
    private fun read(zip:ZipFile,path:String,max:Long=MAX_ENTRY):ByteArray {
        val item=zip.getEntry(path)?:error("Missing Zipack2 entry: "+path)
        require(item.size<0||item.size<=max){"Oversized ZIP entry"}
        return zip.getInputStream(item).use{bytes(it,max)}
    }
    private fun readSource(ctx:Context,uri:Uri):File {
        val target=File.createTempFile("cts-zipack2-", ".zip",ctx.cacheDir)
        try{
            val source=ctx.contentResolver.openInputStream(uri)?:error("Cannot open MegaPack ZIP")
            source.use{input->target.outputStream().use{output->
                val buf=ByteArray(65536);var used=0L
                while(true){val n=input.read(buf);if(n<0)break;used+=n
                    require(used<=MAX_ARCHIVE){"MegaPack exceeds 384 MB compressed"}
                    output.write(buf,0,n)
                }
            }}
            return target
        }catch(e:Exception){target.delete();throw e}
    }
    private fun decode(data:ByteArray,maxSide:Int=4500):Bitmap {
        val opts=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(data,0,data.size,opts)
        require(opts.outWidth>0&&opts.outHeight>0&&opts.outWidth<=16000&&opts.outHeight<=16000){
            "Unsupported contact sheet image size"
        }
        var sample=1
        while(opts.outWidth/sample>maxSide||opts.outHeight/sample>maxSide)sample*=2
        return BitmapFactory.decodeByteArray(data,0,data.size,
            BitmapFactory.Options().apply{inSampleSize=sample})
            ?:error("Invalid contact sheet image")
    }
    private fun png(bitmap:Bitmap):ByteArray {
        val out=ByteArrayOutputStream()
        require(bitmap.compress(Bitmap.CompressFormat.PNG,100,out)){"PNG encoding failed"}
        return out.toByteArray()
    }
    private fun property(obj:JSONObject,vararg keys:String):String {
        for(key in keys){val s=obj.optString(key,"").trim();if(s.isNotEmpty())return s}
        return ""
    }
    private fun finite(n:Double,min:Double,max:Double,def:Double):Double =
        if(n.isFinite())n.coerceIn(min,max)else def

    private fun metadata(obj:JSONObject):StudioModel.Card {
        val c=StudioModel.Card()
        c.Title=property(obj,"title","name").ifBlank{"Comparison"}
        c.BadgeHeader=property(obj,"badge_header","badgeHeader")
        val legacy=property(obj,"value").split(Regex("""\s+""")).filter{it.isNotBlank()}
        val badge=property(obj,"badge_value","badge_primary")
        c.Badge=badge.ifBlank{legacy.firstOrNull()?:""}
        c.BadgeUnit=property(obj,"badge_unit","badge_secondary").ifBlank{
            if(badge.isEmpty()&&legacy.size>1)legacy.drop(1).joinToString(" ")else""
        }
        c.Description=property(obj,"description","details")
        c.ImageTransform.X=finite(obj.optDouble("image_x",0.0),-4000.0,4000.0,0.0)
        c.ImageTransform.Y=finite(obj.optDouble("image_y",0.0),-4000.0,4000.0,0.0)
        c.ImageTransform.Scale=finite(obj.optDouble("image_scale",1.0),0.05,20.0,1.0)
        c.ImageTransform.Rotation=finite(obj.optDouble("image_rotation",0.0),-360.0,360.0,0.0)
        return c
    }
    private fun regions(def:JSONObject,bm:Bitmap,originalWidth:Int,originalHeight:Int):List<Rect>{
        val raw=def.optJSONArray("regions")
        if(raw!=null&&raw.length()>0){
            return (0 until raw.length()).map{index->
                val r=raw.getJSONObject(index)
                val x=r.getInt("x");val y=r.getInt("y")
                val w=r.getInt("width");val h=r.getInt("height")
                require(x>=0&&y>=0&&w>=24&&h>=24 &&
                    x.toLong()+w<=originalWidth && y.toLong()+h<=originalHeight){
                    "Invalid contact-sheet region"
                }
                r.optInt("order",index) to Rect(x,y,x+w,y+h)
            }.sortedBy{it.first}.map{it.second}
        }
        // Legacy sheets lacking explicit regions use Windows' yellow grid convention.
        // Determine separator runs from actual pixels, not assumed fixed dimensions.
        fun bands(horizontal:Boolean):List<Pair<Int,Int>>{
            val length=if(horizontal)bm.width else bm.height
            val other=if(horizontal)bm.height else bm.width
            val sampleCount=minOf(other,11)
            val background=def.optJSONObject("separator")?:JSONObject()
            val rr=background.optInt("red",255);val gg=background.optInt("green",255)
            val bb=background.optInt("blue",0);val tolerance=background.optInt("tolerance",20)
            val mask=BooleanArray(length)
            for(at in 0 until length) {
                var matched=0
                for(sample in 0 until sampleCount){
                    val p=(sample.toLong()*(other-1)/maxOf(1,sampleCount-1)).toInt()
                    val color=if(horizontal)bm.getPixel(at,p)else bm.getPixel(p,at)
                    if(kotlin.math.abs(Color.red(color)-rr)<=tolerance &&
                        kotlin.math.abs(Color.green(color)-gg)<=tolerance &&
                        kotlin.math.abs(Color.blue(color)-bb)<=tolerance)matched++
                }
                mask[at]=matched*10>=sampleCount*7
            }
            val areas=ArrayList<Pair<Int,Int>>()
            var start=-1
            for(i in mask.indices){
                if(!mask[i]&&start<0)start=i
                if(mask[i]&&start>=0){if(i-start>=24)areas.add(start to i);start=-1}
            }
            if(start>=0&&length-start>=24)areas.add(start to length)
            return areas
        }
        val xs=bands(true);val ys=bands(false)
        require(xs.isNotEmpty()&&ys.isNotEmpty()){"No outlined card regions detected"}
        return ys.flatMap{y->xs.map{x->
            Rect(
                (x.first.toLong()*originalWidth/bm.width).toInt(),
                (y.first.toLong()*originalHeight/bm.height).toInt(),
                (x.second.toLong()*originalWidth/bm.width).toInt().coerceAtMost(originalWidth),
                (y.second.toLong()*originalHeight/bm.height).toInt().coerceAtMost(originalHeight)
            )
        }}
    }

    private fun allowed(url:URL):Boolean{
        val host=url.host.lowercase(Locale.ROOT)
        return url.protocol.equals("https",true) &&
            (host=="flaticon.com"||host.endsWith(".flaticon.com"))
    }
    private fun request(url:URL,html:Boolean=false,redirects:Int=0):ByteArray {
        require(allowed(url)){"Only HTTPS Flaticon artwork is supported"}
        require(redirects<=4){"Too many Flaticon redirects"}
        val connection=url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects=false
        connection.connectTimeout=9000;connection.readTimeout=13000
        connection.setRequestProperty("User-Agent","Mozilla/5.0 CubicalCompare/5.0")
        try{
            val status=connection.responseCode
            if(status in 300..399){
                val location=connection.getHeaderField("Location")?:error("Invalid Flaticon redirect")
                return request(URL(url,location),html,redirects+1)
            }
            require(status==200){"Flaticon returned HTTP "+status}
            val type=connection.contentType.orEmpty().lowercase(Locale.ROOT)
            require(if(html)type.contains("text/html")else type.startsWith("image/")){
                "The Flaticon page did not return artwork"
            }
            return connection.inputStream.use{bytes(it,if(html)2L*1024*1024 else 12L*1024*1024)}
        }finally{connection.disconnect()}
    }
    private fun flaticon(source:String):ByteArray {
        val link=URL(source)
        require(allowed(link)){"Unsupported artwork URL. Choose Flaticon HTTPS."}
        val ext=link.path.lowercase()
        if(ext.endsWith(".png")||ext.endsWith(".webp")||ext.endsWith(".jpg")||
            ext.endsWith(".jpeg"))return request(link)
        val html=request(link,true).toString(Charsets.UTF_8)
        val tag=Regex("""<meta[^>]+(?:property|name)=["']og:image["'][^>]*>""",
            RegexOption.IGNORE_CASE).find(html)?.value ?:error("No Flaticon page preview found")
        val sourceUrl=Regex("""content=["']([^"']+)["']""",RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)?.replace("&amp;","&")
            ?:error("No Flaticon artwork source found")
        return request(URL(sourceUrl))
    }

    /** Bounded artwork download. Only HTTPS *.flaticon.com hosts are allowed. */
    internal fun downloadFlaticonArtwork(source:String):String {
        val bitmap=decode(flaticon(source),1600)
        return try{Base64.encodeToString(png(bitmap),Base64.NO_WRAP)}
        finally{bitmap.recycle()}
    }

    fun importPack(ctx:Context,source:Uri,progress:(Int)->Unit={}):Imported{
        val downloaded=readSource(ctx,source)
        try{
            ZipFile(downloaded).use{zip->
                val names=HashSet<String>();var expanded=0L;var members=0
                val en=zip.entries()
                while(en.hasMoreElements()){
                    val entry=en.nextElement()
                    if(entry.isDirectory)continue
                    require(++members<=2048){"ZIP contains too many entries"}
                    require(safe(entry.name)){"Unsafe MegaPack archive entry"}
                    require(names.add(entry.name.lowercase(Locale.ROOT))){"Duplicate archive entry"}
                    if(entry.size>=0){require(entry.size<=MAX_ENTRY){"ZIP entry too big"}
                        expanded+=entry.size;require(expanded<=768L*1024*1024){"Archive expands too large"}}
                }
                val manifestName=when{
                    zip.getEntry("manifest.json")!=null->"manifest.json"
                    zip.getEntry("megapack.json")!=null->"megapack.json"
                    else->error("Zipack2 manifest.json is missing")
                }
                val manifest=JSONObject(read(zip,manifestName,2L*1024*1024).toString(Charsets.UTF_8))
                require(manifest.optString("format")=="cubical.megapack.zipack2" &&
                    manifest.optInt("version")==2){"Unsupported MegaPack version"}
                val details=manifest.optJSONArray("cards")?:JSONArray()
                require(details.length()<=MAX_CARDS){"Maximum 500 MegaPack cards"}
                val cards=ArrayList<StudioModel.Card>()
                for(i in 0 until details.length())cards.add(metadata(details.getJSONObject(i)))
                val defs=manifest.optJSONArray("contactSheets")?:JSONArray()
                val sheets=ArrayList<JSONObject>()
                for(i in 0 until defs.length())sheets.add(defs.getJSONObject(i))
                if(sheets.isEmpty()){
                    val sheetsInZip=names.filter{sheetPattern.matches(it)}.sorted()
                    sheetsInZip.forEachIndexed{index,name->
                        sheets.add(JSONObject().put("path",name).put("order",index))
                    }
                }
                sheets.sortWith(compareBy({it.optInt("order")},{it.optString("path")}))
                var index=0
                for((sheetIndex,def) in sheets.withIndex()){
                    val name=def.optString("path")
                    require(safe(name)){"Unsafe sheet path"}
                    val data=read(zip,name)
                    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                    BitmapFactory.decodeByteArray(data,0,data.size,bounds)
                    require(bounds.outWidth>0 && bounds.outHeight>0 &&
                        bounds.outWidth<=16000 && bounds.outHeight<=16000){
                        "Unsupported full contact-sheet dimensions"
                    }
                    // Large Windows sheets can exceed 6000 pixels. Decode the
                    // original card regions rather than cropping a scaled preview.
                    val sample=if((def.optJSONArray("regions")?.length()?:0)>0)
                        Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)
                        else decode(data,4500)
                    try{
                        val sourceRegions=regions(def,sample,bounds.outWidth,bounds.outHeight)
                        val decoder=BitmapRegionDecoder.newInstance(data,0,data.size,false)
                        try{
                            for(region in sourceRegions){
                                require(index<MAX_CARDS){"Too many contact sheet cards"}
                                require(region.width()>=24 && region.height()>=24){
                                    "Invalid contact-sheet tile dimensions"
                                }
                                var factor=1
                                while(region.width()/factor>2200 || region.height()/factor>2200)factor*=2
                                val cropped=decoder.decodeRegion(region,
                                    BitmapFactory.Options().apply{inSampleSize=factor})
                                    ?:error("Failed to decode contact sheet card "+(index+1))
                                try{
                                    val target=cards.getOrNull(index)?:StudioModel.Card().also{
                                        it.Title="Comparison "+(index+1);cards.add(it)
                                    }
                                    target.ArtworkBase64=Base64.encodeToString(png(cropped),Base64.NO_WRAP)
                                    val m=details.optJSONObject(index)
                                    if(m!=null)target.ArtworkSource=property(m,"image","image_url",
                                        "imageUrl","artwork","artwork_url","icon")
                                }finally{cropped.recycle()}
                                index++
                            }
                        }finally{decoder.recycle()}
                    }finally{sample.recycle()}
                    progress((sheetIndex+1)*80/sheets.size)
                }
                for(i in cards.indices){
                    if(cards[i].ArtworkBase64.isBlank()){
                        val definition=details.optJSONObject(i)
                        if(definition!=null){
                            val uri=property(definition,"image","image_url","imageUrl",
                                "artwork","artwork_url","icon")
                            if(uri.isNotBlank()){
                                cards[i].ArtworkBase64=downloadFlaticonArtwork(uri)
                                cards[i].ArtworkSource=uri
                            }
                        }
                    }
                }
                require(cards.isNotEmpty()){"No cards in the MegaPack"}
                val project=StudioModel.Project()
                project.ProjectName=manifest.optString("name","MegaPack")
                project.Cards=cards
                project.AudioRepeat=manifest.optBoolean("soundtrack_loop",true)
                project.AudioVolume=finite(manifest.optDouble("soundtrack_volume",1.0),0.0,1.0,1.0)
                val audioPaths=manifest.optJSONArray("soundtracks")
                val ordered=if(audioPaths!=null&&audioPaths.length()>0){
                    (0 until audioPaths.length()).map{audioPaths.getString(it)}
                }else listOfNotNull(manifest.optString("soundtrack","").takeIf{it.isNotBlank()})
                require(ordered.size<=128){"MegaPack contains too many audio tracks"}
                for(audio in ordered) {
                    require(safe(audio)&&audio.startsWith("audio/")){"Unsafe soundtrack reference"}
                    val dest=File(ctx.filesDir,"zipack2-soundtracks").apply{mkdirs()}
                    val suffix=audio.substringAfterLast('.', "mp3").lowercase().take(5)
                    val ext=if(suffix in setOf("mp3","m4a","wav","aac","wma","ogg","flac"))suffix else "mp3"
                    val path=File(dest,"track-"+java.util.UUID.randomUUID()+"."+ext)
                    path.writeBytes(read(zip,audio))
                    project.AudioTracks.add(Uri.fromFile(path).toString())
                }
                progress(100)
                return Imported(project,cards.size)
            }
        }finally{downloaded.delete()}
    }

    private fun fillArt(card:StudioModel.Card):Bitmap {
        val bitmap=Bitmap.createBitmap(TILE,TILE,Bitmap.Config.ARGB_8888)
        val c=Canvas(bitmap);c.drawColor(Color.rgb(222,244,249))
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(200,231,237)}
        c.drawRoundRect(9f,9f,(TILE-9).toFloat(),(TILE-9).toFloat(),26f,26f,p)
        if(card.ArtworkBase64.isNotBlank()){
            val image=decode(Base64.decode(card.ArtworkBase64,Base64.DEFAULT),1200)
            try{
                val scale=minOf(313f/image.width,313f/image.height)
                val w=image.width*scale;val h=image.height*scale
                c.drawBitmap(image,null,RectF((TILE-w)/2,(TILE-h)/2,
                    (TILE+w)/2,(TILE+h)/2),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }finally{image.recycle()}
        }
        return bitmap
    }
    private fun put(zip:ZipOutputStream,name:String,data:ByteArray){
        require(safe(name)){"Unsafe export entry"}
        zip.putNextEntry(ZipEntry(name));zip.write(data);zip.closeEntry()
    }
    fun exportPack(ctx:Context,project:StudioModel.Project,target:Uri,progress:(Int)->Unit={}){
        require(project.Cards.isNotEmpty()&&project.Cards.size<=MAX_CARDS){
            "Add between 1 and 500 cards before exporting a MegaPack"
        }
        val manifest=JSONObject()
            .put("format","cubical.megapack.zipack2").put("version",2)
            .put("name",project.ProjectName).put("show_badges",true)
            .put("credits_enabled",true).put("soundtrack_loop",project.AudioRepeat)
            .put("soundtrack_volume",project.AudioVolume)
        val cards=JSONArray()
        project.Cards.forEachIndexed{i,card->
            val t=card.transform(false)
            cards.put(JSONObject().put("id","zipack2-"+(i+1))
                .put("title",card.Title).put("description",card.Description)
                .put("badge_header",card.BadgeHeader).put("badge_value",card.Badge)
                .put("badge_unit",card.BadgeUnit)
                .put("image_x",t.X).put("image_y",t.Y)
                .put("image_scale",t.Scale).put("image_rotation",t.Rotation))
        }
        manifest.put("cards",cards)
        val sheets=JSONArray()
        val output=ctx.contentResolver.openOutputStream(target,"wt")
            ?:error("Cannot create MegaPack file")
        ZipOutputStream(output.buffered()).use{zip->
            val batches=project.Cards.chunked(10)
            batches.forEachIndexed{sheetIndex,batch->
                val cols=minOf(5,batch.size)
                val rows=(batch.size+cols-1)/cols
                val w=cols*(TILE+GAP)+GAP;val h=rows*(TILE+GAP)+GAP
                val sheet=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                try{
                    sheet.eraseColor(Color.YELLOW)
                    val c=Canvas(sheet);val regions=JSONArray()
                    batch.forEachIndexed{i,item->
                        val x=GAP+i%cols*(TILE+GAP)
                        val y=GAP+i/cols*(TILE+GAP)
                        val tile=fillArt(item)
                        try{c.drawBitmap(tile,x.toFloat(),y.toFloat(),null)}
                        finally{tile.recycle()}
                        regions.put(JSONObject().put("x",x).put("y",y)
                            .put("width",TILE).put("height",TILE).put("order",i))
                    }
                    val name="artwork/contact-sheet-"+(sheetIndex+1).toString().padStart(3,'0')+".png"
                    put(zip,name,png(sheet))
                    sheets.put(JSONObject().put("path",name).put("order",sheetIndex)
                        .put("expectedCardWidth",TILE).put("expectedCardHeight",TILE)
                        .put("regions",regions))
                }finally{sheet.recycle()}
                progress((sheetIndex+1)*85/batches.size)
            }
            manifest.put("contactSheets",sheets)
            // Generate this portable MegaPack thumbnail using Bitmap Canvas.
            // A View must not be constructed on a background worker thread.
            val thumb=Bitmap.createBitmap(960,540,Bitmap.Config.ARGB_8888)
            try{
                val surface=Canvas(thumb)
                surface.drawColor(Color.rgb(22,48,66))
                val art=fillArt(project.Cards.first())
                try{surface.drawBitmap(art,null,RectF(470f,24f,936f,522f),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))}
                finally{art.recycle()}
                val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply{
                    color=Color.WHITE;textSize=36f;typeface=Typeface.DEFAULT_BOLD
                }
                surface.drawText(project.Cards.first().Title.take(22),30f,160f,pen)
                pen.textSize=25f
                surface.drawText((project.Cards.first().BadgeHeader+" "+
                    project.Cards.first().Badge).take(28),30f,238f,pen)
                put(zip,"thumbnail.png",png(thumb))
            }finally{thumb.recycle()}
            // Keep the Windows v2 single-track key for compatibility, and
            // preserve the full ordered Android creator pattern as an extension.
            require(project.AudioTracks.size<=128){"Too many soundtrack entries"}
            val soundtracks=JSONArray()
            var soundtrackBytes=0L
            project.AudioTracks.forEachIndexed{index,track->
                val uri=Uri.parse(track)
                val mime=ctx.contentResolver.getType(uri).orEmpty()
                val last=uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()
                val ext=if(last in setOf("mp3","wav","m4a","aac","wma","ogg","flac"))last
                    else when(mime){
                        "audio/mpeg"->"mp3"
                        "audio/wav","audio/x-wav"->"wav"
                        "audio/mp4"->"m4a"
                        "audio/flac"->"flac"
                        "audio/ogg"->"ogg"
                        else->"mp3"
                    }
                val name="audio/pattern-"+(index+1).toString().padStart(3,'0')+"."+ext
                val input=ctx.contentResolver.openInputStream(uri)
                    ?:error("Soundtrack "+(index+1)+" is inaccessible; relink it")
                zip.putNextEntry(ZipEntry(name))
                input.use{
                    val buffer=ByteArray(65536);var trackBytes=0L
                    while(true){
                        val read=it.read(buffer);if(read<0)break
                        trackBytes+=read;soundtrackBytes+=read
                        require(trackBytes<=MAX_ENTRY && soundtrackBytes<=MAX_ARCHIVE){
                            "MegaPack soundtracks exceed the 384 MB mobile limit"
                        }
                        zip.write(buffer,0,read)
                    }
                }
                zip.closeEntry()
                soundtracks.put(name)
                if(index==0)manifest.put("soundtrack",name)
            }
            if(soundtracks.length()>0)manifest.put("soundtracks",soundtracks)
            put(zip,"manifest.json",manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.finish()
        }
        progress(100)
    }
}
