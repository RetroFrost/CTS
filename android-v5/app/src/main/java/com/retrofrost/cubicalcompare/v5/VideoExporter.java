package com.retrofrost.cubicalcompare.v5;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import org.json.JSONObject;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.OptIn;
import androidx.media3.transformer.Effects;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.DefaultGainProvider;
import androidx.media3.common.audio.GainProcessor;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.common.util.UnstableApi;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CancellationException;
import static com.retrofrost.cubicalcompare.v5.StudioModel.*;

@OptIn(markerClass=UnstableApi.class)
public final class VideoExporter {
    public interface Listener {
        void update(String message,int percent);
        void finished(String message);
        void error(String error);
    }
    private final Activity activity;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Transformer transformer;
    private volatile boolean busy;
    private final AtomicBoolean cancelled=new AtomicBoolean(false);
    private final AtomicLong generation=new AtomicLong();
    private boolean active(long ticket){return generation.get()==ticket&&!cancelled.get();}
    private void ensureActive(long ticket){if(!active(ticket))throw new CancellationException("Export cancelled");}
    private File work;
    private Runnable progressTask;
    public VideoExporter(Activity activity){this.activity=activity;}
    public boolean isBusy(){return busy;}
    public void export(Project source,Uri destination,Listener cb){
        if(busy){cb.error("An export is already running.");return;}
        if(source.Cards==null||source.Cards.isEmpty()){cb.error("Add a card before exporting.");return;}
        final long ticket=generation.incrementAndGet();
        cancelled.set(false);
        busy=true;
        // Clone the data so editing during export can't alter the output mid-run.
        Project project=StudioModel.parse(StudioModel.json(source));
        cb.update("Preparing artwork frames...",1);
        final CardCanvas renderer=new CardCanvas(activity); // Create native View on UI thread.
        executor.execute(()->{
            try{
                ensureActive(ticket);
                work=new File(activity.getCacheDir(),"export-"+UUID.randomUUID());if(!work.mkdirs())throw new Exception("Cannot create export staging directory.");
                List<EditedMediaItem> slides=new ArrayList<>();
                if (project.Renderer3AssetName != null && !project.Renderer3AssetName.isBlank()) {
                    File rendered=new File(work,"renderer3-video.mp4");
                    handler.post(()->cb.update("Rendering every authored Renderer3 frame...",2));
                    Renderer3ExportInterop.encode(activity,project,rendered,
                        percent->handler.post(()->{if(active(ticket))cb.update("Renderer3 frame rendering: "+percent+"%",2+percent*23/100);}),
                        ()->!active(ticket));
                    if (!rendered.isFile() || rendered.length()==0)
                        throw new IOException("Renderer3 encoder did not produce video.");
                    slides.add(new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(rendered))).build());
                } else {
                // Motion is derived from Rust's integer frame clock, not random
                // Android UI frame timing. Hold images and short transition clips
                // are assembled into the same Media3 video sequence.

                String projectJson=StudioModel.json(project);
                long totalNativeFrames=NativeEngine.totalFrames(projectJson);
                if(totalNativeFrames<1)throw new Exception("Rust timeline rejected project.");
                final int fps=project.Fps;
                final int framesPerCard=fps*project.SecondsPerCard;
                final int holdFrames=Math.max(1,framesPerCard*3/4);
                final int frameTransitions=framesPerCard-holdFrames;
                int fileIndex=0;
                for(int i=0;i<project.Cards.size();i++){
                    ensureActive(ticket);
                    Bitmap current=renderer.renderBitmap(project.Cards.get(i),false,
                        project.ExportWidth,project.ExportHeight);
                    try{
                        final boolean hasNext=i+1<project.Cards.size();
                        int totalMs=project.SecondsPerCard*1000;
                        int holdMs=hasNext?(int)Math.round(1000d*holdFrames/fps):totalMs;
                        addImageSlide(slides,current,new File(work,String.format(
                            java.util.Locale.ROOT,"frame-%05d.png",fileIndex++)),holdMs,fps);
                        if(hasNext && frameTransitions>0){
                            Bitmap next=renderer.renderBitmap(project.Cards.get(i+1),false,
                                project.ExportWidth,project.ExportHeight);
                            try{
                                int steps=Math.min(8,Math.max(1,frameTransitions));
                                int tailMs=totalMs-holdMs;
                                for(int j=0;j<steps;j++){
                                    ensureActive(ticket);
                                    long clock=(long)i*framesPerCard+holdFrames+
                                        Math.min(frameTransitions-1,Math.round(
                                            (j+.5d)*frameTransitions/steps));
                                    JSONObject scene=new JSONObject(NativeEngine.scene(projectJson,clock));
                                    if(scene.has("error"))throw new Exception(scene.optString("error"));
                                    Bitmap mixed=Bitmap.createBitmap(project.ExportWidth,
                                        project.ExportHeight,Bitmap.Config.ARGB_8888);
                                    try{
                                        Canvas graphics=new Canvas(mixed);
                                        graphics.drawColor(Color.rgb(17,24,37));
                                        Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
                                        float factor=project.ExportWidth/960f;
                                        graphics.drawBitmap(current,
                                            (float)scene.getDouble("currentX")*factor,0,paint);
                                        graphics.drawBitmap(next,
                                            (float)scene.getDouble("nextX")*factor,0,paint);
                                        int ms=(int)((long)(j+1)*tailMs/steps-
                                            (long)j*tailMs/steps);
                                        addImageSlide(slides,mixed,new File(work,String.format(
                                            java.util.Locale.ROOT,"frame-%05d.png",fileIndex++)),
                                            Math.max(1,ms),fps);
                                    } finally { mixed.recycle(); }
                                }
                            }finally{next.recycle();}
                        }
                    }finally{current.recycle();}
                    final int percent=2+(i+1)*23/project.Cards.size();
                    final int cardNumber=i+1;
                    handler.post(()->cb.update("Rendered animated card "+
                        cardNumber+" / "+project.Cards.size(),percent));
                }
                }
                List<EditedMediaItem> tracks=new ArrayList<>();
                for(String reference:project.AudioTracks){
                    ensureActive(ticket);
                    Uri u=Uri.parse(reference);
                    boolean readable=false;
                    try(InputStream stream=activity.getContentResolver().openInputStream(u)){readable=stream!=null;}
                    catch(Exception ignored){}
                    if(!readable)throw new Exception("Missing or inaccessible audio: "+M3uPattern.label(reference)+". Relink it in Audio.");
                    tracks.add(new EditedMediaItem.Builder(MediaItem.fromUri(u)).build());
                }
                handler.post(()->{if(active(ticket))startTransformer(project,slides,tracks,destination,cb,ticket);});
            }catch(Throwable e){if(active(ticket))handler.post(()->{if(active(ticket))fail(cb,e.getMessage()==null?e.toString():e.getMessage());});}
        });
    }
    private static void addImageSlide(List<EditedMediaItem> slides,Bitmap bitmap,
        File destination,long milliseconds,int fps) throws Exception {
        try(FileOutputStream stream=new FileOutputStream(destination)){
            if(!bitmap.compress(Bitmap.CompressFormat.PNG,95,stream))
                throw new Exception("Cannot write transition frame.");
        }
        MediaItem image=new MediaItem.Builder()
            .setUri(Uri.fromFile(destination))
            .setImageDurationMs(Math.max(1,milliseconds))
            .build();
        slides.add(new EditedMediaItem.Builder(image).setFrameRate(fps).build());
    }
    private void startTransformer(Project project,List<EditedMediaItem> slides,List<EditedMediaItem> tracks,Uri destination,Listener cb,long ticket){
        if(!active(ticket))return;
        try {
            EditedMediaItemSequence video=EditedMediaItemSequence.withVideoFrom(slides);
            Composition.Builder composition;
            if(tracks.isEmpty())composition=new Composition.Builder(video);
            else{
                EditedMediaItemSequence audio=EditedMediaItemSequence.withAudioFrom(tracks)
                    .buildUpon().setIsLooping(project.AudioRepeat).build();
                composition=new Composition.Builder(video,audio);
                DefaultGainProvider.Builder gains=new DefaultGainProvider.Builder((float)Math.max(0,Math.min(1,project.AudioVolume)));
                long durationUs=(project.Renderer3AssetName != null && !project.Renderer3AssetName.isBlank()) ? Renderer3ExportInterop.durationMillis(activity,project)*1000L : (long)project.Cards.size()*project.SecondsPerCard*1_000_000L;
                if(project.AudioFadeOut){
                    long fadeLength=Math.min(1_500_000L,durationUs);
                    gains.addFadeAt(Math.max(0,durationUs-fadeLength),Math.max(1,fadeLength),DefaultGainProvider.FADE_OUT_LINEAR);
                }
                composition.setEffects(new Effects(Collections.singletonList(new GainProcessor(gains.build())),Collections.emptyList()));
            }
            File finalFile=new File(work,"output.mp4");
            transformer=new Transformer.Builder(activity)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(new Transformer.Listener(){
                    @Override public void onCompleted(Composition comp,ExportResult result){
                        if(!active(ticket))return;
                        stopProgress();
                        cb.update("Saving MP4 to selected location...",97);
                        executor.execute(()->{
                            try(InputStream in=new java.io.FileInputStream(finalFile);
                                OutputStream out=activity.getContentResolver().openOutputStream(destination,"wt")){
                                if(out==null)throw new Exception("Output location is unavailable");
                                byte[] bytes=new byte[128*1024];int n;
                                while((n=in.read(bytes))!=-1){ensureActive(ticket);out.write(bytes,0,n);}
                                out.flush();
                                handler.post(()->{
                                    if(!active(ticket))return;
                                    busy=false;cb.finished("Exported MP4 successfully.");
                                    cleanAsync();
                                });
                            }catch(Throwable e){
                                if(active(ticket))handler.post(()->{if(active(ticket))fail(cb,e.toString());});
                            }
                        });
                    }
                    @Override public void onError(Composition comp,ExportResult result,ExportException exception){
                        if(!active(ticket))return;
                        stopProgress();fail(cb,exception.getMessage());
                    }
                }).build();
            transformer.start(composition.build(),finalFile.getAbsolutePath());
            cb.update("Encoding MP4 on device...",27);
            final ProgressHolder progress=new ProgressHolder();
            progressTask=new Runnable(){
                @Override public void run(){
                    if(!busy||transformer==null||!active(ticket))return;
                    try{
                        int state=transformer.getProgress(progress);
                        if(state==Transformer.PROGRESS_STATE_AVAILABLE)
                            cb.update("Encoding MP4 on device...",27+(int)(progress.progress*0.69f));
                        handler.postDelayed(this,450);
                    }catch(Exception ignored){}
                }
            };
            handler.postDelayed(progressTask,450);
        }catch(Throwable e){fail(cb,e.getMessage()==null?e.toString():e.getMessage());}
    }
    private void stopProgress(){if(progressTask!=null)handler.removeCallbacks(progressTask);}
    private void fail(Listener cb,String reason){
        stopProgress();busy=false;transformer=null;
        cb.error(reason==null?"MP4 export failed":reason);
        cleanAsync();
    }
    private void cleanAsync(){
        executor.execute(()->{
            if(work!=null)deleteRecursive(work);
            work=null;
        });
    }
    private void deleteRecursive(File file){
        if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File x:children)deleteRecursive(x);}
        file.delete();
    }
    public void cancel(){
        generation.incrementAndGet();
        cancelled.set(true);
        if(transformer!=null){transformer.cancel();transformer=null;}
        stopProgress();busy=false;cleanAsync();
    }
}
