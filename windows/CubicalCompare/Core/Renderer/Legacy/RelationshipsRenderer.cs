using SkiaSharp;
using System.Text.RegularExpressions;

namespace CubicalCompare.Windows;

/// <summary>Windows/Skia port of Android RelationshipsFrameRenderer.</summary>
public sealed class RelationshipsRenderer : IDisposable
{
    private const int W=1920,H=1080;
    private readonly Dictionary<string,SKBitmap> _images=new(StringComparer.OrdinalIgnoreCase);

    public SKBitmap Render(StudioProject project,RendererSpec spec,int frame,int width,int height)
    {
        width=Math.Max(2,width);height=Math.Max(2,height);var output=new SKBitmap(new SKImageInfo(width,height,SKColorType.Bgra8888,SKAlphaType.Premul));using var canvas=new SKCanvas(output);canvas.Scale(width/(float)W,height/(float)H);DrawReference(canvas,project,spec,Math.Max(0,frame));canvas.Flush();return output;
    }

    public int FrameCount(StudioProject project,RendererSpec spec)
    {
        if(!project.AutoLength)return Math.Max(1,(int)Math.Round(project.CustomLengthSeconds*Math.Max(1,spec.ReferenceFps)));
        if(spec.CanonicalFrameCount>0&&spec.CanonicalCardCount>0&&project.Cards.Count==spec.CanonicalCardCount)return spec.CanonicalFrameCount;
        return ContentEnd(project,spec)+Math.Max(300,spec.OutroFrames);
    }

    private int ContentEnd(StudioProject project,RendererSpec spec)
    {
        var canonical=spec.Track("relationships.content_end",0);if(canonical!=null&&(spec.CanonicalCardCount<=0||project.Cards.Count==spec.CanonicalCardCount))return (int)Math.Round(canonical.Value);
        if(project.Cards.Count<=4)return (project.Cards.Count==0?0:OpeningStart(spec,project.Cards.Count-1))+180;return EntryFrame(project.Cards.Count,project.Cards.Count-1,spec)+340;
    }
    private static int OpeningStart(RendererSpec spec,int index)=>index<spec.OpeningStarts.Count?spec.OpeningStarts[index]:384+index*140;
    private static int EntryFrame(int projectSize,int index,RendererSpec spec)
    {
        if(index<4)return OpeningStart(spec,index);var target=1920f;var basis=index*spec.SlotPitch;var low=spec.ContinuousStartFrame;var high=(int)Math.Round(spec.Track("relationships.content_end",0)??(low+projectSize*300f));for(var n=0;n<16;n++){var mid=(low+high)/2;var segment=(mid-spec.ContinuousStartFrame)/4096;var scroll=spec.Track($"relationships.scroll.{segment}",mid)??((mid-spec.ContinuousStartFrame)*2f);if(basis-scroll<=target)high=mid;else low=mid+1;}return high;
    }

    private void DrawReference(SKCanvas c,StudioProject project,RendererSpec spec,int frame)
    {
        c.Clear(Color(spec.BackgroundColor));if(project.Cards.Count==0){DrawIntro(c,project,spec,frame);return;}var end=ContentEnd(project,spec);if(frame<OpeningStart(spec,0))DrawIntro(c,project,spec,frame);else if(frame<end)DrawContent(c,project,spec,frame);else DrawOutro(c,project,spec,frame,end);
    }

