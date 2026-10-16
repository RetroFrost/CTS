package com.retrofrost.cubicalcompare.v5

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import kotlin.math.min

/** Stream every authored Renderer3 frame through a bounded-memory H.264 encoder. */
internal object Renderer3VideoEncoder {
    @JvmStatic fun encode(context: Context, project: StudioModel.Project,
        destination: File, progress: (Int)->Unit, cancelled: ()->Boolean = { false }) {
        val selection=Renderer3Bridge.resolve(context,project)
        val w=project.ExportWidth.coerceIn(320,3840).let{it-it%2}
        val h=project.ExportHeight.coerceIn(180,2160).let{it-it%2}
        val fps=(selection?.fps?:project.Fps).coerceIn(1,120)
        val exact=selection?.let{Renderer3Bridge.Session(context,it,project)}
        val basic=if(exact==null)BasicFramePainter(context,project,w,h)else null
        try {
            val total=exact?.frames?:basic!!.frames
            val codecName=chooseCodec(project,w,h,fps)
            val codec=MediaCodec.createByCodecName(codecName)
            val supported=codec.codecInfo.getCapabilitiesForType(
                MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats.toSet()
            val planar=MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            val semi=MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            val colorMode=when{
                semi in supported->semi
                planar in supported->planar
                else->error("H.264 encoder has no supported planar YUV420 input mode")
            }
            val cfg=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,w,h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,colorMode)
                setInteger(MediaFormat.KEY_BIT_RATE,project.VideoBitrateMbps.coerceIn(1,80)*1_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE,fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,2)
            }
            val image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            val graphics=Canvas(image)
            val pixels=IntArray(w*h)
            val yuv=ByteArray(w*h*3/2)
            val bufferInfo=MediaCodec.BufferInfo()
            val muxer=MediaMuxer(destination.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var started=false
            var muxerStarted=false
            var track=-1
            var cursor=0
            var inputDone=false
            var outputDone=false
            try{
                codec.configure(cfg,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start();started=true
                while(!outputDone){
                    if(cancelled())throw java.util.concurrent.CancellationException("Export cancelled")
                    if(!inputDone){
                        val inputIdx=codec.dequeueInputBuffer(10000)
                        if(inputIdx>=0){
                            val input=codec.getInputBuffer(inputIdx)?:error("No H.264 input buffer")
                            input.clear()
                            val pts=cursor.toLong()*1_000_000/fps
                            if(cursor<total){
                                if(cancelled())throw java.util.concurrent.CancellationException("Export cancelled")
                                graphics.drawColor(Color.BLACK)
                                if(exact!=null)exact.paint(graphics,cursor,w,h)else basic!!.paint(graphics,cursor)
                                image.getPixels(pixels,0,w,0,0,w,h)
                                convertYuv(pixels,yuv,w,h,colorMode==semi)
                                check(input.remaining()>=yuv.size){"Encoder input buffer too small"}
                                input.put(yuv)
                                codec.queueInputBuffer(inputIdx,0,yuv.size,pts,0)
                                cursor++
                                if(cursor%fps==0||cursor==total)progress(cursor*100/total)
                            }else{
                                codec.queueInputBuffer(inputIdx,0,0,pts,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone=true
                            }
                        }
                    }
                    while(true){
                        val outputIdx=codec.dequeueOutputBuffer(bufferInfo,if(inputDone)10000 else 0)
                        when{
                            outputIdx==MediaCodec.INFO_TRY_AGAIN_LATER->break
                            outputIdx==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED->{
                                check(!muxerStarted){"Codec changed output format twice"}
                                track=muxer.addTrack(codec.outputFormat)
                                muxer.start();muxerStarted=true
                            }
                            outputIdx>=0->{
                                val encoded=codec.getOutputBuffer(outputIdx)?:error("No output buffer")
                                if(bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG!=0)bufferInfo.size=0
                                if(bufferInfo.size>0){
                                    check(muxerStarted){"Samples before output format"}
                                    encoded.position(bufferInfo.offset)
                                    encoded.limit(bufferInfo.offset+bufferInfo.size)
                                    muxer.writeSampleData(track,encoded,bufferInfo)
                                }
                                outputDone=bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                                codec.releaseOutputBuffer(outputIdx,false)
                                if(outputDone)break
                            }
                        }
                    }
                }
                progress(100)
            }finally{
                image.recycle()
                if(started)runCatching{codec.stop()}
                codec.release()
                if(muxerStarted)runCatching{muxer.stop()}
                muxer.release()
            }
        } finally {exact?.close();basic?.close()}
    }
    /** Choose a real compatible codec; do not silently ignore CPU/GPU preference. */
    private fun chooseCodec(project:StudioModel.Project,w:Int,h:Int,fps:Int):String {
        val candidates=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter{info->
            info.isEncoder && info.supportedTypes.any{it.equals(MediaFormat.MIMETYPE_VIDEO_AVC,true)} &&
                runCatching{
                    val caps=info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    val colors=caps.colorFormats.toSet()
                    (MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar in colors ||
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar in colors) &&
                        caps.videoCapabilities.areSizeAndRateSupported(w,h,fps.toDouble()) &&
                        caps.videoCapabilities.bitrateRange.contains(project.VideoBitrateMbps*1_000_000)
                }.getOrDefault(false)
        }
        fun software(info:MediaCodecInfo):Boolean=if(android.os.Build.VERSION.SDK_INT>=29)info.isSoftwareOnly
            else info.name.startsWith("OMX.google.",true)||info.name.startsWith("c2.android.",true)
        val filtered=when(project.VideoEncoder){
            "software"->candidates.filter{software(it)}
            "hardware"->candidates.filter{!software(it)}
            else->candidates.sortedBy{software(it)}
        }
        return filtered.firstOrNull()?.name?:error("No ${project.VideoEncoder} H.264 encoder supports ${w}×${h} at ${fps} FPS / ${project.VideoBitrateMbps} Mbps. Reduce export settings or select Auto.")
    }
    private fun convertYuv(pixels:IntArray,out:ByteArray,w:Int,h:Int,semi:Boolean){
        val frameSize=w*h
        for(i in pixels.indices){
            val c=pixels[i];val r=c ushr 16 and 255;val g=c ushr 8 and 255;val b=c and 255
            out[i]=((((66*r+129*g+25*b+128) shr 8)+16).coerceIn(0,255)).toByte()
        }
        var ui=frameSize;var vi=frameSize+frameSize/4;var uvi=frameSize
        var y=0
        while(y<h){
            var x=0
            while(x<w){
                var us=0;var vs=0
                for(dy in 0..1)for(dx in 0..1){
                    val py=min(h-1,y+dy);val px=min(w-1,x+dx)
                    val c=pixels[py*w+px]
                    val r=c ushr 16 and 255;val g=c ushr 8 and 255;val b=c and 255
                    us+=((-38*r-74*g+112*b+128) shr 8)+128
                    vs+=((112*r-94*g-18*b+128) shr 8)+128
                }
                val u=(us/4).coerceIn(0,255).toByte();val v=(vs/4).coerceIn(0,255).toByte()
                if(semi){out[uvi++]=u;out[uvi++]=v}
                else{out[ui++]=u;out[vi++]=v}
                x+=2
            }
            y+=2
        }
    }
}

/** Two cached cards, one output surface; every integer Rust frame is rendered. */
internal class BasicFramePainter(context:Context,project:StudioModel.Project,val w:Int,val h:Int):java.io.Closeable {
        private val json=StudioModel.timelineJson(project)
        val frames=NativeEngine.totalFrames(json).also{check(it in 1..Int.MAX_VALUE){"Invalid video timeline"}}.toInt()
        private val view=CardCanvas(context)
        private val cards=project.Cards
        private val bitmaps=linkedMapOf<Int,Bitmap>()
        private val paint=android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        fun paint(canvas:Canvas,frame:Int){
            val scene=org.json.JSONObject(NativeEngine.scene(json,frame.toLong()))
            check(!scene.has("error")){scene.optString("error")}
            val current=scene.getInt("currentCard");val next=scene.getInt("nextCard")
            bitmaps.keys.filter{it!=current && it!=next}.toList().forEach{bitmaps.remove(it)?.recycle()}
            fun image(index:Int)=bitmaps.getOrPut(index){view.renderBitmap(cards[index],false,w,h)}
            canvas.drawColor(Color.rgb(17,24,37))
            canvas.drawBitmap(image(current),(scene.getDouble("currentX")*w/960).toFloat(),0f,paint)
            if(next!=current && scene.getDouble("transition")>0.0)
                canvas.drawBitmap(image(next),(scene.getDouble("nextX")*w/960).toFloat(),0f,paint)
        }
        fun bitmap(frame:Int):Bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also{
            paint(Canvas(it),frame.coerceIn(0,frames-1))
        }
        override fun close(){bitmaps.values.forEach{it.recycle()};bitmaps.clear()}
    }
