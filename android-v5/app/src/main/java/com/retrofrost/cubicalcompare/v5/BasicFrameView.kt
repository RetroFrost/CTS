package com.retrofrost.cubicalcompare.v5

import android.content.Context
import android.graphics.Canvas
import android.view.View

/** Basic timeline preview and export share the same frame painter and integer clock. */
internal class BasicFrameView(context:Context):View(context){
    private var painter:BasicFramePainter?=null
    private var project:StudioModel.Project?=null
    private var revision=-1
    private var frame=0
    fun configure(value:StudioModel.Project,version:Int,clock:Int){
        if(project!==value || revision!=version){
            painter?.close();painter=null;project=value;revision=version
        }
        frame=clock;invalidate()
    }
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        if(width<1||height<1)return
        val p=project?:return
        if(painter==null)painter=BasicFramePainter(context,p,width,height)
        painter?.paint(canvas,frame)
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){painter?.close();painter=null}
    override fun onDetachedFromWindow(){painter?.close();painter=null;super.onDetachedFromWindow()}
}
