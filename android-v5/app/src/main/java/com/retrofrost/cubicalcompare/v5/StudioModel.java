package com.retrofrost.cubicalcompare.v5;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.io.StringReader;
import java.io.IOException;
import java.io.BufferedReader;

public final class StudioModel {
    private StudioModel() { }
    public static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    public static final class Transform {
        public double Scale = 1.0;
        public double X = 0;
        public double Y = 0;
        public double Rotation = 0;
        public boolean FlipHorizontal = false;
        public boolean FlipVertical = false;
        public Transform copy() {
            Transform t = new Transform();
            t.Scale=Scale; t.X=X; t.Y=Y; t.Rotation=Rotation;
            t.FlipHorizontal=FlipHorizontal; t.FlipVertical=FlipVertical;
            return t;
        }
        public void reset() { Scale=1; X=0; Y=0; Rotation=0; FlipHorizontal=false; FlipVertical=false; }
    }
    public static final class Card {
        public String Title="New comparison";
        public String Badge="0";
        public String BadgeHeader="Value";
        public String BadgeUnit="";
        public String Description="";
        public String BadgeColor="#EC394D";
        public String ArtworkBase64="";
        public String ArtworkSource="";
        public String ThumbnailArtworkBase64="";
        public String ThumbnailArtworkSource="";
        // Retain Windows WPF InkCanvas stroke bytes even though Android can't decode WPF ISF.
        public String StrokesBase64="";
        public String ThumbnailStrokesBase64="";
        public String AndroidDrawingPngBase64="";
        public String AndroidThumbnailDrawingPngBase64="";
        public Transform ImageTransform=new Transform();
        public Transform ThumbnailTransform=new Transform();
        public Transform transform(boolean thumbnail) {
            if (thumbnail) { if (ThumbnailTransform==null) ThumbnailTransform=new Transform(); return ThumbnailTransform; }
            if (ImageTransform==null) ImageTransform=new Transform();
            return ImageTransform;
        }
        public String artwork(boolean thumbnail) {
            if (thumbnail && ThumbnailArtworkBase64!=null && !ThumbnailArtworkBase64.isEmpty()) return ThumbnailArtworkBase64;
            return ArtworkBase64==null?"":ArtworkBase64;
        }
        public String drawing(boolean thumbnail) {
            return thumbnail ? safe(AndroidThumbnailDrawingPngBase64) : safe(AndroidDrawingPngBase64);
        }
        public void setDrawing(boolean thumbnail,String png) {
            if (thumbnail) AndroidThumbnailDrawingPngBase64=png; else AndroidDrawingPngBase64=png;
        }
    }
    public static final class Project {
        public int FormatVersion=5;
        /** Relative filename of Renderer3 previously validated and imported to private app storage. */
        public String Renderer3AssetName="";
        public String Renderer3Id="";
        public String ProjectName="My Comparison";
        public List<Card> Cards=new ArrayList<>();
        public List<String> AudioTracks=new ArrayList<>();
        public double AudioVolume=1.0;
        public boolean AudioRepeat=true;
        public boolean AudioFadeOut=true;
        public int Fps=30;
        public int SecondsPerCard=2;
        public double VideoDurationSeconds=0;
        public String VideoEncoder="auto";
        public int VideoBitrateMbps=8;
        public int ExportWidth=1920;
        public int ExportHeight=1080;
    }

