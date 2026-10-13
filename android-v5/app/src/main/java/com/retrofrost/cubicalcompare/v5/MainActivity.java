package com.retrofrost.cubicalcompare.v5;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import static com.retrofrost.cubicalcompare.v5.StudioModel.*;

public final class MainActivity extends Activity {
    private static final int OPEN_PROJECT=11,SAVE_PROJECT=12,IMPORT_CSV=13,IMAGE_PICK=14,
        THUMB_IMAGE_PICK=15,ADD_AUDIO=16,OPEN_M3U=17,SAVE_M3U=18,
        EXPORT_THUMBNAIL=19,EXPORT_VIDEO=20,RELINK_AUDIO=21;
    private static final String[] MENUS={"Project","Cards","Thumbnails","Audio","Export","Draw","Settings"};
    private static final String[] LANGS={"English","Português","Español","Français","Deutsch","Русский","日本語","中文","Italiano","Українська"};
    private static final String[][] MENU_TRANSLATIONS={
        {"Project","Cards","Thumbnails","Audio","Export","Draw","Settings"},
        {"Projeto","Cartões","Miniaturas","Áudio","Exportar","Desenhar","Definições"},
        {"Proyecto","Tarjetas","Miniaturas","Audio","Exportar","Dibujar","Ajustes"},
        {"Projet","Cartes","Miniatures","Audio","Exporter","Dessiner","Paramètres"},
        {"Projekt","Karten","Vorschaubilder","Audio","Export","Zeichnen","Einstellungen"},
        {"Проект","Карточки","Миниатюры","Аудио","Экспорт","Рисовать","Настройки"},
        {"プロジェクト","カード","サムネイル","音声","書き出し","描画","設定"},
        {"项目","卡片","缩略图","音频","导出","绘制","设置"},
        {"Progetto","Schede","Miniature","Audio","Esporta","Disegna","Impostazioni"},
        {"Проєкт","Картки","Мініатюри","Аудіо","Експорт","Малювати","Налаштування"}};
    private static final String[] ICONS={"▣","▦","▧","♫","↗","✎","⚙"};
    private Project project=StudioModel.blank();
    private int selected=0;
    private int selectedAudio=-1;
    private String menu="Cards";
    private int theme=0,language=0; // theme 0 auto, 1 dark, 2 light
    private boolean softwarePreview=true;
    private boolean exporting=false;
    private boolean pickerIsThumbnail;
    private int background, surface, surfaceAlt, foreground, muted, accent;
    private LinearLayout root, panel, navRow;
    private CardCanvas canvas;
    private TextView status;
    private ProgressBar exportProgress;
    private VideoExporter exporter;
    private MediaPlayer audioPreview;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        theme=getPreferences(0).getInt("theme",0);
        language=getPreferences(0).getInt("language",0);
        softwarePreview=getPreferences(0).getBoolean("software",true);
        exporter=new VideoExporter(this);
        try{
            String saved=getPreferences(0).getString("autosave","");
            if(!saved.isEmpty())project=StudioModel.parse(saved);
        }catch(Exception ignored){}
        if(project.Cards.isEmpty())project=StudioModel.blank();
        calculatePalette();renderUi();
    }
    @Override public void onConfigurationChanged(Configuration config){
        super.onConfigurationChanged(config);
        if(theme==0)calculatePalette();
        renderUi();
    }
    @Override protected void onPause(){super.onPause();saveAutosave();if(audioPreview!=null){audioPreview.release();audioPreview=null;}}
    @Override protected void onDestroy(){
        if(exporter!=null && exporter.isBusy())exporter.cancel();
        if(audioPreview!=null)audioPreview.release();
        super.onDestroy();
    }
    private void saveAutosave(){
        try{
            if(canvas!=null)canvas.persistInk();
            getPreferences(0).edit().putString("autosave",StudioModel.json(project)).apply();
        }catch(Exception e){ /* user still has manual .ccv5 save option */ }
    }
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private void calculatePalette(){
        boolean dark=theme==1||(theme==0&&(getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
        background=Color.parseColor(dark?"#101722":"#EDF4FA");
        surface=Color.parseColor(dark?"#1C2A3D":"#FFFFFF");
        surfaceAlt=Color.parseColor(dark?"#28394F":"#DDE9F3");
        foreground=Color.parseColor(dark?"#F6F9FC":"#172738");
        muted=Color.parseColor(dark?"#ADBED0":"#627589");
        accent=Color.parseColor("#35C2D5");
        if(Build.VERSION.SDK_INT>=31){
            try{accent=getColor(android.R.color.system_accent1_500);}catch(Exception ignored){}
        }
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        getWindow().getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
    private GradientDrawable shape(int color,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private GradientDrawable outline(int color,int radius,int stroke){
        GradientDrawable d=shape(color,radius);d.setStroke(dp(1),stroke);return d;
    }
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private TextView label(String text,int size,int colour,boolean bold){
        TextView t=new TextView(this);t.setText(text);t.setTextColor(colour);t.setTextSize(size);t.setIncludeFontPadding(true);
        if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        return t;
    }
    private void pad(View v,int all){v.setPadding(dp(all),dp(all),dp(all),dp(all));}
    private void space(LinearLayout l,int height){View v=new View(this);l.addView(v,lp(1,height));}
    private void section(LinearLayout l,String text){
        TextView t=label(text,18,foreground,true);t.setPadding(dp(2),dp(11),dp(2),dp(7));l.addView(t,lp(-1,-2));
    }
    private void hint(LinearLayout l,String message){
        TextView t=label(message,12,muted,false);t.setPadding(dp(3),0,dp(3),dp(9));l.addView(t,lp(-1,-2));
    }
    private Button button(String text,Runnable action){
        Button b=new Button(this);b.setText(text);b.setTextColor(foreground);b.setTextSize(12);b.setAllCaps(false);
        b.setBackground(outline(surfaceAlt,13,(accent&0x00ffffff)|0x35000000));b.setMinHeight(0);b.setMinimumHeight(0);
        b.setPadding(dp(11),dp(9),dp(11),dp(9));b.setOnClickListener(v->action.run());
        return b;
    }
    private void action(LinearLayout target,String text,Runnable run){
        Button b=button(text,run);LinearLayout.LayoutParams p=lp(-1,43);p.bottomMargin=dp(7);target.addView(b,p);
    }
    private void actions(LinearLayout target,String a,Runnable first,String b,Runnable second){
        LinearLayout r=row();
        Button l=button(a,first),right=button(b,second);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(43),1);p.rightMargin=dp(5);
        r.addView(l,p);LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(0,dp(43),1);r.addView(right,q);
        LinearLayout.LayoutParams rp=lp(-1,-2);rp.bottomMargin=dp(8);target.addView(r,rp);
    }
    private void field(LinearLayout parent,String title,String initial,Consumer<String> changed){
        TextView label=label(title,12,muted,false);label.setPadding(dp(3),dp(5),0,0);parent.addView(label);
        EditText input=new EditText(this);input.setText(initial==null?"":initial);
        input.setTextColor(foreground);input.setHintTextColor(muted);input.setSingleLine(true);input.setTextSize(14);
        input.setSelectAllOnFocus(false);input.setPadding(dp(12),dp(8),dp(12),dp(8));
        input.setBackground(shape(surfaceAlt,11));
        LinearLayout.LayoutParams p=lp(-1,43);p.topMargin=dp(4);p.bottomMargin=dp(10);
        parent.addView(input,p);
        input.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                changed.accept(s.toString());if(canvas!=null)canvas.refresh();
            }
            public void afterTextChanged(Editable e){}
        });
    }
    private void seek(LinearLayout target,String name,int min,int max,int value,Consumer<Integer> changed){
        LinearLayout header=row();TextView title=label(name,12,muted,false);
        TextView amount=label(String.valueOf(value),12,foreground,true);
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));header.addView(amount);
        target.addView(header);
        SeekBar bar=new SeekBar(this);bar.setMax(max-min);bar.setProgress(Math.max(0,Math.min(max-min,value-min)));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
        target.addView(bar,lp(-1,38));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int progress,boolean user){
                if(!user)return;int v=progress+min;amount.setText(String.valueOf(v));
                changed.accept(v);if(canvas!=null)canvas.refresh();
            }
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){saveAutosave();}
        });
    }
    private void check(LinearLayout l,String name,boolean initial,Consumer<Boolean> changed){
        CheckBox c=new CheckBox(this);c.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
        c.setText(name);c.setTextColor(foreground);c.setTextSize(13);c.setChecked(initial);
        c.setOnCheckedChangeListener((button,checked)->{changed.accept(checked);if(canvas!=null)canvas.refresh();});
        l.addView(c,lp(-1,43));
    }
    private void toast(String txt){Toast.makeText(this,txt,Toast.LENGTH_LONG).show();}
    private Card active(){
        if(project.Cards.isEmpty())project.Cards.add(new Card());
        selected=Math.max(0,Math.min(selected,project.Cards.size()-1));
        return project.Cards.get(selected);
    }
    private void renderUi(){
        if(canvas!=null)canvas.persistInk();
        root=column();root.setBackgroundColor(background);
        FrameLayout outer=new FrameLayout(this);outer.setBackgroundColor(background);
        setContentView(outer);
        LinearLayout.LayoutParams full=lp(-1,-1);
        outer.addView(root,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout header=row();header.setPadding(dp(15),dp(8),dp(15),dp(1));
        TextView mark=label("◈",29,accent,true);header.addView(mark,lp(36,40));
        LinearLayout brand=column();brand.addView(label("Cubical Compare",16,foreground,true));brand.addView(label("V5 · ANDROID",10,muted,true));
        header.addView(brand);root.addView(header,lp(-1,52));

        HorizontalScrollView scroll=new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);scroll.setFillViewport(false);
        navRow=row();navRow.setPadding(dp(7),dp(4),dp(7),dp(4));
        navRow.setBackground(outline(surface,28,(accent&0x00ffffff)|0x55000000));navRow.setElevation(dp(12));
        for(int i=0;i<MENUS.length;i++){
            final String id=MENUS[i];boolean active=id.equals(menu);
            TextView n=label(ICONS[i]+"\n"+MENU_TRANSLATIONS[Math.max(0,Math.min(9,language))][i],11,active?Color.WHITE:foreground,active);
            n.setGravity(Gravity.CENTER);n.setMinWidth(dp(69));n.setPadding(dp(9),dp(7),dp(9),dp(7));
            if(active)n.setBackground(shape(Color.rgb(18,109,160),20));
            n.setOnClickListener(v->{if(canvas!=null)canvas.persistInk();menu=id;renderUi();});
            navRow.addView(n,lp(-2,53));
        }
        scroll.addView(navRow);
        LinearLayout navHost=row();navHost.setGravity(Gravity.CENTER);
        navHost.setPadding(dp(10),dp(4),dp(10),dp(9));
        navHost.addView(scroll,new LinearLayout.LayoutParams(-1,dp(70)));
        root.addView(navHost,lp(-1,83));

        boolean landscape=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE
            && getResources().getDisplayMetrics().widthPixels>=dp(670);
        ScrollView scroller=new ScrollView(this);scroller.setFillViewport(true);
        LinearLayout workspace=landscape?row():column();
        workspace.setGravity(Gravity.TOP);workspace.setPadding(dp(11),dp(2),dp(11),dp(8));
        scroller.addView(workspace);root.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout preview=column();preview.setBackground(outline(surface,18,(accent&0x00ffffff)|0x33000000));
        pad(preview,10);preview.setElevation(dp(3));
        LinearLayout previewTitle=row();
        previewTitle.addView(label(menu.equals("Thumbnails")?"THUMBNAIL / LIVE":"CANVAS / LIVE",11,muted,true));
        TextView resolution=label("  •  16:9",11,muted,false);previewTitle.addView(resolution);
        preview.addView(previewTitle);
        canvas=new CardCanvas(this);canvas.setCard(active());
        boolean thumb=menu.equals("Thumbnails");
        canvas.setThumbnail(thumb);canvas.setTool(menu.equals("Draw")?CardCanvas.Tool.DRAW:CardCanvas.Tool.MOVE);
        canvas.onChanged(()->{saveAutosave();});
        LinearLayout.LayoutParams vp=lp(-1,-2);vp.topMargin=dp(10);
        preview.addView(canvas,vp);
        TextView help=label(menu.equals("Draw")?"Touch to draw. Choose Move for two-finger pinch/rotate.":
            "Drag to move artwork · pinch to scale and rotate",11,muted,false);
        help.setPadding(0,dp(10),0,dp(2));preview.addView(help);
        LinearLayout.LayoutParams pp=landscape?new LinearLayout.LayoutParams(0,-2,1.25f):lp(-1,-2);
        pp.bottomMargin=dp(10);if(landscape)pp.rightMargin=dp(10);
        workspace.addView(preview,pp);

        ScrollView rightScroll=new ScrollView(this);rightScroll.setFillViewport(false);
        panel=column();panel.setBackground(outline(surface,18,(accent&0x00ffffff)|0x33000000));
        pad(panel,14);panel.setElevation(dp(3));rightScroll.addView(panel);
        LinearLayout.LayoutParams rp=landscape?new LinearLayout.LayoutParams(0,-1,1):lp(-1,-2);
        workspace.addView(rightScroll,rp);
        switch(menu){
            case "Project":projectPage();break;
            case "Cards":cardsPage();break;
            case "Thumbnails":thumbnailPage();break;
            case "Audio":audioPage();break;
            case "Export":exportPage();break;
            case "Draw":drawPage();break;
            case "Settings":settingsPage();break;
            default:cardsPage();
        }
        LinearLayout footer=row();footer.setPadding(dp(16),dp(3),dp(16),dp(7));
        status=label(project.Cards.size()+" cards  ·  "+project.ExportWidth+"×"+project.ExportHeight+"  ·  "+
            project.Fps+" fps",11,muted,false);
        footer.addView(status,new LinearLayout.LayoutParams(0,-2,1));
        exportProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);exportProgress.setVisibility(exporting?View.VISIBLE:View.GONE);
        footer.addView(exportProgress,lp(85,6));
        root.addView(footer,lp(-1,33));
    }

    private void projectPage(){
        section(panel,"Project");
        hint(panel,"The same .ccv5 project format as Cubical Compare V5 Windows. Keep your project, card data and transform settings.");
        field(panel,"Project name",project.ProjectName,v->project.ProjectName=v);
        actions(panel,"New project",this::newProject,"Open .ccv5",()->openFile(OPEN_PROJECT,"application/json"));
        actions(panel,"Save .ccv5",()->saveFile(SAVE_PROJECT,"application/json",project.ProjectName+".ccv5"),
            "Import CSV",()->openFile(IMPORT_CSV,"text/*"));
        section(panel,"Video defaults");
        seek(panel,"Frame rate",15,60,project.Fps,v->project.Fps=v);
        seek(panel,"Seconds per card",1,15,project.SecondsPerCard,v->project.SecondsPerCard=v);
        action(panel,"Reset to 1920 × 1080",()->{project.ExportWidth=1920;project.ExportHeight=1080;toast("Full HD restored");});
        hint(panel,"Project files preserve embedded images, badge colours, Windows strokes, Android drawings and audio playlist references.");
    }
    private void cardsPage(){
        section(panel,"Cards  •  "+project.Cards.size());
        actions(panel,"＋ Add",()->{Card c=new Card();c.Title="Card "+(project.Cards.size()+1);
            c.Badge=String.valueOf(project.Cards.size()+1);project.Cards.add(c);selected=project.Cards.size()-1;renderUi();},
            "Duplicate",()->{
                Card cp=StudioModel.JSON.fromJson(StudioModel.JSON.toJson(active()),Card.class);
                project.Cards.add(selected+1,cp);selected++;renderUi();
            });
        LinearLayout list=column();
        for(int i=0;i<project.Cards.size();i++){
            final int index=i;Card card=project.Cards.get(i);
            Button b=button((index+1)+".  "+card.Title,()->{selected=index;canvas.persistInk();renderUi();});
            if(i==selected)b.setBackground(outline(Color.rgb(23,104,136),14,accent));
            LinearLayout.LayoutParams p=lp(-1,43);p.bottomMargin=dp(4);list.addView(b,p);
        }
        panel.addView(list);
        actions(panel,"↑ Move",()->moveCard(-1),"↓ Move",()->moveCard(1));
        action(panel,"Remove selected card",()->{
            if(project.Cards.size()<=1){toast("At least one card must remain.");return;}
            project.Cards.remove(selected);selected=Math.max(0,selected-1);renderUi();
        });
        section(panel,"Card editor");
        Card c=active();
        field(panel,"Title",c.Title,s->c.Title=s);
        field(panel,"Badge header",c.BadgeHeader,s->c.BadgeHeader=s);
        field(panel,"Badge value",c.Badge,s->c.Badge=s);
        field(panel,"Badge unit",c.BadgeUnit,s->c.BadgeUnit=s);
        field(panel,"Description",c.Description,s->c.Description=s);
        action(panel,"Badge colour",this::pickBadgeColor);
        actions(panel,"Import artwork",()->pickImage(false),"Remove artwork",()->{c.ArtworkBase64="";canvas.refresh();});
        section(panel,"Image transform");transformControls(false);
    }
    private void moveCard(int delta){
        int j=selected+delta;if(j<0||j>=project.Cards.size())return;
        Card c=project.Cards.remove(selected);project.Cards.add(j,c);selected=j;renderUi();
    }
    private void thumbnailPage(){
        section(panel,"Thumbnail editor");
        hint(panel,"Thumbnail-only image transforms never modify the video image. The exported PNG uses the exact same canvas as the preview.");
        actions(panel,"Import thumbnail image",()->pickImage(true),"Use main artwork",()->{
            active().ThumbnailArtworkBase64="";canvas.refresh();
        });
        actions(panel,"Copy main transform",()->{canvas.copyMainTransform();renderUi();},
            "Reset thumbnail transform",()->{canvas.resetTransform();renderUi();});
        section(panel,"Thumbnail transform");
        transformControls(true);
        section(panel,"Thumbnail file");
        action(panel,"Export 1920 × 1080 PNG",()->saveFile(EXPORT_THUMBNAIL,"image/png","CubicalCompare-Thumbnail.png"));
    }
    private void transformControls(boolean thumb){
        Transform t=active().transform(thumb);
        seek(panel,"Scale %",10,800,(int)Math.round(t.Scale*100),v->{t.Scale=v/100d;canvas.refresh();});
        seek(panel,"Position X", -1000,1000,(int)t.X,v->{t.X=v;canvas.refresh();});
        seek(panel,"Position Y", -1000,1000,(int)t.Y,v->{t.Y=v;canvas.refresh();});
        seek(panel,"Rotation", -180,180,(int)t.Rotation,v->{t.Rotation=v;canvas.refresh();});
        check(panel,"Flip horizontally",t.FlipHorizontal,v->t.FlipHorizontal=v);
        check(panel,"Flip vertically",t.FlipVertical,v->t.FlipVertical=v);
        action(panel,"Reset transform",()->{t.reset();renderUi();});
    }
    private void drawPage(){
        section(panel,"Draw as artwork");
        hint(panel,"Draw or erase directly on the canvas. Ink is independent for video cards and thumbnails and survives project saving.");
        actions(panel,"✎ Brush",()->canvas.setTool(CardCanvas.Tool.DRAW),"□ Eraser",()->canvas.setTool(CardCanvas.Tool.ERASE));
        actions(panel,"↔ Move image",()->canvas.setTool(CardCanvas.Tool.MOVE),
            "Clear drawing",()->new AlertDialog.Builder(this).setTitle("Clear drawing?")
              .setMessage("Clear ink on the selected canvas only?")
              .setPositiveButton("Clear",(d,w)->canvas.clearInk())
              .setNegativeButton("Cancel",null).show());
        seek(panel,"Brush size",1,70,9,v->canvas.setBrushSize(v));
        action(panel,"Ink colour",()->{
            new AlertDialog.Builder(this).setTitle("Choose ink colour")
                .setItems(new String[]{"Cyan","Blue","Red","Black","White","Orange"},(d,which)->{
                    int[] colors={0xff21bcd2,0xff1681df,0xffe74653,0xff111522,0xffffffff,0xffffaa33};
                    canvas.setBrushColor(colors[which]);
                }).show();
        });
        action(panel,"Open thumbnail drawing",()->{menu="Thumbnails";renderUi();});
    }
    private void audioPage(){
        section(panel,"Audio patterns");
        hint(panel,"Tracks play consecutively in exactly this order, not alphabetically. Reuse creator playlists by saving an M3U file.");
        actions(panel,"＋ Add audio",()->openFile(ADD_AUDIO,"audio/*",true),"Remove selected",()->{
            if(selectedAudio>=0&&selectedAudio<project.AudioTracks.size()){project.AudioTracks.remove(selectedAudio);
                selectedAudio=Math.min(selectedAudio,project.AudioTracks.size()-1);renderUi();}
        });
        int number=0;
        for(String path:new ArrayList<>(project.AudioTracks)){
            final int ix=number++;
            boolean readable=canRead(path);
            Button item=button((ix+1)+". "+(readable?"♫ ":"⚠ ")+audioName(path),()->{
                selectedAudio=ix;renderUi();
            });
            if(ix==selectedAudio)item.setBackground(outline(Color.rgb(22,108,148),11,accent));
            LinearLayout.LayoutParams p=lp(-1,44);p.bottomMargin=dp(5);panel.addView(item,p);
        }
        actions(panel,"↑ Move up",()->moveAudio(-1),"↓ Move down",()->moveAudio(1));
        actions(panel,"▶ Preview selected",this::playSelectedAudio,"■ Stop",this::stopAudio);
        action(panel,"Relink selected audio",()->{if(selectedAudio>=0)openFile(RELINK_AUDIO,"audio/*");});
        seek(panel,"Volume %",0,100,(int)Math.round(project.AudioVolume*100),v->project.AudioVolume=v/100d);
        check(panel,"Repeat full playlist to video end",project.AudioRepeat,v->project.AudioRepeat=v);
        check(panel,"Fade out audio at video end (default)",project.AudioFadeOut,v->project.AudioFadeOut=v);
        actions(panel,"Save Pattern (.m3u)",()->{
            if(project.AudioTracks.isEmpty()){toast("Add at least one track first.");return;}
            saveFile(SAVE_M3U,"audio/x-mpegurl","My-Comparison-Audio.m3u");
        },"Import Pattern (.m3u)",()->openFile(OPEN_M3U,"*/*"));
        int missing=0;for(String path:project.AudioTracks)if(!canRead(path))missing++;
        if(missing>0)hint(panel,"Warning: "+missing+" track(s) need relinking before MP4 export.");
        hint(panel,"An M3U is a text playlist, not an audio archive. Content URIs generally work on the same device; importing another creator's playlist may require relinking audio files.");
    }
    private void moveAudio(int d){
        int to=selectedAudio+d;if(selectedAudio<0||to<0||to>=project.AudioTracks.size())return;
        String item=project.AudioTracks.remove(selectedAudio);project.AudioTracks.add(to,item);selectedAudio=to;renderUi();
    }
    private void exportPage(){
        section(panel,"Export");
        hint(panel,"Export the actual transformed cards with their artwork and badges, and mux the ordered soundtrack pattern into the MP4.");
        seek(panel,"Frame rate",15,60,project.Fps,v->project.Fps=v);
        seek(panel,"Seconds per card",1,15,project.SecondsPerCard,v->project.SecondsPerCard=v);
        hint(panel,project.Cards.size()+" cards  ·  "+(project.Cards.size()*project.SecondsPerCard)+" seconds  ·  "+project.ExportWidth+" × "+project.ExportHeight);
        action(panel,"Export MP4 (H.264 + AAC)",()->{
            if(exporter.isBusy()){toast("Export already running.");return;}
            saveFile(EXPORT_VIDEO,"video/mp4","CubicalCompare-V5.mp4");
        });
        action(panel,"Export thumbnail PNG",()->saveFile(EXPORT_THUMBNAIL,"image/png","CubicalCompare-Thumbnail.png"));
        hint(panel,"Video export uses Media3 and your device's Android video encoder. On older GPUs, lower the output to 1280 × 720 if Full HD encoding is unsupported.");
        actions(panel,"1280 × 720",()->{project.ExportWidth=1280;project.ExportHeight=720;renderUi();},
            "1920 × 1080",()->{project.ExportWidth=1920;project.ExportHeight=1080;renderUi();});
    }
    private void settingsPage(){
        section(panel,"Appearance");
        hint(panel,"Android Material-style surfaces with system dynamic accent on Android 12+. The floating navigation stays consistent on phones and tablets.");
        actions(panel,"Dark",()->setTheme(1),"Light",()->setTheme(2));
        action(panel,"Follow system appearance",()->setTheme(0));
        section(panel,"Language · "+LANGS[language]);
        for(int i=0;i<LANGS.length;i++){
            final int ix=i;
            action(panel,LANGS[i]+(language==i?" ✓":""),()->{language=ix;
                getPreferences(0).edit().putInt("language",ix).apply();renderUi();});
        }
        section(panel,"Compatibility");
        check(panel,"Software canvas preview (older GPU support)",softwarePreview,v->{
            softwarePreview=v;getPreferences(0).edit().putBoolean("software",v).apply();
            if(canvas!=null)canvas.setLayerType(v?View.LAYER_TYPE_SOFTWARE:View.LAYER_TYPE_HARDWARE,null);
        });
        hint(panel,"Drawing is CPU-backed on low-end devices. MP4 still uses the Android media encoder; software preview cannot replace missing H.264 encoder support.");
        action(panel,"Project information",()->new AlertDialog.Builder(this)
            .setTitle("Cubical Compare V5 · Android")
            .setMessage("Seven floating menus · .ccv5 projects · thumbnail-only transforms · drawing · M3U audio patterns · Media3 export. No in-app changelog or What's New pop-ups.")
            .setPositiveButton("OK",null).show());
    }
    private void setTheme(int t){
        theme=t;getPreferences(0).edit().putInt("theme",t).apply();
        calculatePalette();renderUi();
    }
    private void pickBadgeColor(){
        Card c=active();LinearLayout picker=column();pad(picker,16);
        int base;try{base=Color.parseColor(c.BadgeColor);}catch(Exception e){base=0xffed3448;}
        final int[] channel={Color.red(base),Color.green(base),Color.blue(base)};
        View sample=new View(this);sample.setBackground(shape(base,12));picker.addView(sample,lp(-1,48));
        String[] names={"Red","Green","Blue"};
        for(int i=0;i<3;i++){
            final int x=i;seek(picker,names[i],0,255,channel[i],v->{
                channel[x]=v;int color=Color.rgb(channel[0],channel[1],channel[2]);
                sample.setBackground(shape(color,12));c.BadgeColor=String.format(Locale.ROOT,"#%02X%02X%02X",channel[0],channel[1],channel[2]);
                canvas.refresh();
            });
        }
        new AlertDialog.Builder(this).setTitle("Badge colour").setView(picker)
            .setPositiveButton("Use colour",(d,w)->{canvas.refresh();saveAutosave();}).show();
    }
    private void newProject(){
        new AlertDialog.Builder(this).setTitle("New project")
            .setMessage("Replace the current project? Save it to .ccv5 first if needed.")
            .setNegativeButton("Cancel",null).setPositiveButton("New",(d,w)->{
                stopAudio();project=StudioModel.blank();selected=0;selectedAudio=-1;menu="Cards";renderUi();saveAutosave();
            }).show();
    }
    private void pickImage(boolean thumb){pickerIsThumbnail=thumb;openFile(thumb?THUMB_IMAGE_PICK:IMAGE_PICK,"image/*");}
    private void openFile(int req,String mime){openFile(req,mime,false);}
    private void openFile(int req,String mime,boolean multi){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType(mime);i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,multi);
        startActivityForResult(i,req);
    }
    private void saveFile(int req,String mime,String name){
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(mime);i.putExtra(Intent.EXTRA_TITLE,name);
        startActivityForResult(i,req);
    }
    private String readUtf8(Uri uri)throws Exception{
        try(InputStream in=getContentResolver().openInputStream(uri)){
            if(in==null)throw new Exception("File not accessible.");
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
            while((n=in.read(buf))!=-1){
                if(out.size()>72*1024*1024)throw new Exception("File is larger than 72 MB.");
                out.write(buf,0,n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    private void writeUtf8(Uri uri,String s)throws Exception{
        try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
            if(out==null)throw new Exception("Cannot create file.");out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
    private void writePng(Uri uri)throws Exception{
        canvas.persistInk();
        Bitmap b=canvas.renderBitmap(active(),true,1920,1080);
        try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
            if(out==null||!b.compress(Bitmap.CompressFormat.PNG,100,out))throw new Exception("Could not save PNG.");
        }finally{b.recycle();}
    }
    private void importImage(Uri uri,boolean thumbnail)throws Exception{
        try(InputStream in=getContentResolver().openInputStream(uri)){
            if(in==null)throw new Exception("Cannot open selected image.");
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
            while((n=in.read(buf))!=-1){
                out.write(buf,0,n);
                if(out.size()>18*1024*1024)throw new Exception("Image exceeds 18 MB.");
            }
            byte[] bytes=out.toByteArray();
            if(android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,new android.graphics.BitmapFactory.Options())==null)
                throw new Exception("Unsupported image format.");
            if(thumbnail)active().ThumbnailArtworkBase64=Base64.encodeToString(bytes,Base64.NO_WRAP);
            else active().ArtworkBase64=Base64.encodeToString(bytes,Base64.NO_WRAP);
        }
    }
    private boolean canRead(String ref){
        try(InputStream in=getContentResolver().openInputStream(Uri.parse(ref))){return in!=null;}catch(Exception e){return false;}
    }
    private String audioName(String ref){
        try(Cursor cur=getContentResolver().query(Uri.parse(ref),new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(cur!=null&&cur.moveToFirst())return cur.getString(0);
        }catch(Exception ignored){}
        return M3uPattern.label(ref);
    }
    private void playSelectedAudio(){
        if(selectedAudio<0||selectedAudio>=project.AudioTracks.size()){toast("Select a track.");return;}
        stopAudio();
        try{
            audioPreview=new MediaPlayer();
            audioPreview.setDataSource(this,Uri.parse(project.AudioTracks.get(selectedAudio)));
            audioPreview.setVolume((float)project.AudioVolume,(float)project.AudioVolume);
            audioPreview.prepareAsync();
            audioPreview.setOnPreparedListener(MediaPlayer::start);
            audioPreview.setOnCompletionListener(p->stopAudio());
        }catch(Exception e){stopAudio();toast("Cannot preview track: "+e.getMessage());}
    }
    private void stopAudio(){
        if(audioPreview!=null){try{audioPreview.stop();}catch(Exception ignored){}
            audioPreview.release();audioPreview=null;}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null)return;
        Uri uri=data.getData();
        if(uri==null&&request!=ADD_AUDIO)return;
        try{
            if(request==ADD_AUDIO){
                List<Uri> uris=new ArrayList<>();
                if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)
                    uris.add(data.getClipData().getItemAt(i).getUri());
                else if(uri!=null)uris.add(uri);
                if(project.AudioTracks.size()+uris.size()>128)throw new Exception("A playlist can contain at most 128 tracks.");
                for(Uri u:uris){persistUri(u);project.AudioTracks.add(u.toString());}
                selectedAudio=project.AudioTracks.size()-1;renderUi();
            }else{
                persistUri(uri);
                switch(request){
                    case OPEN_PROJECT:{
                        Project loaded=StudioModel.parse(readUtf8(uri));
                        canvas.persistInk();project=loaded;selected=0;selectedAudio=-1;menu="Cards";renderUi();toast("V5 project loaded.");break;
                    }
                    case SAVE_PROJECT:canvas.persistInk();writeUtf8(uri,StudioModel.json(project));toast("Project saved.");break;
                    case IMPORT_CSV:{
                        List<Card> cards=StudioModel.parseCsv(readUtf8(uri));
                        project.Cards=cards;selected=0;menu="Cards";renderUi();
                        toast("Imported "+cards.size()+" cards.");break;
                    }
                    case IMAGE_PICK:importImage(uri,false);renderUi();break;
                    case THUMB_IMAGE_PICK:importImage(uri,true);renderUi();break;
                    case SAVE_M3U:writeUtf8(uri,M3uPattern.save(project.AudioTracks));toast("Ordered M3U pattern saved.");break;
                    case OPEN_M3U:{
                        List<String> imported=M3uPattern.read(readUtf8(uri));
                        if(imported.isEmpty())throw new Exception("Playlist is empty.");
                        new AlertDialog.Builder(this).setTitle("Import pattern")
                            .setMessage("Replace current audio playlist with "+imported.size()+" ordered tracks?")
                            .setNegativeButton("Cancel",null).setPositiveButton("Import",(dialog,which)->{
                                project.AudioTracks=new ArrayList<>(imported);selectedAudio=0;renderUi();
                                toast("Imported playlist. Relink unavailable audio if needed.");
                            }).show();break;
                    }
                    case RELINK_AUDIO:{
                        if(selectedAudio>=0&&selectedAudio<project.AudioTracks.size()){
                            project.AudioTracks.set(selectedAudio,uri.toString());renderUi();
                        }break;
                    }
                    case EXPORT_THUMBNAIL:writePng(uri);toast("Thumbnail PNG exported.");break;
                    case EXPORT_VIDEO:runExport(uri);break;
                }
            }
            saveAutosave();
        }catch(Exception e){new AlertDialog.Builder(this).setTitle("Could not complete action")
            .setMessage(e.getMessage()).setPositiveButton("OK",null).show();}
    }
    private void persistUri(Uri uri){
        if(uri==null)return;
        try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
        catch(Exception ignored){}
    }
    private void runExport(Uri output){
        canvas.persistInk();
        exporting=true;
        if(exportProgress!=null)exportProgress.setVisibility(View.VISIBLE);
        exporter.export(project,output,new VideoExporter.Listener(){
            public void update(String message,int percent){
                if(status!=null)status.setText(message+" · "+percent+"%");
                if(exportProgress!=null){exportProgress.setProgress(percent);exportProgress.setVisibility(View.VISIBLE);}
            }
            public void finished(String message){
                exporting=false;if(exportProgress!=null)exportProgress.setVisibility(View.GONE);
                toast(message);if(status!=null)status.setText(message);
            }
            public void error(String reason){
                exporting=false;if(exportProgress!=null)exportProgress.setVisibility(View.GONE);
                new AlertDialog.Builder(MainActivity.this).setTitle("Export failed")
                    .setMessage(reason).setPositiveButton("OK",null).show();
            }
        });
    }
}