    private static void DrawIntro(SKCanvas c,StudioProject project,RendererSpec spec,int frame)
    {
        var fade=Smooth(Math.Clamp(frame/36f,0,1));var settle=frame<90?1.42f-.46f*Smooth(frame/90f):.96f+.04f*Smooth(Math.Max(0,180-frame)/90f);var alpha=frame>340?Math.Clamp((384-frame)/44f,0,1):1;var cx=960f;var cy=470f;c.Save();c.Scale(settle,settle,cx,cy);using(var paint=new SKPaint{IsAntialias=true,Style=SKPaintStyle.Stroke,StrokeWidth=9,StrokeCap=SKStrokeCap.Round,Color=new SKColor(216,235,42,(byte)(255*fade*alpha))}){c.DrawArc(new SKRect(cx-250,cy-118,cx-5,cy+118),42,276,false,paint);paint.Color=new SKColor(238,111,139,(byte)(255*fade*alpha));c.DrawArc(new SKRect(cx+5,cy-118,cx+250,cy+118),222,276,false,paint);paint.StrokeWidth=3;paint.Color=new SKColor(58,58,58,(byte)(255*fade*alpha));using var cross=new SKPath();cross.MoveTo(cx-168,cy-86);cross.LineTo(cx+168,cy+86);cross.MoveTo(cx+168,cy-86);cross.LineTo(cx-168,cy+86);c.DrawPath(cross,paint);}c.Restore();if(frame>=170){var chars="Infinite\nComparison";var visible=Math.Clamp((int)((frame-170)/2.4f),0,chars.Length);using var text=TextPaint(project,34,new SKColor(255,255,255,(byte)(255*alpha)),false,"Segoe UI Light");text.TextAlign=SKTextAlign.Center;var lines=chars[..visible].Split('\n');for(var i=0;i<lines.Length;i++)c.DrawText(lines[i],cx,640+i*38,text);}}

    private void DrawContent(SKCanvas c,StudioProject project,RendererSpec spec,int frame)
    {
        var positions=new Dictionary<int,float>();if(frame<spec.ContinuousStartFrame){for(var i=0;i<Math.Min(4,project.Cards.Count);i++)if(frame>=OpeningStart(spec,i))positions[i]=i*spec.SlotPitch;}else{var segment=(frame-spec.ContinuousStartFrame)/4096;var scroll=spec.Track($"relationships.scroll.{segment}",frame)??((frame-spec.ContinuousStartFrame)*2f);for(var i=0;i<project.Cards.Count;i++){var x=i*spec.SlotPitch-scroll;if(x>-spec.SlotPitch&&x<W+spec.SlotPitch)positions[i]=x;}}
        foreach(var pair in positions)DrawCardBody(c,project,project.Cards[pair.Key],pair.Value,spec,frame,pair.Key);if(project.CreditsEnabled&&frame>=OpeningStart(spec,0)&&frame<spec.ContinuousStartFrame)DrawDisclaimer(c,project,frame,spec);foreach(var pair in positions)DrawBadge(c,project,pair.Key,pair.Value,frame,spec);foreach(var pair in positions)if(project.Cards[pair.Key].ImageLayer.Equals("front",StringComparison.OrdinalIgnoreCase))DrawArtwork(c,project.Cards[pair.Key],new SKRect(pair.Value+spec.BodyInset,0,pair.Value+spec.BodyInset+spec.BodyWidth,ImageBottom(project.Cards[pair.Key],spec)),true);
    }

    private static float ImageBottom(StudioCard card,RendererSpec spec)=>Math.Clamp(spec.ImageHeight,0,H);
    private void DrawCardBody(SKCanvas c,StudioProject project,StudioCard card,float slotX,RendererSpec spec,int frame,int index)
    {
        var left=slotX+spec.BodyInset;var right=left+spec.BodyWidth;var titleHeight=string.IsNullOrWhiteSpace(card.Title)?0:spec.TitleHeight;var imageBottom=ImageBottom(card,spec);using var paint=new SKPaint{IsAntialias=true,Style=SKPaintStyle.Fill,Color=new SKColor(30,30,30)};c.DrawRect(left,0,spec.BodyWidth,imageBottom,paint);var local=frame-EntryFrame(int.MaxValue,index,spec);var reveal=index<4?Math.Clamp((local-52)/42f,0,1):1;if(!card.ImageLayer.Equals("front",StringComparison.OrdinalIgnoreCase)&&reveal>0){c.Save();c.ClipRect(new SKRect(left,0,right,imageBottom*Smooth(reveal)));DrawArtwork(c,card,new SKRect(left,0,right,imageBottom),true);c.Restore();}var cursor=imageBottom;if(!string.IsNullOrWhiteSpace(card.Title)){paint.Color=Color(spec.TitleBackgroundColor);c.DrawRect(left,cursor,spec.BodyWidth,titleHeight,paint);DrawFitted(c,project,card.Title,new SKRect(left+10,cursor+1,right-10,cursor+titleHeight-1),Color(spec.TitleTextColor),spec.TitleTextSize,true,1);cursor+=titleHeight;}if(!string.IsNullOrWhiteSpace(card.Description)){paint.Color=Color(spec.DescriptionBackgroundColor);c.DrawRect(left,cursor,spec.BodyWidth,H-cursor,paint);DrawFitted(c,project,card.Description,new SKRect(left+11,cursor+4,right-11,1076),Color(spec.DescriptionTextColor),spec.DescriptionTextSize,false,4);}}