    public static Project blank() {
        Project p = new Project();
        Card c=new Card(); c.Title="First comparison"; c.BadgeHeader="Rank"; c.Badge="1";
        c.Description="Edit this card to begin."; p.Cards.add(c);
        return p;
    }
    public static Project parse(String json) {
        Project p = JSON.fromJson(json,Project.class);
        if (p==null || p.FormatVersion!=5) throw new IllegalArgumentException("Not a Cubical Compare V5 project.");
        p.ProjectName=safe(p.ProjectName);
        p.Renderer3AssetName=safe(p.Renderer3AssetName);p.Renderer3Id=safe(p.Renderer3Id);
        if (p.Cards==null) p.Cards=new ArrayList<>();
        if (p.AudioTracks==null) p.AudioTracks=new ArrayList<>();
        p.Fps=Math.max(1, Math.min(60,p.Fps==0?30:p.Fps));
        p.SecondsPerCard=Math.max(1,Math.min(3600,p.SecondsPerCard==0?2:p.SecondsPerCard));
        p.ExportWidth=Math.max(320,Math.min(3840,p.ExportWidth<=0?1920:p.ExportWidth));
        p.ExportHeight=Math.max(180,Math.min(2160,p.ExportHeight<=0?1080:p.ExportHeight));
        p.ExportWidth-=p.ExportWidth%2; p.ExportHeight-=p.ExportHeight%2;
        if (!Double.isFinite(p.VideoDurationSeconds)) p.VideoDurationSeconds=0;
        p.VideoDurationSeconds=Math.max(0,Math.min(86400,p.VideoDurationSeconds));
        p.VideoBitrateMbps=Math.max(1,Math.min(80,p.VideoBitrateMbps));
        if (!java.util.Arrays.asList("auto","hardware","software").contains(p.VideoEncoder)) p.VideoEncoder="auto";
        p.AudioVolume=Double.isFinite(p.AudioVolume)?Math.max(0,Math.min(1,p.AudioVolume)):1;
        p.Cards.removeIf(java.util.Objects::isNull);
        p.AudioTracks.removeIf(java.util.Objects::isNull);
        if(p.Cards.isEmpty())p.Cards.add(new Card());
        if(p.Cards.size()>500)throw new IllegalArgumentException("Maximum 500 cards per project");
        if(p.VideoDurationSeconds>0)p.VideoDurationSeconds=Math.max(p.VideoDurationSeconds,(double)p.Cards.size()/p.Fps);
        for(Card c:p.Cards) {
            c.Title=safe(c.Title);c.Badge=safe(c.Badge);c.BadgeHeader=safe(c.BadgeHeader);
            c.BadgeUnit=safe(c.BadgeUnit);c.Description=safe(c.Description);
            c.ArtworkBase64=safe(c.ArtworkBase64);c.ArtworkSource=safe(c.ArtworkSource);
            c.ThumbnailArtworkBase64=safe(c.ThumbnailArtworkBase64);
            if(c.ImageTransform==null)c.ImageTransform=new Transform();
            if(c.ThumbnailTransform==null)c.ThumbnailTransform=new Transform();
            normalize(c.ImageTransform);normalize(c.ThumbnailTransform);
        }
        return p;
    }
    private static void normalize(Transform t){
        t.Scale=Double.isFinite(t.Scale)?Math.max(.1,Math.min(8,t.Scale)):1;
        t.X=Double.isFinite(t.X)?Math.max(-1000,Math.min(1000,t.X)):0;
        t.Y=Double.isFinite(t.Y)?Math.max(-1000,Math.min(1000,t.Y)):0;
        t.Rotation=Double.isFinite(t.Rotation)?t.Rotation%360:0;
    }
    /** RFC 4180, in the creator's canonical column order. CSV references media, not binaries. */
    public static String csv(Project project){
        StringBuilder out=new StringBuilder("Number,Title,Badge Header,Badge Value,Badge Unit,Image,Description,Badge Color\r\n");
        int index=0;
        for(Card c:project.Cards){
            String[] fields={String.valueOf(++index),c.Title,c.BadgeHeader,c.Badge,c.BadgeUnit,c.ArtworkSource,c.Description,c.BadgeColor};
            for(int i=0;i<fields.length;i++){
                if(i>0)out.append(',');
                out.append('"').append(safe(fields[i]).replace("\"","\"\"")).append('"');
            }
            out.append("\r\n");
        }
        return out.toString();
    }
    public static void applyTransformToAll(Project p,Card source,boolean thumbnail){
        for(Card c:p.Cards){if(thumbnail)c.ThumbnailTransform=source.transform(true).copy();
            else c.ImageTransform=source.transform(false).copy();}
    }
    private static String safe(String v){return v==null?"":v;}
    /** JNI timing never needs megabytes of embedded artwork or audio. */
    public static String timelineJson(Project p){
        java.util.Map<String,Object> timing=new java.util.LinkedHashMap<>();
        List<java.util.Map<String,String>> cards=new ArrayList<>();
        for(Card c:p.Cards)cards.add(java.util.Collections.singletonMap("Title",safe(c.Title)));
        timing.put("Cards",cards);timing.put("Fps",p.Fps);
        timing.put("SecondsPerCard",p.SecondsPerCard);timing.put("VideoDurationSeconds",p.VideoDurationSeconds);
        return JSON.toJson(timing);
    }
    public static String json(Project p){return JSON.toJson(p);}
    public static List<Card> parseCsv(String csv) throws IOException {
        List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();
        StringBuilder value=new StringBuilder();boolean quoted=false;
        for(int i=0;i<csv.length();i++){
            char c=csv.charAt(i);
            if(c=='"'){
                if(quoted && i+1<csv.length() && csv.charAt(i+1)=='"'){value.append('"');i++;}
                else quoted=!quoted;
            } else if(c==',' && !quoted){row.add(value.toString());value.setLength(0);}
            else if((c=='\n'||c=='\r')&&!quoted){
                if(c=='\r'&&i+1<csv.length()&&csv.charAt(i+1)=='\n')i++;
                row.add(value.toString());value.setLength(0);rows.add(row);row=new ArrayList<>();
            } else value.append(c);
        }
        if(quoted)throw new IOException("CSV has an unclosed quotation mark.");
        if(value.length()>0||!row.isEmpty()){row.add(value.toString());rows.add(row);}
        if(rows.isEmpty())throw new IOException("CSV is empty");
        List<String> header=rows.get(0);
        for(int i=0;i<header.size();i++)header.set(i,header.get(i).replace("\uFEFF","").trim().toLowerCase(Locale.ROOT));
        if(!header.contains("title"))throw new IOException("CSV requires a Title column.");
        List<Card> cards=new ArrayList<>();
        for(int i=1;i<rows.size();i++){
            List<String> fields=rows.get(i); String title=col(header,fields,"title");
            if(title.trim().isEmpty())continue;
            Card c=new Card();c.Title=title;c.BadgeHeader=col(header,fields,"badge header");
            c.Badge=col(header,fields,"badge value");c.BadgeUnit=col(header,fields,"badge unit");
            c.Description=col(header,fields,"description");
            c.ArtworkSource=col(header,fields,"image");
            String color=col(header,fields,"badge colour");
            if(color.isBlank())color=col(header,fields,"badge color");
            if(!color.isBlank())c.BadgeColor=color;
            // CSV Image is a Windows path/Flaticon URL. Import the data; the UI
            // makes unresolved artwork explicit rather than inventing image bytes.
            cards.add(c);
        }
        if(cards.isEmpty())throw new IOException("CSV contains no cards.");
        return cards;
    }
    private static String col(List<String> h,List<String> row,String key){
        int i=h.indexOf(key); return i>=0 && i<row.size()?row.get(i):"";
    }
}
