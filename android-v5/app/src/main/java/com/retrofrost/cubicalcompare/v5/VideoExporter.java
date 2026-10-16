package com.retrofrost.cubicalcompare.v5;

import android.content.Context;
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
    private final Context activity;
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
    public VideoExporter(Context activity){this.activity=activity;}
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

        executor.execute(()->{
            try{
                ensureActive(ticket);
                work=new File(activity.getCacheDir(),"export-"+UUID.randomUUID());if(!work.mkdirs())throw new Exception("Cannot create export staging directory.");
                List<EditedMediaItem> slides=new ArrayList<>();
                File rendered=new File(work,"rendered-video.mp4");
                handler.post(()->cb.update("Rendering integer timeline frames...",2));
                Renderer3ExportInterop.encode(activity,project,rendered,
                    percent->handler.post(()->{if(active(ticket))cb.update("Rendering video: "+percent+"%",2+percent*83/100);}),
                    ()->!active(ticket));
                if(!rendered.isFile() || rendered.length()==0)throw new IOException("Encoder produced no video.");
                slides.add(new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(rendered))).build());
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
                long durationUs=(project.Renderer3AssetName != null && !project.Renderer3AssetName.isBlank()) ? Renderer3ExportInterop.durationMillis(activity,project)*1000L : NativeEngine.totalFrames(StudioModel.timelineJson(project))*1_000_000L/project.Fps;
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
            cb.update("Muxing MP4 and soundtrack...",86);
            final ProgressHolder progress=new ProgressHolder();
            progressTask=new Runnable(){
                @Override public void run(){
                    if(!busy||transformer==null||!active(ticket))return;
                    try{
                        int state=transformer.getProgress(progress);
                        if(state==Transformer.PROGRESS_STATE_AVAILABLE)
                            cb.update("Muxing MP4 and soundtrack...",86+(int)(progress.progress*0.10f));
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
    public void close(){cancel();executor.shutdown();}
    public void cancel(){
        generation.incrementAndGet();
        cancelled.set(true);
        if(transformer!=null){transformer.cancel();transformer=null;}
        stopProgress();busy=false;cleanAsync();
    }
}