    private void DrawArtwork(SKCanvas c,StudioCard card,SKRect dest,bool cover)
    {
        var bitmap=Load(card.Image);if(bitmap==null)return;var src=new SKRect((float)(bitmap.Width*Math.Clamp(card.ImageCropLeft,0,.95)),(float)(bitmap.Height*Math.Clamp(card.ImageCropTop,0,.95)),(float)(bitmap.Width*(1-Math.Clamp(card.ImageCropRight,0,.95))),(float)(bitmap.Height*(1-Math.Clamp(card.ImageCropBottom,0,.95))));if(src.Width<1||src.Height<1)return;var baseScale=(cover?Math.Max(dest.Width/src.Width,dest.Height/src.Height):Math.Min(dest.Width/src.Width,dest.Height/src.Height));var scale=baseScale*(float)Math.Clamp(card.ImageScale,.05,12);var w=src.Width*scale;var h=src.Height*scale;var cx=dest.MidX+(float)card.ImageX;var cy=dest.MidY+(float)card.ImageY;var target=new SKRect(cx-w/2,cy-h/2,cx+w/2,cy+h/2);c.Save();c.ClipRect(dest);if(card.ImageRotation!=0)c.RotateDegrees((float)card.ImageRotation,cx,cy);using var paint=new SKPaint{IsAntialias=true,FilterQuality=SKFilterQuality.High};c.DrawBitmap(bitmap,src,target,paint);c.Restore();
    }

    private void DrawBadge(SKCanvas c,StudioProject project,int index,float cardX,int frame,RendererSpec spec)
    {
        if(!project.ShowBadges)return;var card=project.Cards[index];if(string.IsNullOrWhiteSpace(card.Value)&&string.IsNullOrWhiteSpace(card.BadgeHeader))return;var local=frame-EntryFrame(project.Cards.Count,index,spec);if(local<0)return;var scale=Math.Clamp(spec.Track("relationships.badge.scale",local)??(local<45?Smooth(local/45f):1),0,1.25f);var y=spec.Track("relationships.badge.y",local)??0;c.Save();c.Translate(cardX,y);c.Scale(scale*spec.BadgeScale,scale*spec.BadgeScale,spec.BadgeCenterX,spec.BadgeCenterY);using var path=Octagon(spec.BadgeCenterX,spec.BadgeCenterY,184,177);using(var fill=new SKPaint{IsAntialias=true,Color=Color(spec.BadgeColor)})c.DrawPath(path,fill);using(var stroke=new SKPaint{IsAntialias=true,Style=SKPaintStyle.Stroke,StrokeWidth=4,Color=Color(spec.BadgeDarkColor)})c.DrawPath(path,stroke);DrawBadgeText(c,project,card,spec);c.Restore();
    }
    private static SKPath Octagon(float cx,float cy,float rx,float ry){var pts=new[]{new SKPoint(cx-92,cy-ry),new SKPoint(cx+92,cy-ry),new SKPoint(cx+rx,cy-88),new SKPoint(cx+rx,cy+86),new SKPoint(cx+92,cy+ry),new SKPoint(cx-92,cy+ry),new SKPoint(cx-rx,cy+86),new SKPoint(cx-rx,cy-88)};var path=new SKPath();path.MoveTo(pts[0]);foreach(var p in pts.Skip(1))path.LineTo(p);path.Close();return path;}
    private static void DrawBadgeText(SKCanvas c,StudioProject project,StudioCard card,RendererSpec spec){var parts=Regex.Split(card.Value.Trim(),"\\s+",RegexOptions.CultureInvariant);var primary=parts.FirstOrDefault()??"";var unit=parts.Length>1?string.Join(' ',parts.Skip(1)):"People";using var paint=TextPaint(project,31,Color(spec.BadgeTextColor),false);paint.TextAlign=SKTextAlign.Center;DrawBadgeLine(c,string.IsNullOrWhiteSpace(card.BadgeHeader)?"1 in":card.BadgeHeader,spec.BadgeCenterX,spec.BadgeCenterY-75,31,17,230,paint);DrawBadgeLine(c,primary,spec.BadgeCenterX,spec.BadgeCenterY+12,72,28,300,paint);DrawBadgeLine(c,unit,spec.BadgeCenterX,spec.BadgeCenterY+70,29,16,245,paint);}
    private static void DrawBadgeLine(SKCanvas c,string text,float x,float y,float preferred,float min,float maxWidth,SKPaint paint){if(string.IsNullOrWhiteSpace(text))return;paint.TextSize=preferred;var measured=Math.Max(1,paint.MeasureText(text));paint.TextSize=measured<=maxWidth?preferred:Math.Max(min,preferred*maxWidth/measured);c.DrawText(text,x,y,paint);}

