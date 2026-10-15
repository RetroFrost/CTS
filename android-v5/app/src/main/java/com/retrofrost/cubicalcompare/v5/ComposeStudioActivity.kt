package com.retrofrost.cubicalcompare.v5

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as MColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** Real Material 3 Android editor. Rust JNI computes deterministic comparison animation scenes. */
class ComposeStudioActivity : ComponentActivity() {
    private val tabs = listOf("Project","Cards","Thumbnails","Renderers","Audio","Export","Draw","Settings")
    private var project by mutableStateOf(StudioModel.blank())
    private var selected by mutableIntStateOf(0)
    private var selectedAudio by mutableIntStateOf(-1)
    private var tab by mutableStateOf("Cards")
    private var rev by mutableIntStateOf(0)
    private var themePref by mutableIntStateOf(0)
    private var language by mutableIntStateOf(0)
    private var drawingThumbnail by mutableStateOf(false)
    private var tool by mutableStateOf(CardCanvas.Tool.MOVE)
    private var status by mutableStateOf("Ready")
    private var exporting by mutableStateOf(false)
    private var exportProgress by mutableFloatStateOf(0f)
    private var playing by mutableStateOf(false)
    private var frame by mutableLongStateOf(0)
    private var colorDialog by mutableStateOf(false)
    private var audioWarning by mutableStateOf("")
    private var newProjectConfirmation by mutableStateOf(false)
    private var renderPreviewOnCards by mutableStateOf(false)
    private var bundleBusy by mutableStateOf(false)
    private val bundleExecutor=java.util.concurrent.Executors.newSingleThreadExecutor()
    private var rendererSelection by mutableStateOf<io.github.retrofrost.cts.android.renderer3.RendererV3Selection?>(null)
    private var audioPlayer: android.media.MediaPlayer? = null
    private var canvas: CardCanvas? = null
    private lateinit var exporter: VideoExporter
    private val autosaveHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingAutosave:Runnable?=null
    private fun writeAutosave(){
        try{
            ink()
            // Take a consistent UI-thread snapshot; write it off the UI thread.
            val snapshot=StudioModel.json(project)
            bundleExecutor.execute{
                try{
                    val temp=File(filesDir,"compose-autosave.tmp")
                    val saved=File(filesDir,"compose-autosave.ccv5")
                    temp.writeText(snapshot)
                    try {
                        java.nio.file.Files.move(temp.toPath(),saved.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                    } catch(_:java.io.IOException) {
                        java.nio.file.Files.move(temp.toPath(),saved.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                }catch(e:Exception){
                    android.util.Log.w("CTS_AUTOSAVE","Unable to save current project",e)
                }
            }
        }catch(e:Exception){
            android.util.Log.w("CTS_AUTOSAVE","Unable to snapshot current project",e)
        }
    }
    private fun scheduleAutosave(){
        pendingAutosave?.let{autosaveHandler.removeCallbacks(it)}
        val task=Runnable{pendingAutosave=null;writeAutosave()}
        pendingAutosave=task
        autosaveHandler.postDelayed(task,2600L)
    }
    private fun changed() {rev++;canvas?.refresh();scheduleAutosave()}
    private fun message(s:String){status=s;Toast.makeText(this,s,Toast.LENGTH_SHORT).show()}
    private fun ink(){canvas?.persistInk()}
    private fun card():StudioModel.Card {
        if(project.Cards.isEmpty()) project.Cards.add(StudioModel.Card())
        selected=selected.coerceIn(0,project.Cards.size-1)
        return project.Cards[selected]
    }
    private fun text(value:String)=UiText.t(value,language)
    private inline fun guarded(action:()->Unit){try{action()}catch(e:Exception){message(e.message?:"Operation failed")}}
    private fun read(uri:Uri):ByteArray = contentResolver.openInputStream(uri)?.use{source->
        val output=ByteArrayOutputStream(); val buffer=ByteArray(8192)
        while(true){val n=source.read(buffer); if(n<0)break
            if(output.size()+n>64*1024*1024)error("File larger than 64MB")
            output.write(buffer,0,n)
        }
        output.toByteArray()
    }?:error("Cannot read file")
    private fun save(uri:Uri,bytes:ByteArray){
        contentResolver.openOutputStream(uri,"wt")?.use{it.write(bytes);it.flush()}?:error("Cannot save file")
    }
    private fun grant(uri:Uri){
        try{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
        catch(_:Exception){}
    }
    private val openProject=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)guarded{
            grant(uri);ink();project=StudioModel.parse(read(uri).toString(Charsets.UTF_8))
            selected=0;tab="Cards"
            rendererSelection=runCatching{Renderer3Bridge.resolve(this,project)}.getOrNull()
            if(project.Renderer3AssetName.isNotBlank() && rendererSelection==null)
                status="Project loaded, but its Renderer3 file needs reimporting on this device."
            changed();message("Project loaded")
        }
    }
    private val saveProject=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        if(uri!=null)guarded{ink();save(uri,StudioModel.json(project).toByteArray(Charsets.UTF_8));message("Project saved")}
    }
    private val importRenderer3=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)guarded{
            val selection=Renderer3Bridge.install(this,uri)
            ink()
            Renderer3Bridge.select(project,selection)
            rendererSelection=selection
            frame=0L
            tab="Renderers"
            changed()
            message("Renderer3 imported: "+selection.name)
        }
    }
    private val openPortable=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null&&!bundleBusy){
            ink();bundleBusy=true;status="Opening complete project bundle..."
            bundleExecutor.execute{
                try{
                    val loaded=PortableProjectBundle.read(this,uri)
                    runOnUiThread{
                        project=loaded.project
                        rendererSelection=loaded.renderer
                        selected=0;selectedAudio=-1;frame=0L;playing=false
                        tab=if(loaded.renderer!=null)"Renderers"else"Cards"
                        bundleBusy=false;changed()
                        message("Complete project restored with renderer and audio.")
                    }
                }catch(e:Exception){
                    runOnUiThread{bundleBusy=false;message("Cannot open portable project: "+e.message)}
                }
            }
        }
    }
    private val savePortable=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->
        if(uri!=null&&!bundleBusy){
            ink()
            val snapshot=StudioModel.parse(StudioModel.json(project))
            bundleBusy=true;status="Packing renderer, artwork and soundtracks..."
            bundleExecutor.execute{
                try{
                    PortableProjectBundle.write(this,snapshot,uri)
                    runOnUiThread{bundleBusy=false;message("Portable project saved, including all selected media.")}
                }catch(e:Exception){
                    runOnUiThread{bundleBusy=false;message("Bundle export failed: "+e.message)}
                }
            }
        }
    }
    private val openCsv=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)guarded{
            ink();project.Cards=StudioModel.parseCsv(read(uri).toString(Charsets.UTF_8))
            selected=0;changed();message("CSV imported")
        }
    }
    private val openArtwork=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)guarded{
            val bytes=read(uri);if(bytes.size>18*1024*1024)error("Image exceeds 18MB")
            val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
            android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            if(bounds.outWidth<1 || bounds.outHeight<1)error("Image format unsupported")
            card().ArtworkBase64=Base64.encodeToString(bytes,Base64.NO_WRAP);changed()
        }
    }
    private val openThumbnail=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)guarded{
            val bytes=read(uri);if(bytes.size>18*1024*1024)error("Image exceeds 18MB")
            card().ThumbnailArtworkBase64=Base64.encodeToString(bytes,Base64.NO_WRAP);changed()
        }
    }
    private val openAudio=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){list->
        if(list.isNotEmpty())guarded{
            if(project.AudioTracks.size+list.size>128)error("Maximum 128 audio tracks")
            list.forEach{grant(it);project.AudioTracks.add(it.toString())}
            selectedAudio=project.AudioTracks.size-1;changed()
        }
    }
    private val relinkAudio=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null && selectedAudio in project.AudioTracks.indices){
            grant(uri);project.AudioTracks[selectedAudio]=uri.toString();changed()
        }
    }
    private val openM3u=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)guarded{
            project.AudioTracks=java.util.ArrayList(M3uPattern.read(read(uri).toString(Charsets.UTF_8)))
            selectedAudio=if(project.AudioTracks.isEmpty())-1 else 0;changed()
            message("M3U pattern imported. Relink unavailable tracks if necessary.")
        }
    }
    private val saveM3u=registerForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")){uri->
        if(uri!=null)guarded{save(uri,M3uPattern.save(project.AudioTracks).toByteArray(Charsets.UTF_8));message("M3U saved")}
    }
    private val savePng=registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")){uri->
        if(uri!=null)guarded{
            ink();val bitmap=(canvas?:CardCanvas(this)).renderBitmap(card(),true,1920,1080)
            try{
                val bytes=ByteArrayOutputStream()
                if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes))error("PNG export failed")
                save(uri,bytes.toByteArray());message("Thumbnail exported")
            }finally{bitmap.recycle()}
        }
    }
    private val saveMp4=registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")){uri->
        if(uri!=null){
            ink();exporting=true;exportProgress=0f
            exporter.export(project,uri,object:VideoExporter.Listener{
                override fun update(message:String,percent:Int){status=message;exportProgress=percent.coerceIn(0,100)/100f}
                override fun finished(message:String){exporting=false;exportProgress=1f;message(message)}
                override fun error(error:String){exporting=false;message("Export failed: "+error)}
            })
        }
    }
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        exporter=VideoExporter(this)
        check(NativeEngine.totalFrames(StudioModel.json(StudioModel.blank())) > 0L) { "Rust timeline engine failed to initialise" }
        themePref=getPreferences(0).getInt("composeTheme",0)
        language=getPreferences(0).getInt("composeLanguage",0)
        val saved=File(filesDir,"compose-autosave.ccv5")
        if(saved.exists())try{project=StudioModel.parse(saved.readText())}catch(_:Exception){}
        rendererSelection=runCatching{Renderer3Bridge.resolve(this,project)}.getOrNull()
        if(BuildConfig.DEBUG && intent.getBooleanExtra("ctsRenderer3SelfTest",false)){
            try{
                val fixture=File(filesDir,"test.renderer3")
                check(fixture.isFile){"Renderer3 fixture is missing"}
                val imported=Renderer3Bridge.install(this,Uri.fromFile(fixture))
                Renderer3Bridge.select(project,imported)
                rendererSelection=imported
                Renderer3Bridge.Session(this,imported,project).use{session->
                    val bitmap=session.bitmap(0,320,180)
                    try{
                        check(bitmap.getPixel(10,10)!=Color.BLACK){"Renderer3 scene did not paint"}
                    }finally{bitmap.recycle()}
                }
                android.util.Log.i("CTS_RENDERER3_SELFTEST","PASS: imported and painted "+imported.name)
                val testAudio=File(cacheDir,"portable-smoke-track.wav")
                testAudio.writeBytes(byteArrayOf(82,73,70,70,0,0,0,0,87,65,86,69))
                project.AudioTracks.clear()
                project.AudioTracks.add(Uri.fromFile(testAudio).toString())
                project.AudioTracks.add(Uri.fromFile(testAudio).toString())
                val testZip=File(cacheDir,"portable-smoke.ctsv5")
                PortableProjectBundle.write(this,project,Uri.fromFile(testZip))
                val restored=PortableProjectBundle.read(this,Uri.fromFile(testZip))
                check(restored.renderer?.id==imported.id){"Renderer lost in portable project"}
                check(restored.project.AudioTracks.size==2){"Audio order lost in portable project"}
                check(restored.project.AudioTracks.all { ref->
                    val bytes=contentResolver.openInputStream(Uri.parse(ref))?.use { it.readBytes() }
                    bytes?.contentEquals(testAudio.readBytes())==true
                }){"Portable audio payload corrupted"}
                android.util.Log.i("CTS_BUNDLE_SELFTEST","PASS: round-trip renderer and ordered embedded audio")
            }catch(e:Exception){
                android.util.Log.e("CTS_RENDERER3_SELFTEST","FAIL",e)
                throw e
            }
        }
        setContent{Studio()}
    }
    override fun onPause(){
        super.onPause();stopAudio()
        pendingAutosave?.let{autosaveHandler.removeCallbacks(it)}
        pendingAutosave=null
        writeAutosave()
    }
    override fun onDestroy(){pendingAutosave?.let{autosaveHandler.removeCallbacks(it)};stopAudio();if(exporter.isBusy)exporter.cancel();bundleExecutor.shutdown();super.onDestroy()}
    private fun navigate(to:String){
        ink();tab=to;playing=false
        if(to=="Thumbnails")drawingThumbnail=true
        if(to=="Cards"){drawingThumbnail=false;renderPreviewOnCards=false}
    }
    private fun savePrefs(){
        getPreferences(0).edit().putInt("composeTheme",themePref)
            .putInt("composeLanguage",language).apply()
        changed()
    }

    @Composable private fun Studio(){
        val systemNight=isSystemInDarkTheme()
        val night=when(themePref){1->true;2->false;else->systemNight}
        val ctx=LocalContext.current
        val colors=if(Build.VERSION.SDK_INT>=31)
            if(night)dynamicDarkColorScheme(ctx)else dynamicLightColorScheme(ctx)
        else if(night)darkColorScheme(primary=MColor(0xFF52CFDA),
            background=MColor(0xFF101A27),surface=MColor(0xFF1B2A39))
        else lightColorScheme(primary=MColor(0xFF006E7D),background=MColor(0xFFF3F7FA))
        MaterialTheme(colorScheme=colors,shapes=Shapes(
            small=RoundedCornerShape(13.dp),medium=RoundedCornerShape(19.dp),
            large=RoundedCornerShape(26.dp),extraLarge=RoundedCornerShape(33.dp))){
            val revision=rev
            val wide=LocalConfiguration.current.screenWidthDp>=700
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.systemBars)){
                Row(Modifier.fillMaxWidth().padding(start=18.dp,end=18.dp,top=12.dp,bottom=10.dp),
                    verticalAlignment=Alignment.CenterVertically){
                    Text("◈",fontSize=34.sp,color=MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)){
                        Text("Cubical Compare",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                        Text("V5 • RUST ENGINE + MATERIAL 3",style=MaterialTheme.typography.labelSmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(exporting||bundleBusy)CircularProgressIndicator(Modifier.size(23.dp),strokeWidth=3.dp)
                }
                Surface(modifier=Modifier.fillMaxWidth().padding(horizontal=11.dp),
                    shape=RoundedCornerShape(28.dp),
                    color=MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation=9.dp,tonalElevation=4.dp){
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(7.dp),
                        verticalAlignment=Alignment.CenterVertically){
                        tabs.forEachIndexed{index,name->
                            FilterChip(selected=tab==name,onClick={navigate(name)},
                                label={Text(text(name),maxLines=1)},
                                leadingIcon={Icon(when(index){
                                    0->Icons.Default.Folder;1->Icons.Default.ViewCarousel
                                    2->Icons.Default.Image;3->Icons.Default.Layers
                                    4->Icons.Default.MusicNote;5->Icons.Default.FileUpload
                                    6->Icons.Default.Brush;else->Icons.Default.Settings
                                },contentDescription=null,modifier=Modifier.size(18.dp))},
                                modifier=Modifier.padding(horizontal=3.dp))
                        }
                    }
                }
                if(exporting)LinearProgressIndicator(progress={exportProgress},
                    modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=4.dp))
                if(wide){
                    Row(Modifier.fillMaxSize().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        Box(Modifier.weight(1.2f)){Stage()}
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())){Editor()}
                    }
                }else{
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)){
                        Stage();Spacer(Modifier.height(13.dp));Editor()
                    }
                }
            }
            if(colorDialog)BadgeDialog()
            if(newProjectConfirmation)AlertDialog(onDismissRequest={newProjectConfirmation=false},
                title={Text("Create a new project?")},
                text={Text("Save your current project first if you want to keep your edits. Unsaved changes will be replaced.")},
                confirmButton={TextButton(onClick={
                    ink();project=StudioModel.blank();rendererSelection=null;selected=0
                    selectedAudio=-1;frame=0L;playing=false;newProjectConfirmation=false;changed()
                }){Text("Create new")}},
                dismissButton={TextButton(onClick={newProjectConfirmation=false}){Text("Cancel")}})
            if(audioWarning.isNotEmpty())AlertDialog(onDismissRequest={audioWarning=""},
                title={Text("Audio duration warning")},text={Text(audioWarning)},
                confirmButton={TextButton(onClick={audioWarning="";saveMp4.launch("CubicalCompare-V5.mp4")}){Text("Export anyway")}},
                dismissButton={TextButton(onClick={audioWarning=""}){Text("Cancel")}})
        }
    }
    @Composable private fun Stage(){
        val revision=rev
        val selectedCard=card()
        val thumb=tab=="Thumbnails"||(tab=="Draw"&&drawingThumbnail)
        ElevatedCard(shape=RoundedCornerShape(25.dp),modifier=Modifier.fillMaxWidth()){
            Column(Modifier.padding(13.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){
                    Text(if(thumb)"THUMBNAIL"else"COMPARISON",style=MaterialTheme.typography.labelMedium,
                        color=MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    Text("1920 × 1080",style=MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(10.dp))
                val activeRenderer=rendererSelection
                if(activeRenderer!=null && (tab=="Renderers" || tab=="Export" || (tab=="Cards"&&renderPreviewOnCards))){
                    val rframe=frame.toInt().coerceIn(0,activeRenderer.frameCountForCards(project.Cards.size).coerceAtLeast(1)-1)
                    AndroidView(factory={ctx->Renderer3FrameView(ctx)},
                        update={view->
                            runCatching{
                                view.configure(project,activeRenderer,rev)
                                view.setFrame(rframe)
                            }.onFailure{status="Renderer3 preview: "+it.message}
                        },modifier=Modifier.fillMaxWidth().aspectRatio(16f/9f))
                } else if(tab=="Export" && playing) {
                    val scene=try{JSONObject(NativeEngine.scene(StudioModel.json(project),frame))}
                        catch(_:Exception){JSONObject()}
                    val current=scene.optInt("currentCard",0).coerceIn(0,project.Cards.size-1)
                    val next=scene.optInt("nextCard",current).coerceIn(0,project.Cards.size-1)
                    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(16f/9f).clip(RoundedCornerShape(15.dp))) {
                        val dx=(maxWidth.value*scene.optDouble("currentX",0.0).toFloat()/960f).dp
                        val nx=(maxWidth.value*scene.optDouble("nextX",960.0).toFloat()/960f).dp
                        AndroidView(factory={ctx->
                            CardCanvas(ctx).apply{setCard(project.Cards[current]);setThumbnail(false)}
                        },update={view->
                            if(view.tag!==project.Cards[current]) {
                                view.setCard(project.Cards[current]);view.tag=project.Cards[current]
                            }
                        },modifier=Modifier.fillMaxSize().offset(x=dx))
                        if(next!=current && scene.optDouble("transition",0.0)>0.0){
                            AndroidView(factory={ctx->
                                CardCanvas(ctx).apply{setCard(project.Cards[next]);setThumbnail(false)}
                            },update={view->
                                if(view.tag!==project.Cards[next]){
                                    view.setCard(project.Cards[next]);view.tag=project.Cards[next]
                                }
                            },modifier=Modifier.fillMaxSize().offset(x=nx))
                        }
                    }
                } else {
                    AndroidView(factory={context->
                        CardCanvas(context).also{
                            it.setCard(selectedCard);it.setThumbnail(thumb)
                            it.tag=selectedCard;it.onChanged(Runnable{changed()});canvas=it
                        }
                    },update={view->
                        if(view.tag !== selectedCard){view.setCard(selectedCard);view.tag=selectedCard}
                        view.setThumbnail(thumb)
                        view.setTool(if(tab=="Draw")tool else CardCanvas.Tool.MOVE)
                        view.refresh();canvas=view
                    },modifier=Modifier.fillMaxWidth().aspectRatio(16f/9f))
                }
                Spacer(Modifier.height(8.dp))
                Text(if(tab=="Draw")"Use your finger to draw • brush and eraser in Draw settings"
                    else "Drag artwork • pinch to scale and rotate • independent thumbnail transformations",
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(tab=="Export" || tab=="Renderers")Timeline()
            }
        }
    }
    @Composable private fun Timeline(){
        val selectedRenderer=rendererSelection
        val revision=rev
        val json=remember(revision,project.Cards.size,project.Fps,project.SecondsPerCard){
            StudioModel.json(project)
        }
        val total=if(selectedRenderer!=null)
            selectedRenderer.frameCountForCards(project.Cards.size).toLong().coerceAtLeast(1)
            else NativeEngine.totalFrames(json).coerceAtLeast(1L)
        val fps=selectedRenderer?.fps ?: project.Fps
        LaunchedEffect(playing,json,selectedRenderer?.id){
            while(playing){
                delay((1000/fps.coerceIn(1,60)).toLong().coerceAtLeast(16))
                frame=(frame+1L)%total
            }
        }
        Row(verticalAlignment=Alignment.CenterVertically){
            TextButton(onClick={playing=!playing}){
                Icon(if(playing)Icons.Default.Pause else Icons.Default.PlayArrow,null)
                Spacer(Modifier.width(6.dp));Text(if(playing)"Pause"else"Play")
            }
            Spacer(Modifier.weight(1f))
            Text((selectedRenderer?.name ?:"Rust timeline")+" · "+frame+"/"+total,
                style=MaterialTheme.typography.labelSmall)
        }
        Slider(value=(frame.toFloat()/total).coerceIn(0f,1f),
            onValueChange={frame=(it*total).toLong().coerceIn(0L,total-1L)})
        Text(if(selectedRenderer!=null)
            "Renderer3 native timeline • "+selectedRenderer.fps+" FPS • "+total+" frames"
            else "Rust card-conveyor timeline • "+project.Fps+" FPS",
            style=MaterialTheme.typography.labelSmall)
    }
    @Composable private fun Editor(){
        ElevatedCard(modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(25.dp)){
            Column(Modifier.fillMaxWidth().padding(17.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp)){
                when(tab){
                    "Project"->ProjectPage()
                    "Cards"->CardsPage()
                    "Thumbnails"->ThumbnailsPage()
                    "Renderers"->Renderer3Page()
                    "Audio"->AudioPage()
                    "Export"->ExportPage()
                    "Draw"->DrawPage()
                    else->SettingsPage()
                }
                Spacer(Modifier.height(4.dp))
                HorizontalDivider()
                Text(status,style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    @Composable private fun Heading(title:String,detail:String?=null){
        Text(text(title),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        if(detail!=null)Text(detail,style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    @Composable private fun Buttons(vararg actions:Pair<String,()->Unit>){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)){
            actions.forEach{(name,action)->
                FilledTonalButton(onClick=action,modifier=Modifier.weight(1f),
                    contentPadding=PaddingValues(horizontal=9.dp,vertical=7.dp)){
                    Text(text(name),fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
            }
        }
    }
    @Composable private fun Edit(label:String,value:String,change:(String)->Unit){
        OutlinedTextField(value=value,onValueChange={change(it);changed()},
            label={Text(text(label))},modifier=Modifier.fillMaxWidth(),
            shape=RoundedCornerShape(14.dp),singleLine=true)
    }
    @Composable private fun MultilineEdit(label:String,value:String,change:(String)->Unit){
        OutlinedTextField(value=value,onValueChange={change(it);changed()},
            label={Text(text(label))},modifier=Modifier.fillMaxWidth(),
            singleLine=false,minLines=3,maxLines=8,shape=RoundedCornerShape(14.dp))
    }
    @Composable private fun Measure(label:String,value:Float,range:ClosedFloatingPointRange<Float>,
        change:(Float)->Unit){
        Column{
            Row{
                Text(text(label),modifier=Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
                Text(value.roundToInt().toString(),style=MaterialTheme.typography.labelMedium)
            }
            Slider(value=value.coerceIn(range.start,range.endInclusive),
                valueRange=range,onValueChange={change(it);changed()})
        }
    }
    @Composable private fun ProjectPage(){
        Heading("Project","Windows V5 compatible .ccv5 card data, artwork and settings.")
        Edit("Project name",project.ProjectName){project.ProjectName=it}
        Buttons("New project" to{newProjectConfirmation=true},
            "Open .ccv5" to{openProject.launch(arrayOf("application/json","*/*"))})
        Buttons("Save .ccv5" to{saveProject.launch(project.ProjectName+".ccv5")},
            "Import CSV" to{openCsv.launch(arrayOf("text/*","*/*"))})
        Heading("Shareable project package")
        Text("A .ccv5 JSON file does not contain renderer or soundtrack files. A .ctsv5 bundle includes the selected Renderer3 and every audio track for other devices.",
            style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        Buttons("Export complete .ctsv5" to{
            if(!bundleBusy)savePortable.launch((project.ProjectName.ifBlank{"Comparison"})+".ctsv5")
        },"Open complete .ctsv5" to{
            if(!bundleBusy)openPortable.launch(arrayOf("application/zip","application/octet-stream","*/*"))
        })
        OutlinedButton(onClick={importRenderer3.launch(arrayOf("*/*"))},modifier=Modifier.fillMaxWidth()){
            Icon(Icons.Default.Layers,null);Spacer(Modifier.width(7.dp));Text("Import Renderer3 (.renderer3 / ZIP)")
        }
        Heading("Video defaults")
        Measure("Frame rate",project.Fps.toFloat(),15f..60f){project.Fps=it.roundToInt()}
        Measure("Seconds per card",project.SecondsPerCard.toFloat(),1f..15f){
            project.SecondsPerCard=it.roundToInt()}
        Text(project.Cards.size.toString()+" cards • "+project.AudioTracks.size+" tracks")
    }
    @Composable private fun CardsPage(){
        Heading("Cards","Touch a card to edit. No fixed card limit on the canvas.")
        Buttons("＋ Add" to{
            ink();val c=StudioModel.Card();c.Title="Card "+(project.Cards.size+1)
            project.Cards.add(c);selected=project.Cards.size-1;changed()
        },"Duplicate" to{
            ink();val copy=StudioModel.JSON.fromJson(StudioModel.JSON.toJson(card()),StudioModel.Card::class.java)
            project.Cards.add(selected+1,copy);selected++;changed()
        })
        // Bound this nested scrolling region: do not compose all 40-50+ cards
        // eagerly or give a LazyColumn an infinite height inside verticalScroll.
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=238.dp),
            verticalArrangement=Arrangement.spacedBy(4.dp)){
            itemsIndexed(project.Cards){index,c->
                Surface(onClick={ink();selected=index;changed()},
                    shape=RoundedCornerShape(13.dp),
                    color=if(index==selected)MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier=Modifier.fillMaxWidth()){
                    Text((index+1).toString()+". "+c.Title,
                        modifier=Modifier.padding(10.dp),maxLines=1,
                        style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        Buttons("↑ Move" to{shiftCard(-1)},"↓ Move" to{shiftCard(1)})
        OutlinedButton(onClick={
            if(project.Cards.size>1){ink();project.Cards.removeAt(selected)
                selected=selected.coerceAtMost(project.Cards.size-1);changed()}
        },modifier=Modifier.fillMaxWidth()){Text(text("Remove selected card"))}
        if(rendererSelection!=null){
            Buttons((if(renderPreviewOnCards)"Edit artwork directly" else "Preview Renderer3") to{
                renderPreviewOnCards=!renderPreviewOnCards;changed()
            })
        }
        Heading("Card editor")
        val c=card()
        Edit("Title",c.Title){c.Title=it}
        Edit("Badge header",c.BadgeHeader){c.BadgeHeader=it}
        Edit("Badge value",c.Badge){c.Badge=it}
        Edit("Badge unit",c.BadgeUnit){c.BadgeUnit=it}
        MultilineEdit("Description",c.Description){c.Description=it}
        Buttons("Badge colour" to{colorDialog=true})
        Buttons("Import artwork" to{openArtwork.launch(arrayOf("image/*"))},
            "Remove artwork" to{c.ArtworkBase64="";changed()})
        Transforms(false)
    }
    private fun shiftCard(offset:Int){
        val next=selected+offset;if(next !in project.Cards.indices)return
        ink();val c=project.Cards.removeAt(selected);project.Cards.add(next,c)
        selected=next;changed()
    }
    @Composable private fun Transforms(thumbnail:Boolean){
        Heading(if(thumbnail)"Thumbnail transform"else"Image transform")
        val t=card().transform(thumbnail)
        Measure("Scale %",(t.Scale*100).toFloat(),10f..800f){t.Scale=it/100.0}
        Measure("Position X",t.X.toFloat(),-1000f..1000f){t.X=it.toDouble()}
        Measure("Position Y",t.Y.toFloat(),-1000f..1000f){t.Y=it.toDouble()}
        Measure("Rotation",t.Rotation.toFloat(),-180f..180f){t.Rotation=it.toDouble()}
        Row(verticalAlignment=Alignment.CenterVertically){
            Checkbox(checked=t.FlipHorizontal,onCheckedChange={t.FlipHorizontal=it;changed()})
            Text(text("Flip horizontally"))
        }
        Row(verticalAlignment=Alignment.CenterVertically){
            Checkbox(checked=t.FlipVertical,onCheckedChange={t.FlipVertical=it;changed()})
            Text(text("Flip vertically"))
        }
        OutlinedButton(onClick={t.reset();changed()},modifier=Modifier.fillMaxWidth()){
            Text(text("Reset transform"))
        }
    }
    @Composable private fun ThumbnailsPage(){
        Heading("Thumbnail editor","Thumbnail transforms and artwork remain separate from video cards.")
        Buttons("Import thumbnail image" to{openThumbnail.launch(arrayOf("image/*"))},
            "Use main artwork" to{card().ThumbnailArtworkBase64="";changed()})
        Buttons("Copy main transform" to{
            card().ThumbnailTransform=card().transform(false).copy();changed()
        })
        Transforms(true)
        Button(onClick={ink();savePng.launch("CubicalCompare-Thumbnail.png")},
            modifier=Modifier.fillMaxWidth()){
            Icon(Icons.Default.Image,null);Spacer(Modifier.width(8.dp));Text("Export thumbnail PNG")
        }
    }
    @Composable private fun Renderer3Page(){
        val revision=rev
        Heading("Renderer3","Import a .renderer3 file or ZIP with renderer assets. Rendering uses the actual Windows-compatible Renderer3 scene engine.")
        Button(onClick={importRenderer3.launch(arrayOf("*/*"))},
            modifier=Modifier.fillMaxWidth()){
            Icon(Icons.Default.FileOpen,null)
            Spacer(Modifier.width(8.dp))
            Text("Import .renderer3 / Renderer3 ZIP")
        }
        val installed=remember(revision){Renderer3Bridge.list(this@ComposeStudioActivity)}
        if(installed.isEmpty()){
            Text("No imported renderers yet. Import your renderer package above.",
                style=MaterialTheme.typography.bodyMedium)
        }else{
            Heading("Renderer library")
            installed.forEach{selection->
                val isActive=rendererSelection?.filePath==selection.filePath
                Surface(onClick={
                    Renderer3Bridge.select(project,selection)
                    rendererSelection=selection;frame=0L;changed()
                },shape=RoundedCornerShape(16.dp),
                    color=if(isActive)MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier=Modifier.fillMaxWidth()){
                    Column(Modifier.padding(13.dp)){
                        Text((if(isActive)"✓ "else"")+selection.name,
                            fontWeight=FontWeight.SemiBold)
                        Text(Renderer3Bridge.selectionDetails(selection,project.Cards.size),
                            style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        val selected=rendererSelection
        if(selected!=null){
            Heading("Current renderer")
            Text(Renderer3Bridge.selectionDetails(selected,project.Cards.size))
            Text("Press Play above to preview the exact Renderer3 scene, including supported SmartCard, SmartBadge and animated ribbon assets.",
                style=MaterialTheme.typography.bodySmall)
            Buttons("Disable renderer" to{
                Renderer3Bridge.forget(project);rendererSelection=null;frame=0L;playing=false;changed()
            },"Delete imported file" to{
                guarded{
                    Renderer3Bridge.delete(this@ComposeStudioActivity,selected)
                    Renderer3Bridge.forget(project);rendererSelection=null;frame=0L;playing=false;changed()
                }
            })
        }
        Text("Unsupported Renderer3 engines, missing assets or invalid archive paths are rejected with errors rather than silently replaced with a generic template.",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }

    @Composable private fun AudioPage(){
        Heading("Audio patterns","Save the creator's exact track order into a reusable M3U playlist.")
        Buttons("＋ Add audio" to{openAudio.launch(arrayOf("audio/*"))},
            "Remove" to{
                if(selectedAudio in project.AudioTracks.indices){
                    project.AudioTracks.removeAt(selectedAudio)
                    selectedAudio=selectedAudio.coerceAtMost(project.AudioTracks.size-1);changed()
                }
            })
        project.AudioTracks.forEachIndexed{index,p->
            Surface(onClick={selectedAudio=index;changed()},shape=RoundedCornerShape(13.dp),
                color=if(index==selectedAudio)MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                modifier=Modifier.fillMaxWidth()){
                Text((index+1).toString()+". "+p.substringAfterLast('/').take(45),
                    modifier=Modifier.padding(10.dp),maxLines=1)
            }
        }
        Buttons("↑ Move up" to{moveAudio(-1)},"↓ Move down" to{moveAudio(1)})
        Buttons("▶ Preview" to{previewAudio()},"■ Stop" to{stopAudio()})
        Buttons("Relink selected audio" to{
            if(selectedAudio in project.AudioTracks.indices)relinkAudio.launch(arrayOf("audio/*"))
        })
        Measure("Volume %",(project.AudioVolume*100).toFloat(),0f..100f){
            project.AudioVolume=it/100.0
        }
        Row(verticalAlignment=Alignment.CenterVertically){
            Switch(checked=project.AudioRepeat,onCheckedChange={project.AudioRepeat=it;changed()})
            Spacer(Modifier.width(12.dp));Text("Repeat full pattern")
        }
        Row(verticalAlignment=Alignment.CenterVertically){
            Switch(checked=project.AudioFadeOut,onCheckedChange={project.AudioFadeOut=it;changed()})
            Spacer(Modifier.width(12.dp));Text("Fade at video end")
        }
        Buttons("Save Pattern (.m3u)" to{
            if(project.AudioTracks.isEmpty())message("Add tracks first")
            else saveM3u.launch("Audio-Pattern.m3u")
        },"Import Pattern (.m3u)" to{
            openM3u.launch(arrayOf("audio/x-mpegurl","*/*"))
        })
        Text("Imported Windows file paths may need relinking on Android.",
            style=MaterialTheme.typography.bodySmall)
    }
    private fun moveAudio(direction:Int){
        val next=selectedAudio+direction
        if(selectedAudio !in project.AudioTracks.indices||next !in project.AudioTracks.indices)return
        val a=project.AudioTracks.removeAt(selectedAudio)
        project.AudioTracks.add(next,a);selectedAudio=next;changed()
    }
    private fun stopAudio(){
        val current=audioPlayer
        audioPlayer=null
        if(current!=null)try{current.stop();current.release()}catch(_:Exception){}
    }
    private fun previewAudio(){
        if(selectedAudio !in project.AudioTracks.indices){message("Select an audio track");return}
        stopAudio()
        try{
            val player=android.media.MediaPlayer()
            audioPlayer=player
            player.setDataSource(this,Uri.parse(project.AudioTracks[selectedAudio]))
            player.setVolume(project.AudioVolume.toFloat(),project.AudioVolume.toFloat())
            player.setOnPreparedListener{it.start()}
            player.setOnCompletionListener{stopAudio()}
            player.prepareAsync()
        }catch(e:Exception){stopAudio();message("Cannot preview audio: "+e.message)}
    }
    private fun reviewAudioBeforeExport(){
        if(project.AudioTracks.isEmpty()){
            saveMp4.launch("CubicalCompare-V5.mp4");return
        }
        var total=0L
        var known=true
        project.AudioTracks.forEach{reference->
            val probe=android.media.MediaMetadataRetriever()
            try{
                probe.setDataSource(this,Uri.parse(reference))
                val duration=probe.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                if(duration==null)known=false else total+=duration.toLong()
            }catch(_:Exception){known=false}
            finally{try{probe.release()}catch(_:Exception){}}
        }
        val videoDuration=project.Cards.size.toLong()*project.SecondsPerCard*1000L
        audioWarning=if(!known)"Some soundtrack files could not be inspected. Missing tracks may prevent export."
        else if(total>videoDuration)"The pattern extends past the video end. Audio will be trimmed and faded."
        else if(total<videoDuration && !project.AudioRepeat)
            "The pattern ends before the video. The remainder will be silent; enable Repeat Pattern to fill it."
        else ""
        if(audioWarning.isEmpty())saveMp4.launch("CubicalCompare-V5.mp4")
    }
    @Composable private fun ExportPage(){
        Heading("Export","Rust computes the animation clock and easing. Android Media3 encodes MP4.")
        Measure("Frame rate",project.Fps.toFloat(),15f..60f){project.Fps=it.roundToInt()}
        Measure("Seconds per card",project.SecondsPerCard.toFloat(),1f..15f){
            project.SecondsPerCard=it.roundToInt()}
        Text(project.Cards.size.toString()+" cards • "+(project.Cards.size*project.SecondsPerCard)+
            " seconds • "+project.ExportWidth+" × "+project.ExportHeight)
        Buttons("1280 × 720" to{
            project.ExportWidth=1280;project.ExportHeight=720;changed()
        },"1920 × 1080" to{
            project.ExportWidth=1920;project.ExportHeight=1080;changed()
        })
        Button(onClick={if(!exporter.isBusy)reviewAudioBeforeExport()},
            modifier=Modifier.fillMaxWidth(),enabled=!exporting){
            Icon(Icons.Default.Movie,null);Spacer(Modifier.width(7.dp));Text("Export MP4 with audio")
        }
        OutlinedButton(onClick={savePng.launch("CubicalCompare-Thumbnail.png")},
            modifier=Modifier.fillMaxWidth()){Text("Export thumbnail PNG")}
        Text("Rust drives the card-conveyor easing for timeline playback and MP4 transitions; complete Renderer3-specific badge ZIP animations are not yet ported.",
            style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    @Composable private fun DrawPage(){
        Heading("Draw as artwork","Draw on the video card or thumbnail with separate layers.")
        Buttons("Draw on card" to{ink();drawingThumbnail=false;changed()},
            "Draw on thumbnail" to{ink();drawingThumbnail=true;changed()})
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
            for(mode in CardCanvas.Tool.values()){
                FilterChip(selected=tool==mode,onClick={tool=mode;changed()},
                    label={Text(mode.name.lowercase().replaceFirstChar{it.uppercase()})})
            }
        }
        OutlinedButton(onClick={canvas?.clearInk();changed()},modifier=Modifier.fillMaxWidth()){
            Text("Clear drawing")
        }
    }
    @Composable private fun SettingsPage(){
        Heading("Appearance")
        Buttons("Dark" to{themePref=1;savePrefs()},"Light" to{themePref=2;savePrefs()})
        OutlinedButton(onClick={themePref=0;savePrefs()},modifier=Modifier.fillMaxWidth()){
            Text("Follow system appearance")
        }
        Heading("Language")
        val options=listOf("English","Português","Español","Français","Deutsch","Русский",
            "日本語","中文","Italiano","Українська")
        options.forEachIndexed{index,item->
            Row(Modifier.fillMaxWidth().clickable{language=index;savePrefs()},
                verticalAlignment=Alignment.CenterVertically){
                RadioButton(selected=language==index,onClick={language=index;savePrefs()})
                Text(item)
            }
        }
        Heading("Compatibility")
        Text("Android Canvas runs without relying on advanced GPU preview features. Rust timeline maths is deterministic, with an Android native decoder/encoder.",
            style=MaterialTheme.typography.bodySmall)
    }
    @Composable private fun BadgeDialog(){
        val initial=try{Color.parseColor(card().BadgeColor)}catch(_:Exception){0xffec394d.toInt()}
        var r by remember{mutableFloatStateOf(Color.red(initial).toFloat())}
        var g by remember{mutableFloatStateOf(Color.green(initial).toFloat())}
        var b by remember{mutableFloatStateOf(Color.blue(initial).toFloat())}
        AlertDialog(onDismissRequest={colorDialog=false},
            title={Text(text("Badge colour"))},
            text={Column{
                Box(Modifier.fillMaxWidth().height(44.dp).background(
                    MColor(r.roundToInt(),g.roundToInt(),b.roundToInt()),RoundedCornerShape(13.dp)))
                for(i in 0..2){
                    Text(listOf("Red","Green","Blue")[i])
                    Slider(value=when(i){0->r;1->g;else->b},
                        onValueChange={when(i){0->r=it;1->g=it;else->b=it}},
                        valueRange=0f..255f)
                }
            }},
            confirmButton={TextButton(onClick={
                card().BadgeColor=String.format(Locale.ROOT,"#%02X%02X%02X",
                    r.roundToInt(),g.roundToInt(),b.roundToInt())
                colorDialog=false;changed()
            }){Text("Apply")}},
            dismissButton={TextButton(onClick={colorDialog=false}){Text("Cancel")}})
    }
}
