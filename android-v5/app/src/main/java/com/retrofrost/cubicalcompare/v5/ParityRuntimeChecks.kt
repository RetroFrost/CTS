package com.retrofrost.cubicalcompare.v5

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/** Executed by the emulator CI, using real Canvas, Rust JNI and Android media codecs. */
internal object ParityRuntimeChecks {
    private fun envelope(magic:String,json:String):ByteArray{
        val out=ByteArrayOutputStream();java.util.zip.GZIPOutputStream(out).use{it.write(json.toByteArray())}
        val bytes=out.toByteArray()
        return java.nio.ByteBuffer.allocate(20+bytes.size).order(java.nio.ByteOrder.BIG_ENDIAN).apply{
            put(magic.toByteArray());putInt(1);putInt(bytes.size)
            putInt(java.util.zip.CRC32().apply{update(bytes)}.value.toInt());put(bytes)
        }.array()
    }
    private fun checkRendererClockAndArtwork(context:Context,p:StudioModel.Project){
        val clock=File(context.cacheDir,"integer-clock.renderer3")
        clock.writeBytes(envelope("CCRNDR03", """{"api":3,"id":"integer-clock","engine":"scene-v3","timeline":{"clock":"absolute","frames":80,"implicitAnimation":false},"canvas":{"width":320,"height":180,"fps":60},"background":"#000000","resources":{"pixel":{"type":"rect","width":1,"height":1,"color":"#ff0000"}},"objects":[{"id":"pixel0","kind":"rect","resource":"pixel","frame":0,"properties":{"x":{"track":[[0,0],[79,79]],"interpolation":"linear","extrapolate":"hold"}}}]}"""))
        val selection=Renderer3Bridge.install(context,android.net.Uri.fromFile(clock))
        try{
            Renderer3Bridge.Session(context,selection,p).use{session->
                for(frame in listOf(0,1,7,13,31,59,60,61,79,31,0)){
                    val bitmap=session.bitmap(frame,320,180)
                    try{check(bitmap.getPixel(frame,0)!=Color.BLACK){"Renderer3 integer clock lost frame $frame"}}
                    finally{bitmap.recycle()}
                }
            }
        }finally{Renderer3Bridge.delete(context,selection);clock.delete()}
        val pack=File(context.cacheDir,"smart-artwork.renderer3")
        val transparent=Bitmap.createBitmap(64,100,Bitmap.Config.ARGB_8888)
        val png=ByteArrayOutputStream();transparent.compress(Bitmap.CompressFormat.PNG,100,png);transparent.recycle()
        java.util.zip.ZipOutputStream(pack.outputStream()).use{zip->
            fun entry(name:String,bytes:ByteArray){zip.putNextEntry(java.util.zip.ZipEntry(name));zip.write(bytes);zip.closeEntry()}
            entry("renderer.renderer3",envelope("CCRNDR03","""{"api":3,"id":"smart-artwork-test","timeline":{"clock":"absolute","frames":80,"implicitAnimation":false},"canvas":{"width":640,"height":360,"fps":60},"wrappedRenderer":"renderer.renderer","features":["project-card-data"],"resources":{"main":{"type":"smart-card-animation","sequenceRoot":"cards/demo"}},"objects":[{"id":"smartCard@0","kind":"smartCard","frame":0,"cardIndex":0,"resource":"main"}]}"""))
            entry("renderer.renderer",envelope("CCRNDR01","""{"id":"artwork-fixture","rendererApi":2,"engine":"ribbon-exact","canonicalCardCount":4,"canonicalFrameCount":80,"continuousStartFrame":60,"continuousStepFrames":30,"openingStarts":[0,20,40,60],"openingEnds":[20,40,60,80],"bodySlideFrames":1,"endHoldFrames":20,"fadeFrames":0,"blackTailFrames":0}"""))
            entry("cards/demo/desc.txt","64 100 60\np 1 0 base\n".toByteArray())
            entry("cards/demo/smart.json","""{"marker":"jsparse","transparentBackground":true,"emptyText":true,"fields":[{"name":"title","source":"title","token":"jsparse","rect":[0,0,64,10]}],"artwork":{"dest":[0,10,64,100],"clip":[0,10,64,100]}}""".toByteArray())
            entry("cards/demo/base/0000.png",png.toByteArray())
        }
        val smart=Renderer3Bridge.install(context,android.net.Uri.fromFile(pack))
        fun hash():Int=Renderer3Bridge.Session(context,smart,p).use{session->
            val bitmap=session.bitmap(10,640,360);val pixels=IntArray(640*360)
            bitmap.getPixels(pixels,0,640,0,0,640,360);bitmap.recycle();pixels.contentHashCode()
        }
        try{
            val before=hash();p.Cards[0].ImageTransform.X=110.0
            check(hash()!=before){"Wrapped SmartCard ignored artwork transform"}
            p.Cards[0].ImageTransform.X=0.0
            check(hash()==before){"Wrapped SmartCard transform reset is state dependent"}
        }finally{Renderer3Bridge.delete(context,smart);pack.delete()}
        android.util.Log.i("CTS_RENDERER3_PARITY","PASS: integer frame seek and live SmartCard artwork transforms")
    }
    fun run(context:Context){
        val p=StudioModel.blank().apply{
            ExportWidth=320;ExportHeight=180;Fps=16;SecondsPerCard=1;VideoDurationSeconds=2.0
            VideoBitrateMbps=1
            Cards.add(StudioModel.Card().apply{Title="Second";Badge="2"})
        }
        val art=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888)
        val draw=Canvas(art);draw.drawColor(Color.CYAN)
        draw.drawRect(0f,0f,40f,80f,Paint().apply{color=Color.RED})
        val bytes=ByteArrayOutputStream();check(art.compress(Bitmap.CompressFormat.PNG,100,bytes));art.recycle()
        p.Cards[0].ArtworkBase64=Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP)
        fun hash(bitmap:Bitmap):Int{val pixels=IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            bitmap.recycle();return pixels.contentHashCode()}
        checkRendererClockAndArtwork(context,p)
        val original=BasicFramePainter(context,p,320,180).use{painter->
            val first=hash(painter.bitmap(0))
            check(hash(painter.bitmap(14))!=first){"Conveyor transition did not render"}
            check(hash(painter.bitmap(0))==first){"Reverse seeking is state dependent"}
            first
        }
        p.Cards[0].ImageTransform.X=110.0
        val moved=BasicFramePainter(context,p,320,180).use{hash(it.bitmap(0))}
        check(moved!=original){"Artwork transform did not affect video frame"}
        val output=File(context.cacheDir,"parity-basic-export.mp4")
        Renderer3VideoEncoder.encode(context,p,output,{})
        val extractor=MediaExtractor()
        try{
            extractor.setDataSource(output.absolutePath)
            val index=(0 until extractor.trackCount).first{
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true}
            extractor.selectTrack(index)
            var count=0;var previous=-1L
            while(extractor.sampleTime>=0){
                check(extractor.sampleTime>previous){"Non-monotonic presentation time"}
                previous=extractor.sampleTime;count++
                if(!extractor.advance())break
            }
            check(count==32){"Expected every frame: 32, got $count"}
            check(previous==31L*1_000_000/16){"Wrong final frame timestamp: $previous"}
        } finally {extractor.release();output.delete()}
        android.util.Log.i("CTS_PARITY_SELFTEST","PASS: reverse seek, artwork transform, real 32-frame MP4 and timestamps")
    }
}