    private static void DrawDisclaimer(SKCanvas c,StudioProject project,int frame,RendererSpec spec){var first=OpeningStart(spec,0);var p=Math.Clamp((frame-first)/70f,0,1);var x=1450+470*(1-Smooth(p));if(x>=W)return;using var fill=new SKPaint{Color=new SKColor(22,22,22)};c.DrawRect(x,0,W-x,H,fill);var lines=new[]{"DISCLAIMER: This","comparison video","is based on public","data, surveys,","public comments","& discussions and","approximate","estimations that","might be","subjected to some","degree of error."};using var text=TextPaint(project,26,SKColors.LightGray,false);var y=220f;for(var i=0;i<lines.Length;i++){text.Color=i==0?new SKColor(178,0,22):SKColors.LightGray;c.DrawText(lines[i],x+30,y,text);y+=38;}}

    private void DrawOutro(SKCanvas c,StudioProject project,RendererSpec spec,int frame,int contentEnd)
    {
        var local=frame-contentEnd;c.Clear(Color(spec.BackgroundColor));var last=project.Cards[^1];var cardX=spec.Track("relationships.outro.card.x",frame)??(local<80?Lerp(320,781,Smooth(local/80f)):781);DrawCardBody(c,project,last,cardX,spec,frame,project.Cards.Count-1);DrawBadge(c,project,project.Cards.Count-1,cardX,frame,spec);if(local>=58){using var panel=new SKPaint{Color=new SKColor(28,28,28)};c.DrawRect(1290,180,442,730,panel);using var text=TextPaint(project,31,new SKColor(145,145,145),false,"Segoe UI Light");text.TextAlign=SKTextAlign.Center;c.DrawText("WATCH MORE",1511,235,text);}if(local>=70){var full="Which relationship type\nare you in right now?";var chars=Math.Clamp((int)((local-70)*.52f),0,full.Length);DrawTyped(c,project,full[..chars],40,390,37,SKColors.White);}if(local>=225){var full="Comment below!";var chars=Math.Clamp((int)((local-225)*.6f),0,full.Length);DrawTyped(c,project,full[..chars],40,500,37,new SKColor(244,159,0));}if(local>=290){using var text=TextPaint(project,34,new SKColor(224,10,34),true);c.DrawText("SUBSCRIBE",40,900,text);text.Typeface=SKTypeface.FromFamilyName("Segoe UI Light");text.TextSize=30;text.Color=SKColors.LightGray;c.DrawText("for more",220,900,text);c.DrawText("comparison videos.",40,944,text);}var tracked=spec.Track("relationships.outro.fade.alpha",frame);var fade=tracked!=null?Math.Clamp(tracked.Value,0,1):Math.Clamp((local-Math.Max(0,FrameCount(project,spec)-contentEnd-42))/42f,0,1);if(fade>0){using var p=new SKPaint{Color=new SKColor(0,0,0,(byte)(255*fade))};c.DrawRect(0,0,W,H,p);}}
    private static void DrawTyped(SKCanvas c,StudioProject project,string text,float x,float y,float size,SKColor color){using var paint=TextPaint(project,size,color,false);var i=0;foreach(var line in text.Split('\n'))c.DrawText(line,x,y+i++*(size+7),paint);}

