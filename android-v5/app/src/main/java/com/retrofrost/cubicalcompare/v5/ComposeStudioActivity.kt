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
    private val tabs = listOf("Project","Cards","Thumbnails","Renderers","MegaPacks","Audio","Export","Draw","Settings")
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
    private val history=ProjectHistory()
    private var pendingHistory:Runnable?=null
    private var cardSearch by mutableStateOf("")
    private var tappedElement by mutableStateOf("")
    private var colourAll by mutableStateOf(false)
    private var megapackStatus by mutableStateOf("No MegaPack loaded")
    private var megapackProgress by mutableFloatStateOf(0f)
    private val bundleExecutor=java.util.concurrent.Executors.newSingleThreadExecutor()
    private var rendererSelection by mutableStateOf<io.github.retrofrost.cts.android.renderer3.RendererV3Selection?>(null)
    private var audioPlayer: android.media.MediaPlayer? = null
    private var canvas: CardCanvas? = null
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
    private fun changed() {
        rev++;canvas?.refresh();scheduleAutosave()
        pendingHistory?.let{autosaveHandler.removeCallbacks(it)}
        pendingHistory=Runnable{pendingHistory=null;history.capture(StudioModel.json(project));rev++}
        autosaveHandler.postDelayed(pendingHistory!!,600L)
    }
    private fun restoreHistory(redo:Boolean){
        ink();pendingHistory?.let{autosaveHandler.removeCallbacks(it)};pendingHistory=null
        history.capture(StudioModel.json(project))
        val json=if(redo)history.redo()else history.undo()
        if(json!=null){
            // Persist the old canvas before swapping model ownership.
            canvas=null;project=StudioModel.parse(json)
            selected=selected.coerceIn(0,project.Cards.size-1)
            rendererSelection=runCatching{Renderer3Bridge.resolve(this,project)}.getOrNull()
            frame=0L;playing=false;changed()
        }
    }
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
    private val openZipack2=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null && !bundleBusy) {
            ink();bundleBusy=true;megapackProgress=0f
            status="Importing Zipack2 MegaPack, artwork and badges..."
            bundleExecutor.execute {
                try {
                    val imported=Zipack2Bridge.importPack(this,uri){percent->
                        runOnUiThread{megapackProgress=percent/100f}
                    }
                    runOnUiThread {
                        project=imported.project
                        rendererSelection=null
                        selected=0;selectedAudio=-1;frame=0L;playing=false
                        tab="MegaPacks";bundleBusy=false
                        megapackStatus="Imported "+imported.count+" cards from "+project.ProjectName
                        changed();message(megapackStatus)
                    }
                } catch(e:Exception) {
                    runOnUiThread {
                        bundleBusy=false;megapackStatus="Import failed: "+e.message
                        message(megapackStatus)
                    }
                }
            }
        }
    }
    private val saveZipack2=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->
        if(uri!=null && !bundleBusy){
            ink();bundleBusy=true;megapackProgress=0f
            status="Exporting real Zipack2 contact sheets and manifest..."
            val snapshot=StudioModel.parse(StudioModel.json(project))
            bundleExecutor.execute{
                try {
                    Zipack2Bridge.exportPack(this,snapshot,uri){percent->
                        runOnUiThread{megapackProgress=percent/100f}
                    }
                    runOnUiThread {
                        bundleBusy=false;megapackStatus="MegaPack exported: "+snapshot.Cards.size+" cards"
                        message(megapackStatus)
                    }
                }catch(e:Exception){
                    runOnUiThread {
                        bundleBusy=false;megapackStatus="MegaPack export failed: "+e.message
                        message(megapackStatus)
                    }
                }
            }
        }
    }
    private val openCsv=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null && !bundleBusy){
            ink();bundleBusy=true;status="Importing cards and Flaticon artwork..."
            bundleExecutor.execute {
                try {
                    val parsed=StudioModel.parseCsv(read(uri).toString(Charsets.UTF_8))
                    var downloaded=0
                    val failures=ArrayList<String>()
                    for((index,entry) in parsed.withIndex()){
                        val url=entry.ArtworkSource.orEmpty()
                        if(url.isNotBlank()){
                            try{
                                entry.ArtworkBase64=Zipack2Bridge.downloadFlaticonArtwork(url)
                                downloaded++
                            }catch(e:Exception){
                                failures.add("Card "+(index+1)+": "+(e.message?:"artwork unavailable"))
                            }
                        }
                    }
                    runOnUiThread{
                        project.Cards=parsed
                        selected=0;bundleBusy=false;changed()
                        status="Imported "+parsed.size+" cards, "+downloaded+" artwork images"+
                            if(failures.isEmpty())""else" • "+failures.size+" image(s) need relinking"
                        message(status)
                        if(failures.isNotEmpty())android.util.Log.w("CTS_CSV",failures.joinToString("\n"))
                    }
                }catch(e:Exception){
                    runOnUiThread{bundleBusy=false;message("CSV import failed: "+e.message)}
                }
            }
        }
    }
    private fun loadFlaticonArtwork(thumb:Boolean){
        val selectedCard=card()
        val url=if(thumb)selectedCard.ThumbnailArtworkSource.orEmpty()
            else selectedCard.ArtworkSource.orEmpty()
        if(url.isBlank()){message("Paste a Flaticon image or icon-page URL first");return}
        if(bundleBusy){message("A file operation is still running");return}
        ink();bundleBusy=true
        status="Loading Flaticon artwork..."
        bundleExecutor.execute{
            try{
                val png=Zipack2Bridge.downloadFlaticonArtwork(url)
                runOnUiThread{
                    if(thumb)selectedCard.ThumbnailArtworkBase64=png
                    else selectedCard.ArtworkBase64=png
                    bundleBusy=false;changed();message("Artwork loaded from Flaticon")
                }
            }catch(e:Exception){
                runOnUiThread{bundleBusy=false;message("Artwork load failed: "+e.message)}
            }
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
    private val saveCsv=registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->
        if(uri!=null)guarded{ink();save(uri,StudioModel.csv(project).toByteArray(Charsets.UTF_8));message("CSV exported")}
    }
    private val saveFrame=registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")){uri->
        if(uri!=null&&!bundleBusy){
            ink();val snapshot=StudioModel.parse(StudioModel.json(project))
            val clock=frame.toInt();val selection=rendererSelection
            bundleBusy=true
            bundleExecutor.execute{
                try{
                    val bitmap=if(selection!=null)Renderer3Bridge.Session(this,selection,snapshot).use{
                        it.bitmap(clock,snapshot.ExportWidth,snapshot.ExportHeight)
                    }else BasicFramePainter(this,snapshot,snapshot.ExportWidth,snapshot.ExportHeight).use{
                        it.bitmap(clock)
                    }
                    try{val out=ByteArrayOutputStream();check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out))
                        save(uri,out.toByteArray())}finally{bitmap.recycle()}
                    runOnUiThread{bundleBusy=false;message("Current video frame exported")}
                }catch(e:Exception){runOnUiThread{bundleBusy=false;message("Frame export failed: "+e.message)}}
            }
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
    private var pendingExportUri:Uri?=null
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){
        pendingExportUri?.let{uri->pendingExportUri=null;guarded{ExportService.start(this,project,uri)}}
    }
    private val saveMp4=registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")){uri->
        if(uri!=null){
            ink();exporting=true;exportProgress=0f
            if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
                pendingExportUri=uri;notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }else guarded{ExportService.start(this,project,uri)}
        }
    }
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
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
                // A true Windows-style contact-sheet ZIP fixture: two coloured
                // 363-pixel regions and distinct badge headers/values.
                val contact=Bitmap.createBitmap(750,379,Bitmap.Config.ARGB_8888)
                val drawing=android.graphics.Canvas(contact)
                drawing.drawColor(Color.YELLOW)
                val paint=android.graphics.Paint().apply{color=Color.CYAN}
                drawing.drawRect(8f,8f,371f,371f,paint)
                paint.color=Color.MAGENTA
                drawing.drawRect(379f,8f,742f,371f,paint)
                val imageOut=ByteArrayOutputStream()
                check(contact.compress(Bitmap.CompressFormat.PNG,100,imageOut))
                contact.recycle()
                val sheetName="artwork/contact-sheet-001.png"
                val meta=JSONObject()
                    .put("format","cubical.megapack.zipack2").put("version",2)
                    .put("name","Zipack2 Smoke")
                    .put("cards",org.json.JSONArray()
                        .put(JSONObject().put("title","First")
                            .put("badge_header","After").put("badge_value","1")
                            .put("badge_unit","minute").put("description","First card"))
                        .put(JSONObject().put("title","Second")
                            .put("badge_header","After").put("badge_value","2")
                            .put("badge_unit","minutes").put("description","Second card")))
                    .put("contactSheets",org.json.JSONArray().put(
                        JSONObject().put("path",sheetName).put("order",0)
                            .put("regions",org.json.JSONArray()
                                .put(JSONObject().put("x",8).put("y",8)
                                    .put("width",363).put("height",363).put("order",0))
                                .put(JSONObject().put("x",379).put("y",8)
                                    .put("width",363).put("height",363).put("order",1)))))
                val packFixture=File(cacheDir,"zipack2-input.zip")
                java.util.zip.ZipOutputStream(packFixture.outputStream()).use { zipped ->
                    zipped.putNextEntry(java.util.zip.ZipEntry(sheetName))
                    zipped.write(imageOut.toByteArray());zipped.closeEntry()
                    zipped.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                    zipped.write(meta.toString().toByteArray(Charsets.UTF_8));zipped.closeEntry()
                }
                val importedZip=Zipack2Bridge.importPack(this,Uri.fromFile(packFixture))
                check(importedZip.count==2)
                check(importedZip.project.Cards[0].BadgeHeader=="After")
                check(importedZip.project.Cards[0].Badge=="1")
                check(importedZip.project.Cards[1].BadgeUnit=="minutes")
                check(importedZip.project.Cards.all{it.ArtworkBase64.isNotBlank()})
                val reexport=File(cacheDir,"zipack2-reexport.zip")
                Zipack2Bridge.exportPack(this,importedZip.project,Uri.fromFile(reexport))
                val reimported=Zipack2Bridge.importPack(this,Uri.fromFile(reexport))
                check(reimported.project.Cards.map{it.Title}==listOf("First","Second"))
                check(reimported.project.Cards[1].ArtworkBase64.isNotBlank())
                android.util.Log.i("CTS_ZIPACK2_SELFTEST","PASS: contact sheet import and 2-card MegaPack re-export")
                // Regression: Windows often stores 40–50 illustrations on ONE
                // contact sheet wider than the mobile preview texture limit.
                // Validate native rectangle extraction after full-size decode.
                val huge=Bitmap.createBitmap(5200,900,Bitmap.Config.ARGB_8888)
                val bigCanvas=android.graphics.Canvas(huge)
                bigCanvas.drawColor(Color.YELLOW)
                val swatch=android.graphics.Paint().apply{color=Color.CYAN}
                bigCanvas.drawRect(8f,8f,2310f,880f,swatch)
                swatch.color=Color.MAGENTA
                bigCanvas.drawRect(2600f,8f,4902f,880f,swatch)
                val bigOut=ByteArrayOutputStream()
                check(huge.compress(Bitmap.CompressFormat.PNG,100,bigOut))
                huge.recycle()
                val largeManifest=JSONObject(meta.toString())
                    .put("contactSheets",org.json.JSONArray().put(
                        JSONObject().put("path",sheetName).put("order",0)
                            .put("regions",org.json.JSONArray()
                                .put(JSONObject().put("x",8).put("y",8)
                                    .put("width",2302).put("height",872).put("order",0))
                                .put(JSONObject().put("x",2600).put("y",8)
                                    .put("width",2302).put("height",872).put("order",1)))))
                val bigFile=File(cacheDir,"zipack2-giant-sheet.zip")
                java.util.zip.ZipOutputStream(bigFile.outputStream()).use{zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry(sheetName))
                    zip.write(bigOut.toByteArray());zip.closeEntry()
                    zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                    zip.write(largeManifest.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                val giantImport=Zipack2Bridge.importPack(this,Uri.fromFile(bigFile))
                check(giantImport.count==2 && giantImport.project.Cards.all{
                    it.ArtworkBase64.isNotBlank()
                }){"Large Windows contact-sheet region decode failed"}
                android.util.Log.i("CTS_ZIPACK2_LARGE","PASS: original-resolution regions from 5200px Windows contact sheet")


            }catch(e:Exception){
                android.util.Log.e("CTS_RENDERER3_SELFTEST","FAIL",e)
                throw e
            }
        }
        history.capture(StudioModel.json(project))
        setContent{Studio()}
        if(BuildConfig.DEBUG && intent.getBooleanExtra("ctsParitySelfTest",false))bundleExecutor.execute{
            try{
                ParityRuntimeChecks.run(this)
                val fixture=StudioModel.blank().apply{
                    ExportWidth=320;ExportHeight=180;Fps=16;SecondsPerCard=4;VideoBitrateMbps=1
                    Cards.add(StudioModel.Card().apply{Title="Service export"})
                }
                runOnUiThread{ExportService.start(this,fixture,Uri.fromFile(File(filesDir,"service-smoke.mp4")))}
            }catch(e:Throwable){
                android.util.Log.e("CTS_PARITY_SELFTEST","FAIL",e)
            }
        }
    }
    override fun onPause(){
        super.onPause();stopAudio()
        pendingAutosave?.let{autosaveHandler.removeCallbacks(it)}
        pendingAutosave=null
        writeAutosave()
    }
    override fun onDestroy(){pendingHistory?.let{autosaveHandler.removeCallbacks(it)};pendingAutosave?.let{autosaveHandler.removeCallbacks(it)};stopAudio();bundleExecutor.shutdown();super.onDestroy()}
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
        val exportState by ExportService.state.collectAsState()
        LaunchedEffect(exportState){
            exporting=exportState.busy;exportProgress=exportState.percent/100f
            if(exportState.message!="Ready")status=exportState.message
        }
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
                                    4->Icons.Default.FolderZip;5->Icons.Default.MusicNote
                                    6->Icons.Default.FileUpload;7->Icons.Default.Brush
                                    else->Icons.Default.Settings
                                },contentDescription=null,modifier=Modifier.size(18.dp))},
                                modifier=Modifier.padding(horizontal=3.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
                    TextButton(onClick={restoreHistory(false)},enabled=history.canUndo()||pendingHistory!=null){
                        Icon(Icons.Default.Undo,null);Text("Undo")}
                    TextButton(onClick={restoreHistory(true)},enabled=history.canRedo()){
                        Icon(Icons.Default.Redo,null);Text("Redo")}
                    Spacer(Modifier.weight(1f))
                    Text("${selected+1} / ${project.Cards.size}",style=MaterialTheme.typography.labelSmall)
                }
                if(exporting)Row(Modifier.fillMaxWidth().padding(horizontal=14.dp),
                    verticalAlignment=Alignment.CenterVertically){
                    LinearProgressIndicator(progress={exportProgress},
                        modifier=Modifier.weight(1f).height(6.dp))
                    TextButton(onClick={
                        ExportService.cancel(this@ComposeStudioActivity)
                        exporting=false
                        exportProgress=0f
                        status="Export cancelled."
                    }){Text("Cancel export")}
                }
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
            if(tappedElement.isNotEmpty()){
                val c=card()
                AlertDialog(onDismissRequest={tappedElement=""},title={Text("Edit "+tappedElement)},
                    text={Column{
                        when(tappedElement){
                            "title"->Edit("Title",c.Title){c.Title=it}
                            "description"->MultilineEdit("Description",c.Description){c.Description=it}
                            else->{Edit("Badge header",c.BadgeHeader){c.BadgeHeader=it}
                                Edit("Badge value",c.Badge){c.Badge=it}
                                Edit("Badge unit",c.BadgeUnit){c.BadgeUnit=it}}
                        }
                    }},confirmButton={TextButton(onClick={tappedElement=""}){Text("Done")}})
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
                    Text("${project.ExportWidth} × ${project.ExportHeight}",style=MaterialTheme.typography.labelSmall)
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
                } else if(tab=="Export") {
                    AndroidView(factory={ctx->BasicFrameView(ctx)},
                        update={view->view.configure(project,rev,frame.toInt())},
                        modifier=Modifier.fillMaxWidth().aspectRatio(16f/9f))
                } else {
                    AndroidView(factory={context->
                        CardCanvas(context).also{
                            it.setCard(selectedCard);it.setThumbnail(thumb)
                            it.tag=selectedCard;it.onChanged(Runnable{changed()})
                            it.onElementTapped{element->ink();tappedElement=element};canvas=it
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
                if(tab=="Export" || tab=="Renderers" || (tab=="Cards"&&renderPreviewOnCards))Timeline()
            }
        }
    }
    @Composable private fun Timeline(){
        val selectedRenderer=rendererSelection
        val revision=rev
        val json=remember(revision,project.Cards.size,project.Fps,project.SecondsPerCard){
            StudioModel.timelineJson(project)
        }
        val total=if(selectedRenderer!=null)
            selectedRenderer.frameCountForCards(project.Cards.size).toLong().coerceAtLeast(1)
            else NativeEngine.totalFrames(json).coerceAtLeast(1L)
        val fps=selectedRenderer?.fps ?: project.Fps
        LaunchedEffect(playing,json,selectedRenderer?.id){
            val start=android.os.SystemClock.elapsedRealtimeNanos()
            val first=frame
            while(playing){
                delay(8L)
                frame=(first+(android.os.SystemClock.elapsedRealtimeNanos()-start)*fps/1_000_000_000L)%total
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
        Row{
            TextButton(onClick={playing=false;frame=(frame-1).coerceAtLeast(0)}){Text("−1 frame")}
            TextButton(onClick={playing=false;frame=(frame+1).coerceAtMost(total-1)}){Text("+1 frame")}
            TextButton(onClick={playing=false;frame=0L}){Text("Start")}
            TextButton(onClick={playing=false;frame=total-1}){Text("End")}
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
                    "MegaPacks"->MegaPacksPage()
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
    @Composable private fun NumberEdit(label:String,value:Double,range:ClosedFloatingPointRange<Double>,change:(Double)->Unit){
        var draft by remember(label){mutableStateOf(value.toString())}
        LaunchedEffect(value,rev){draft=if(value%1.0==0.0)value.toLong().toString()else value.toString()}
        val parsed=draft.toDoubleOrNull()
        val valid=parsed!=null && parsed.isFinite() && parsed in range
        OutlinedTextField(value=draft,onValueChange={draft=it},label={Text(label)},
            singleLine=true,isError=!valid,modifier=Modifier.fillMaxWidth(),
            keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal),
            trailingIcon={IconButton(onClick={if(valid){change(parsed!!);changed()}},enabled=valid){Icon(Icons.Default.Check,"Apply")}})
    }
    @Composable private fun ProjectPage(){
        Heading("Project","Windows V5 compatible .ccv5 card data, artwork and settings.")
        Edit("Project name",project.ProjectName){project.ProjectName=it}
        Buttons("New project" to{newProjectConfirmation=true},
            "Open .ccv5" to{openProject.launch(arrayOf("application/json","*/*"))})
        Buttons("Save .ccv5" to{saveProject.launch(project.ProjectName+".ccv5")},
            "Import CSV" to{openCsv.launch(arrayOf("text/*","*/*"))})
        Buttons("Export CSV" to{saveCsv.launch(project.ProjectName+".csv")})
        Buttons("Import MegaPack (.zipack2)" to{
            if(!bundleBusy)openZipack2.launch(arrayOf("application/zip","application/octet-stream","*/*"))
        },"Export MegaPack (.zipack2)" to{
            if(!bundleBusy)saveZipack2.launch(project.ProjectName.ifBlank{"Comparison"}+".zipack2")
        })
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
        Heading("Cards","Touch a card to edit, or tap its title, badge or description in the artwork preview.")
        OutlinedTextField(value=cardSearch,onValueChange={cardSearch=it},label={Text("Find cards")},
            modifier=Modifier.fillMaxWidth(),singleLine=true)
        Buttons("Sort value ↑" to{sortCards(false)},"Sort value ↓" to{sortCards(true)})
        Buttons("＋ Add" to{
            ink();val c=StudioModel.Card();c.Title="Card "+(project.Cards.size+1)
            if(project.Cards.size<500){project.Cards.add(c);selected=project.Cards.size-1;changed()}else message("Maximum 500 cards")
        },"Duplicate" to{
            ink();val copy=StudioModel.JSON.fromJson(StudioModel.JSON.toJson(card()),StudioModel.Card::class.java)
            if(project.Cards.size<500){project.Cards.add(selected+1,copy);selected++;changed()}else message("Maximum 500 cards")
        })
        // Bound this nested scrolling region: do not compose all 40-50+ cards
        // eagerly or give a LazyColumn an infinite height inside verticalScroll.
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=238.dp),
            verticalArrangement=Arrangement.spacedBy(4.dp)){
            itemsIndexed(project.Cards.withIndex().filter{cardSearch.isBlank() ||
                (it.value.Title+" "+it.value.Description+" "+it.value.Badge).contains(cardSearch,true)}){_,entry->
                val index=entry.index;val c=entry.value
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
        Edit("Flaticon image / icon page URL",c.ArtworkSource.orEmpty()){c.ArtworkSource=it}
        OutlinedButton(onClick={loadFlaticonArtwork(false)},modifier=Modifier.fillMaxWidth()){
            Text("Load Flaticon artwork")
        }
        Transforms(false)
    }
    private fun sortCards(descending:Boolean){
        ink();val active=card()
        fun number(c:StudioModel.Card):Double?=Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")
            .find(c.Badge.replace(",",""))?.value?.toDoubleOrNull()
        project.Cards.sortWith(Comparator{a,b->
            val x=number(a);val y=number(b)
            when{ x==null&&y==null->0;x==null->1;y==null->-1
                descending->y.compareTo(x);else->x.compareTo(y)}
        })
        selected=project.Cards.indexOf(active);changed()
    }
    private fun shiftCard(offset:Int){
        val next=selected+offset;if(next !in project.Cards.indices)return
        ink();val c=project.Cards.removeAt(selected);project.Cards.add(next,c)
        selected=next;changed()
    }
    @Composable private fun Transforms(thumbnail:Boolean){
        Heading(if(thumbnail)"Thumbnail transform"else"Image transform")
        val t=card().transform(thumbnail)
        NumberEdit("Scale %",t.Scale*100,10.0..800.0){t.Scale=it/100.0}
        NumberEdit("Position X",t.X,-1000.0..1000.0){t.X=it}
        NumberEdit("Position Y",t.Y,-1000.0..1000.0){t.Y=it}
        NumberEdit("Rotation",t.Rotation,-360.0..360.0){t.Rotation=it}
        Row(verticalAlignment=Alignment.CenterVertically){
            Checkbox(checked=t.FlipHorizontal,onCheckedChange={t.FlipHorizontal=it;changed()})
            Text(text("Flip horizontally"))
        }
        Row(verticalAlignment=Alignment.CenterVertically){
            Checkbox(checked=t.FlipVertical,onCheckedChange={t.FlipVertical=it;changed()})
            Text(text("Flip vertically"))
        }
        OutlinedButton(onClick={StudioModel.applyTransformToAll(project,card(),thumbnail);changed()},
            modifier=Modifier.fillMaxWidth()){Text("Apply transform to all images")}
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
        Edit("Thumbnail Flaticon URL",card().ThumbnailArtworkSource.orEmpty()){
            card().ThumbnailArtworkSource=it
        }
        OutlinedButton(onClick={loadFlaticonArtwork(true)},modifier=Modifier.fillMaxWidth()){
            Text("Load thumbnail from Flaticon")
        }
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

    @Composable private fun MegaPacksPage(){
        Heading("MegaPacks","Windows-compatible Zipack2 v2. Import illustrated contact sheets, card data, badges, ordering and soundtracks.")
        Buttons("Import .zipack2" to{
            if(!bundleBusy)openZipack2.launch(arrayOf("application/zip","application/octet-stream","*/*"))
        },"Export .zipack2" to{
            if(!bundleBusy)saveZipack2.launch(project.ProjectName.ifBlank{"Comparison"}+".zipack2")
        })
        if(bundleBusy){
            LinearProgressIndicator(progress={megapackProgress},modifier=Modifier.fillMaxWidth())
            Text("Processing MegaPack artwork…",style=MaterialTheme.typography.bodySmall)
        }
        Text(megapackStatus,style=MaterialTheme.typography.bodyMedium,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        Heading("Contents")
        Text("Project: "+project.ProjectName)
        Text("Cards: "+project.Cards.size+" • Audio tracks: "+project.AudioTracks.size)
        Text("Images: "+project.Cards.count{!it.ArtworkBase64.isNullOrBlank()}+
            " embedded images",style=MaterialTheme.typography.bodySmall)
        Buttons("Review cards" to{tab="Cards";changed()},
            "Preview video" to{tab="Export";changed()})
        Text("Imported cards preserve chronological order and separate badge header, value and unit. Exported contact sheets use 363 px coloured tiles with real image regions; no transparent placeholders.",
            style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Flaticon HTTPS image and preview-page URLs are supported. Unsupported web image hosts are rejected rather than silently replaced.",
            style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
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
        val videoDuration=Renderer3ExportInterop.durationMillis(this,project)
        audioWarning=if(!known)"Some soundtrack files could not be inspected. Missing tracks may prevent export."
        else if(total>videoDuration)"The pattern extends past the video end. Audio will be trimmed and faded."
        else if(total<videoDuration && !project.AudioRepeat)
            "The pattern ends before the video. The remainder will be silent; enable Repeat Pattern to fill it."
        else ""
        if(audioWarning.isEmpty())saveMp4.launch("CubicalCompare-V5.mp4")
    }
    @Composable private fun ExportPage(){
        Heading("Export","Rust computes the animation clock and easing. Android Media3 encodes MP4.")
        if(rendererSelection!=null){
            Text("Renderer3 controls frame rate and duration: "+
                Renderer3Bridge.selectionDetails(rendererSelection!!,project.Cards.size))
        }else{
            Measure("Frame rate",project.Fps.toFloat(),1f..60f){project.Fps=it.roundToInt()}
            NumberEdit("Seconds per card",project.SecondsPerCard.toDouble(),1.0..3600.0){project.SecondsPerCard=it.roundToInt()}
            NumberEdit("Total video seconds (0 = automatic)",project.VideoDurationSeconds,0.0..86400.0){
                if(it==0.0 || it*project.Fps>=project.Cards.size)project.VideoDurationSeconds=it
                else message("Choose at least one frame per card")
            }
            Text("${NativeEngine.totalFrames(StudioModel.timelineJson(project))} integer frames")
        }
        Buttons("1280 × 720" to{project.ExportWidth=1280;project.ExportHeight=720;changed()},
            "1920 × 1080" to{project.ExportWidth=1920;project.ExportHeight=1080;changed()})
        Buttons("2560 × 1440" to{project.ExportWidth=2560;project.ExportHeight=1440;changed()},
            "3840 × 2160" to{project.ExportWidth=3840;project.ExportHeight=2160;changed()})
        NumberEdit("Width (even pixels)",project.ExportWidth.toDouble(),320.0..3840.0){project.ExportWidth=it.toInt().let{n->n-n%2}}
        NumberEdit("Height (even pixels)",project.ExportHeight.toDouble(),180.0..2160.0){project.ExportHeight=it.toInt().let{n->n-n%2}}
        Text("Encoder")
        Row{listOf("auto","hardware","software").forEach{mode->
            FilterChip(selected=project.VideoEncoder==mode,onClick={project.VideoEncoder=mode;changed()},
                label={Text(mode.replaceFirstChar{it.uppercase()})},modifier=Modifier.padding(end=4.dp))}}
        NumberEdit("Video bitrate (Mbps)",project.VideoBitrateMbps.toDouble(),1.0..80.0){project.VideoBitrateMbps=it.roundToInt()}
        Button(onClick={if(!ExportService.state.value.busy)reviewAudioBeforeExport()},
            modifier=Modifier.fillMaxWidth(),enabled=!exporting){
            Icon(Icons.Default.Movie,null);Spacer(Modifier.width(7.dp));Text("Export MP4 with audio")
        }
        if(exporting)OutlinedButton(onClick={
            ExportService.cancel(this@ComposeStudioActivity);exporting=false;exportProgress=0f;status="Export cancelled."
        },modifier=Modifier.fillMaxWidth()){Text("Cancel export")}
        OutlinedButton(onClick={savePng.launch("CubicalCompare-Thumbnail.png")},
            modifier=Modifier.fillMaxWidth()){Text("Export thumbnail PNG")}
        OutlinedButton(onClick={saveFrame.launch("CubicalCompare-Frame-${frame}.png")},
            modifier=Modifier.fillMaxWidth(),enabled=!bundleBusy){Text("Export current video frame PNG")}
        Text("Every integer video frame is encoded. Renderer3 uses its authored SmartCard/SmartBadge timeline. Device codec limits are checked before rendering.",
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
                Row(verticalAlignment=Alignment.CenterVertically){
                    Checkbox(checked=colourAll,onCheckedChange={colourAll=it});Text("Apply to all cards")}
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
                if(colourAll){val colour=card().BadgeColor;project.Cards.forEach{it.BadgeColor=colour}}
                colorDialog=false;changed()
            }){Text("Apply")}},
            dismissButton={TextButton(onClick={colorDialog=false}){Text("Cancel")}})
    }
}
