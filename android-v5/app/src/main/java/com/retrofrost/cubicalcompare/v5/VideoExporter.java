package com.retrofrost.cubicalcompare.v5;

import android.app.Activity;
import android.graphics.Bitmap;
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
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private boolean busy;
    private File work;
    private Runnable progressTask;
    public VideoExporter(Activity activity){this.activity=activity;}
    public boolean isBusy(){return busy;}
    public void export(Project source,Uri destination,Listener cb){
        if(busy){cb.error("An export is already running.");return;}
        if(source.Cards==null||source.Cards.isEmpty()){cb.error("Add a card before exporting.");return;}
        busy=true;
        // Clone the data so editing during export can't alter the output mid-run.
        Project project=StudioModel.parse(StudioModel.json(source));
        cb.update("Preparing artwork frames...",1);
        executor.execute(()->{
            try{
                work=new File(activity.getCacheDir(),"export-"+UUID.randomUUID());if(!work.mkdirs())throw new Exception("Cannot create export staging directory.");
                CardCanvas renderer=new CardCanvas(activity);
                List<EditedMediaItem> slides=new ArrayList<>();
                for(int i=0;i<project.Cards.size();i++){
                    File f=new File(work,String.format(java.util.Locale.ROOT,"card-%04d.png",i));
                    Bitmap bmp=renderer.renderBitmap(project.Cards.get(i),false,project.ExportWidth,project.ExportHeight);
                    try(FileOutputStream stream=new FileOutputStream(f)){
                        if(!bmp.compress(Bitmap.CompressFormat.PNG,95,stream))throw new Exception("Could not encode image "+(i+1));
                    } finally{bmp.recycle();}
                    MediaItem item=new MediaItem.Builder().setUri(Uri.fromFile(f))
                        .setImageDurationMs(project.SecondsPerCard*1000L).build();
                    slides.add(new EditedMediaItem.Builder(item).setFrameRate(project.Fps).build());
                    final int percent=2+i*23/project.Cards.size();
                    final int cardNumber=i+1;
                    handler.post(()->cb.update("Rendered card "+cardNumber+" / "+project.Cards.size(),percent));
                }
                List<EditedMediaItem> tracks=new ArrayList<>();
                for(String reference:project.AudioTracks){
                    Uri u=Uri.parse(reference);
                    boolean readable=false;
                    try(InputStream stream=activity.getContentResolver().openInputStream(u)){readable=stream!=null;}
                    catch(Exception ignored){}
                    if(!readable)throw new Exception("Missing or inaccessible audio: "+M3uPattern.label(reference)+". Relink it in Audio.");
                    tracks.add(new EditedMediaItem.Builder(MediaItem.fromUri(u)).build());
                }
                handler.post(()->startTransformer(project,slides,tracks,destination,cb));
            }catch(Throwable e){handler.post(()->fail(cb,e.getMessage()==null?e.toString():e.getMessage()));}
        });
    }
    private void startTransformer(Project project,List<EditedMediaItem> slides,List<EditedMediaItem> tracks,Uri destination,Listener cb){
        try {
            EditedMediaItemSequence video=EditedMediaItemSequence.withVideoFrom(slides);
            Composition.Builder composition;
            if(tracks.isEmpty())composition=new Composition.Builder(video);
            else{
                EditedMediaItemSequence audio=EditedMediaItemSequence.withAudioFrom(tracks)
                    .buildUpon().setIsLooping(project.AudioRepeat).build();
                composition=new Composition.Builder(video,audio);
                DefaultGainProvider.Builder gains=new DefaultGainProvider.Builder((float)Math.max(0,Math.min(1,project.AudioVolume)));
                long durationUs=(long)project.Cards.size()*project.SecondsPerCard*1_000_000L;
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
                        stopProgress();
                        cb.update("Saving MP4 to selected location...",97);
                        executor.execute(()->{
                            try(InputStream in=new java.io.FileInputStream(finalFile);
                                OutputStream out=activity.getContentResolver().openOutputStream(destination,"wt")){
                                if(out==null)throw new Exception("Output location is unavailable");
                                byte[] bytes=new byte[128*1024];int n;
                                while((n=in.read(bytes))!=-1)out.write(bytes,0,n);
                                out.flush();
                                handler.post(()->{
                                    busy=false;cb.finished("Exported MP4 successfully.");
                                    cleanAsync();
                                });
                            }catch(Throwable e){handler.post(()->fail(cb,e.toString()));}
                        });
                    }
                    @Override public void onError(Composition comp,ExportResult result,ExportException exception){
                        stopProgress();fail(cb,exception.getMessage());
                    }
                }).build();
            transformer.start(composition.build(),finalFile.getAbsolutePath());
            cb.update("Encoding MP4 on device...",27);
            final ProgressHolder progress=new ProgressHolder();
            progressTask=new Runnable(){
                @Override public void run(){
                    if(!busy||transformer==null)return;
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
        if(transformer!=null){transformer.cancel();transformer=null;}
        stopProgress();busy=false;cleanAsync();
    }
}