    private static void DrawFitted(SKCanvas c,StudioProject project,string text,SKRect box,SKColor color,float preferred,bool bold,int maxLines){if(string.IsNullOrWhiteSpace(text))return;using var paint=TextPaint(project,preferred,color,bold);paint.TextAlign=SKTextAlign.Center;var size=preferred;var lines=Wrap(text,paint,box.Width,maxLines);while((lines.Count>maxLines||lines.Any(l=>paint.MeasureText(l)>box.Width))&&size>11){size--;paint.TextSize=size;lines=Wrap(text,paint,box.Width,maxLines);}var metrics=paint.FontMetrics;var lineH=(metrics.Descent-metrics.Ascent)*.92f;var y=box.MidY-(lines.Count-1)*lineH/2-(metrics.Ascent+metrics.Descent)/2;foreach(var line in lines.Take(maxLines)){c.DrawText(line,box.MidX,y,paint);y+=lineH;}}
    private static List<string> Wrap(string text,SKPaint paint,float width,int maxLines){var words=Regex.Split(text.Trim(),"\\s+",RegexOptions.CultureInvariant).Where(x=>x.Length>0);var lines=new List<string>();var current="";foreach(var word in words){var trial=current.Length==0?word:current+" "+word;if(current.Length==0||paint.MeasureText(trial)<=width)current=trial;else{lines.Add(current);current=word;}}if(current.Length>0)lines.Add(current);if(lines.Count<=maxLines)return lines;return lines.Take(maxLines-1).Concat([string.Join(' ',lines.Skip(maxLines-1))]).ToList();}
    private static SKPaint TextPaint(StudioProject project,float size,SKColor color,bool bold,string fallback="Segoe UI"){SKTypeface face;try{face=!string.IsNullOrWhiteSpace(project.FontFile)&&File.Exists(project.FontFile)?SKTypeface.FromFile(project.FontFile):SKTypeface.FromFamilyName(string.IsNullOrWhiteSpace(project.FontFamily)?fallback:project.FontFamily,bold?SKFontStyle.Bold:SKFontStyle.Normal);}catch{face=SKTypeface.Default;}return new SKPaint{IsAntialias=true,SubpixelText=true,Typeface=face,TextSize=size,Color=color};}
    private SKBitmap? Load(string path){if(string.IsNullOrWhiteSpace(path)||path.StartsWith("http://",StringComparison.OrdinalIgnoreCase)||path.StartsWith("https://",StringComparison.OrdinalIgnoreCase)||!File.Exists(path))return null;if(_images.TryGetValue(path,out var hit))return hit;try{var b=SKBitmap.Decode(path);if(b!=null)_images[path]=b;return b;}catch{return null;}}
    private static float Smooth(float x){var p=Math.Clamp(x,0,1);return p*p*(3-2*p);}private static float Lerp(float a,float b,float p)=>a+(b-a)*Math.Clamp(p,0,1);private static SKColor Color(uint argb)=>new((byte)(argb>>16),(byte)(argb>>8),(byte)argb,(byte)(argb>>24));
    public void Dispose(){foreach(var image in _images.Values.Distinct())image.Dispose();_images.Clear();}
}