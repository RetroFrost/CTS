package com.retrofrost.cubicalcompare.v5;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.Base64;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.View;
import java.io.ByteArrayOutputStream;
import java.util.Locale;
import static com.retrofrost.cubicalcompare.v5.StudioModel.*;

public final class CardCanvas extends View {
    public enum Tool { MOVE, DRAW, ERASE }
    public static final int DESIGN_W=960, DESIGN_H=540;
    private Card card;
    private boolean thumbnail;
    private Tool tool=Tool.MOVE;
    private float brushSize=9;
    private int brushColor=Color.rgb(27,110,155);
    private Bitmap ink;
    private Canvas inkCanvas;
    private float lastX,lastY,lastDistance,lastAngle;
    private boolean drawing=false;
    private int draggingPointers=0;
    private Runnable changeCallback=()->{};
    private final Paint p=new Paint(3);
    private final LruCache<String,Bitmap> images=new LruCache<String,Bitmap>(18*1024*1024){
        @Override protected int sizeOf(String k, Bitmap v){return v.getByteCount();}
        // Let Android recycle no-longer-referenced bitmaps normally. Explicit
        // recycle() could race with a pending Canvas.drawBitmap() call.
    };
    public CardCanvas(Context c){super(c);init();}
    public CardCanvas(Context c, AttributeSet a){super(c,a);init();}
    private void init(){setLayerType(View.LAYER_TYPE_SOFTWARE,null);setBackgroundColor(Color.rgb(10,19,31));}
    public void setCard(Card c){persistInk();this.card=c;loadInk();invalidate();}
    public void setThumbnail(boolean b){if(thumbnail==b)return;persistInk();thumbnail=b;loadInk();invalidate();}
    public boolean isThumbnail(){return thumbnail;}
    public void setTool(Tool t){tool=t;}
    public Tool tool(){return tool;}
    public void setBrushSize(float size){brushSize=Math.max(1,Math.min(90,size));}
    public void setBrushColor(int color){brushColor=color;}
    public void onChanged(Runnable run){changeCallback=run;}
    public void clearInk(){ if(ink!=null){ink.eraseColor(Color.TRANSPARENT);persistInk();invalidate();} }
    public void copyMainTransform(){if(card==null)return; card.ThumbnailTransform=card.transform(false).copy();invalidate();changeCallback.run();}
    public void resetTransform(){if(card!=null){card.transform(thumbnail).reset();invalidate();changeCallback.run();}}
    public void refresh(){invalidate();}
    private void loadInk(){
        ink=Bitmap.createBitmap(DESIGN_W,DESIGN_H,Bitmap.Config.ARGB_8888);
        inkCanvas=new Canvas(ink);
        if(card==null)return;
        String blob=card.drawing(thumbnail);
        if(!blob.isEmpty())try{
            byte[] raw=Base64.decode(blob,Base64.DEFAULT);
            Bitmap saved=BitmapFactory.decodeByteArray(raw,0,raw.length);
            if(saved!=null){inkCanvas.drawBitmap(saved,null,new RectF(0,0,DESIGN_W,DESIGN_H),p);saved.recycle();}
        }catch(Exception ignored){}
    }
    public void persistInk(){
        if(card==null || ink==null)return;
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        ink.compress(Bitmap.CompressFormat.PNG,100,out);
        card.setDrawing(thumbnail, Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP));
    }
    @Override protected void onMeasure(int w,int h){
        int width=MeasureSpec.getSize(w);
        int height=(int)Math.max(140,Math.round(width*9.0/16.0));
        if(MeasureSpec.getMode(h)==MeasureSpec.EXACTLY)height=Math.min(height,MeasureSpec.getSize(h));
        setMeasuredDimension(width,height);
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        c.drawColor(Color.rgb(13,21,32));
        float scale=Math.min(getWidth()/(float)DESIGN_W,getHeight()/(float)DESIGN_H);
        c.save();
        c.translate((getWidth()-DESIGN_W*scale)/2f,(getHeight()-DESIGN_H*scale)/2f);
        c.scale(scale,scale);
        render(c,card,thumbnail,ink);
        c.restore();
    }
    public Bitmap renderBitmap(Card which,boolean thumb,int width,int height){
        Bitmap output=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(output);
        c.scale(width/(float)DESIGN_W,height/(float)DESIGN_H);
        Bitmap overlay=null;
        if(which==card && thumb==thumbnail)overlay=ink;
        else {
            String base=which.drawing(thumb);
            if(!base.trim().isEmpty())try{
                byte[] bytes=Base64.decode(base,Base64.DEFAULT);
                overlay=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
            }catch(Exception ignored){}
        }
        render(c,which,thumb,overlay);
        if(overlay!=null && overlay!=ink && !overlay.isRecycled())overlay.recycle();
        return output;
    }
    private void fill(Canvas c,int color,float x,float y,float right,float bottom,float radius){
        p.reset();p.setAntiAlias(true);p.setColor(color);p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(x,y,right,bottom,radius,radius,p);
    }
    private void text(Canvas c,String value,float size,int color,float x,float baseline,boolean bold){
        p.reset();p.setAntiAlias(true);p.setColor(color);p.setTextSize(size);p.setFakeBoldText(bold);
        p.setTypeface(bold?android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.BOLD):
                android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL));
        c.drawText(value==null?"":value,x,baseline,p);
    }
    private String limit(String value,int len){
        if(value==null)return "";
        return value.length()<=len?value:value.substring(0,len-1)+"…";
    }
    private int badgeColor(Card current){
        try{return Color.parseColor(current.BadgeColor);}catch(Exception e){return Color.rgb(236,57,77);}
    }
    private Bitmap decode(String b64){
        if(b64==null||b64.isEmpty())return null;
        String key=b64.length()+":"+b64.hashCode();
        Bitmap existing=images.get(key);
        if(existing!=null&&!existing.isRecycled())return existing;
        try{
            byte[] bytes=Base64.decode(b64,Base64.DEFAULT);
            BitmapFactory.Options bounds=new BitmapFactory.Options();
            bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
            int sample=1;while(bounds.outWidth/sample>1900 || bounds.outHeight/sample>1500)sample*=2;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;
            Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
            if(image!=null)images.put(key,image);
            return image;
        }catch(Exception ignore){return null;}
    }
    private void render(Canvas c,Card current,boolean thumb,Bitmap overlay){
        p.reset();p.setAntiAlias(true);
        if(thumb){
            p.setShader(new LinearGradient(0,0,960,540,Color.rgb(13,41,68),Color.rgb(2,103,123),Shader.TileMode.CLAMP));
            c.drawRect(0,0,960,540,p);p.setShader(null);
            fill(c,Color.rgb(19,44,68),488,18,945,528,26);
            text(c,"CUBICAL COMPARE",19,Color.rgb(112,232,239),45,102,true);
        }else{
            c.drawColor(Color.rgb(17,24,37));
            fill(c,Color.rgb(245,248,252),165,12,795,528,18);
        }
        if(current==null){
            text(c,"Create a comparison card",34,Color.WHITE,95,230,true);
            return;
        }
        if(thumb){
            text(c,limit(current.Title,19),43,Color.WHITE,43,176,true);
            fill(c,badgeColor(current),44,213,445,281,12);
            text(c,limit(current.BadgeHeader+" "+current.Badge+" "+current.BadgeUnit,21),23,Color.WHITE,66,255,true);
        }else{
            text(c,limit(current.Title,35),32,Color.rgb(13,23,40),189,67,true);
            fill(c,badgeColor(current),300,92,660,149,12);
            text(c,limit(current.BadgeHeader+" "+current.Badge+" "+current.BadgeUnit,28),24,Color.WHITE,321,129,true);
            text(c,limit(current.Description,58),17,Color.rgb(34,55,73),189,502,false);
        }
        RectF artwork=thumb?new RectF(502,33,934,520):new RectF(189,164,771,474);
        c.save();c.clipRect(artwork);
        Bitmap bitmap=decode(current.artwork(thumb));
        if(bitmap!=null){
            Transform t=current.transform(thumb);
            float sc=(float)Math.max(.02,Math.min(30,t.Scale));
            float fit=Math.min(artwork.width()/bitmap.getWidth(),artwork.height()/bitmap.getHeight());
            c.save();
            c.translate(artwork.centerX()+(float)t.X,artwork.centerY()+(float)t.Y);
            c.rotate((float)t.Rotation);
            c.scale((t.FlipHorizontal?-1:1)*fit*sc,(t.FlipVertical?-1:1)*fit*sc);
            p.reset();p.setAntiAlias(true);p.setFilterBitmap(true);p.setAlpha(255);
            c.drawBitmap(bitmap,-bitmap.getWidth()/2f,-bitmap.getHeight()/2f,p);
            c.restore();
        }else{
            text(c,"＋ Add artwork",25,thumb?Color.rgb(182,227,239):Color.rgb(90,119,148),
                artwork.left+35,artwork.centerY(),true);
        }
        c.restore();
        if(overlay!=null){
            // Artwork drawings must not paint over the title or badge.
            c.save();
            c.clipRect(artwork);
            p.reset();p.setAntiAlias(true);p.setFilterBitmap(true);
            c.drawBitmap(overlay,null,new RectF(0,0,DESIGN_W,DESIGN_H),p);
            c.restore();
        }
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(card==null)return false;
        float scale=Math.min(getWidth()/(float)DESIGN_W,getHeight()/(float)DESIGN_H);
        float ox=(getWidth()-DESIGN_W*scale)/2f,oy=(getHeight()-DESIGN_H*scale)/2f;
        float x=(e.getX()-ox)/scale,y=(e.getY()-oy)/scale;
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                lastX=x;lastY=y;drawing=true;draggingPointers=1;
                if(tool!=Tool.MOVE)stroke(x,y,x,y);
                invalidate();return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if(e.getPointerCount()==2&&tool==Tool.MOVE){
                    lastDistance=distance(e);lastAngle=angle(e);draggingPointers=2;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if(!drawing)return true;
                if(tool==Tool.MOVE) {
                    Transform t=card.transform(thumbnail);
                    if(e.getPointerCount()>1){
                        float newDistance=distance(e),newAngle=angle(e);
                        if(lastDistance>0){t.Scale=Math.max(.1,Math.min(8,t.Scale*newDistance/lastDistance));}
                        t.Rotation=Math.max(-360,Math.min(360,t.Rotation+newAngle-lastAngle));
                        lastDistance=newDistance;lastAngle=newAngle;
                    }else if(draggingPointers==1){
                        t.X=Math.max(-1000,Math.min(1000,t.X+x-lastX));
                        t.Y=Math.max(-1000,Math.min(1000,t.Y+y-lastY));
                    }
                } else stroke(lastX,lastY,x,y);
                lastX=x;lastY=y;invalidate();return true;
            case MotionEvent.ACTION_POINTER_UP:
                draggingPointers=1;lastX=x;lastY=y;return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if(tool!=Tool.MOVE)persistInk();
                drawing=false;draggingPointers=0;changeCallback.run();invalidate();return true;
        }
        return true;
    }
    private float distance(MotionEvent e){
        return (float)Math.hypot(e.getX(0)-e.getX(1),e.getY(0)-e.getY(1));
    }
    private float angle(MotionEvent e){
        return (float)Math.toDegrees(Math.atan2(e.getY(1)-e.getY(0),e.getX(1)-e.getX(0)));
    }
    private void stroke(float x,float y,float dx,float dy){
        if(inkCanvas==null)return;
        Paint pen=new Paint(3);pen.setStyle(Paint.Style.STROKE);
        pen.setStrokeWidth(brushSize);pen.setStrokeCap(Paint.Cap.ROUND);pen.setStrokeJoin(Paint.Join.ROUND);
        if(tool==Tool.ERASE)pen.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        else pen.setColor(brushColor);
        inkCanvas.drawLine(x,y,dx,dy,pen);
        pen.setStyle(Paint.Style.FILL);
        inkCanvas.drawCircle(dx,dy,brushSize/2,pen);
    }
}