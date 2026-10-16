package com.retrofrost.cubicalcompare.v5

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import io.github.retrofrost.cts.android.model.fromV5
import io.github.retrofrost.cts.android.renderer3.RendererV3Container
import io.github.retrofrost.cts.android.renderer3.RendererV3Player
import io.github.retrofrost.cts.android.renderer3.RendererV3Selection
import java.io.File
import java.io.Closeable

/** The actual Windows-compatible Renderer3 reader/validator/player ported from Android stable. */
internal object Renderer3Bridge {
    fun install(context:Context, uri:Uri):RendererV3Selection =
        RendererV3Container.install(context,uri)

    fun list(context:Context):List<RendererV3Selection> =
        File(context.filesDir,"renderer3").listFiles().orEmpty()
            .filter{it.isFile && it.extension.equals("renderer3",true)}
            .mapNotNull{ runCatching{RendererV3Container.open(it,validateAnimations=false).use{pkg->pkg.selection}}.getOrNull()}
            .sortedBy{it.name.lowercase()}

    fun resolve(context:Context, project:StudioModel.Project):RendererV3Selection? {
        val filename=project.Renderer3AssetName.orEmpty()
        if(filename.isBlank())return null
        require(filename.matches(Regex("[a-fA-F0-9-]{36}\\.renderer3"))) {
            "Renderer3 identifier is invalid; import the renderer again"
        }
        val file=File(File(context.filesDir,"renderer3"),filename)
        require(file.isFile) { "Selected Renderer3 is missing: import it again" }
        return RendererV3Container.open(file,validateAnimations=false).use{packageFile->
            val selected=packageFile.selection
            if(project.Renderer3Id.isNotBlank() && project.Renderer3Id!=selected.id)
                error("Renderer3 signature changed; import again")
            selected
        }
    }
    fun fileName(selection:RendererV3Selection)=File(selection.filePath).name
    fun select(project:StudioModel.Project,selection:RendererV3Selection){
        project.Renderer3AssetName=fileName(selection)
        project.Renderer3Id=selection.id
    }
    fun forget(project:StudioModel.Project){
        project.Renderer3AssetName=""
        project.Renderer3Id=""
    }
    fun delete(context:Context, selection:RendererV3Selection) {
        val file=RendererV3Container.checkedProjectFile(context,selection)
        check(file.delete()){"Cannot delete renderer from app storage"}
    }
    fun selectionDetails(selection:RendererV3Selection, cardCount:Int):String =
        selection.name+" • "+selection.width+"×"+selection.height+
            " • "+selection.fps+"fps • "+selection.frameCountForCards(cardCount)+" frames"

    class Session(context:Context, selection:RendererV3Selection,
        project:StudioModel.Project):Closeable {
        private val player=RendererV3Player(context,selection,fromV5(project))
        val frames=selection.frameCountForCards(project.Cards.size).coerceAtLeast(1)
        val fps=selection.fps
        val width=selection.width
        val height=selection.height
        fun paint(canvas:Canvas,frame:Int,outputWidth:Int,outputHeight:Int){
            // The same deterministic frame-addressed evaluator feeds both preview and export.
            val resolved=frame.coerceIn(0,frames-1)
            player.renderFrame(canvas,resolved,outputWidth,outputHeight)
        }
        fun bitmap(frame:Int,w:Int,h:Int):Bitmap {
            val output=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            paint(Canvas(output),frame,w,h)
            return output
        }
        override fun close(){player.close()}
    }
}

/** On-screen Renderer3 playback view; preview uses the same evaluator as video export. */
internal class Renderer3FrameView(context:Context):View(context){
    private var session:Renderer3Bridge.Session?=null
    private var frame=0
    fun configure(project:StudioModel.Project,selected:RendererV3Selection,revision:Int){
        val token=selected.filePath+"|"+revision+"|"+project.Cards.size
        if(token != tag){
            session?.close()
            session=Renderer3Bridge.Session(context,selected,project)
            tag=token
        }
        invalidate()
    }
    fun setFrame(value:Int){frame=value;invalidate()}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        canvas.drawColor(android.graphics.Color.BLACK)
        try{
            session?.paint(canvas,frame.coerceAtLeast(0),width.coerceAtLeast(1),height.coerceAtLeast(1))
        }catch(e:Exception){
            canvas.drawColor(android.graphics.Color.rgb(39,11,19))
            val error=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color=android.graphics.Color.WHITE
                textSize=25f*resources.displayMetrics.density
            }
            canvas.drawText("Renderer3 preview error",24f,54f,error)
            error.textSize=15f*resources.displayMetrics.density
            canvas.drawText(e.message.orEmpty().take(56),24f,88f,error)
            android.util.Log.e("CTS_RENDERER3","Frame render failed",e)
        }
    }
    fun release(){session?.close();session=null;tag=null}
    override fun onDetachedFromWindow(){release();super.onDetachedFromWindow()}
}
