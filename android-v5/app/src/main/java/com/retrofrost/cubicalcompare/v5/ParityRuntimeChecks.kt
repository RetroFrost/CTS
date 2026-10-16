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
