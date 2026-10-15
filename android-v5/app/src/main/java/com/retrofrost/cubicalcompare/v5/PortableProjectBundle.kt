package com.retrofrost.cubicalcompare.v5

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable V5 ZIP: project JSON + validated Renderer3 package + every ordered soundtrack. */
internal object PortableProjectBundle {
    private const val LIMIT = 512L * 1024 * 1024
    private const val AUDIO_LIMIT = 96L * 1024 * 1024
    private const val RENDERER_LIMIT = 128L * 1024 * 1024
    private const val JSON_LIMIT = 64L * 1024 * 1024
    private const val PREFIX = "pack:"
    private val audioName = Regex("""audio/[0-9]{4}\.[a-z0-9]{1,8}""")

    internal fun allowedEntry(name: String): Boolean =
        name == "project.ccv5" || name == "renderer/selected.renderer3" ||
            audioName.matches(name)

    private class Quota {
        var used: Long = 0
        fun add(amount: Long) {
            used += amount
            require(used <= LIMIT) { "Portable bundle exceeds 512 MB expanded" }
        }
    }
    private fun copyLimited(input: InputStream, output: OutputStream, max: Long,
                            quota: Quota, label: String) {
        var single = 0L
        val buffer = ByteArray(65536)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            single += n
            require(single <= max) { label + " exceeds its per-file limit" }
            quota.add(n.toLong())
            output.write(buffer, 0, n)
        }
    }
    private fun suffix(context:Context, uri:Uri):String {
        val extension = uri.lastPathSegment.orEmpty().substringAfterLast('.',"")
            .lowercase().take(8)
        if(extension.matches(Regex("[a-z0-9]{1,8}")))return "."+extension
        return when(context.contentResolver.getType(uri)?.lowercase()){
            "audio/mpeg","audio/mp3"->".mp3"
            "audio/mp4","audio/x-m4a"->".m4a"
            "audio/wav","audio/x-wav"->".wav"
            "audio/flac"->".flac"
            "audio/ogg"->".ogg"
            else->".bin"
        }
    }
    fun write(context:Context,original:StudioModel.Project,target:Uri) {
        val project=StudioModel.parse(StudioModel.json(original))
        val quota=Quota()
        val selection=if(project.Renderer3AssetName.isNotBlank())
            Renderer3Bridge.resolve(context,project)
                ?:error("Renderer3 missing. Reimport it before sharing the project.")
            else null
        val output=context.contentResolver.openOutputStream(target,"wt")
            ?:error("Cannot create portable project")
        ZipOutputStream(output.buffered()).use{zip->
            project.AudioTracks.forEachIndexed{index,source->
                require(index<128){"Maximum 128 audio files in a portable project"}
                val uri=Uri.parse(source)
                val name="audio/"+index.toString().padStart(4,'0')+suffix(context,uri)
                require(allowedEntry(name)){"Audio filename is invalid"}
                zip.putNextEntry(ZipEntry(name))
                val input=context.contentResolver.openInputStream(uri)
                    ?:error("Cannot read audio "+(index+1)+". Relink it first.")
                input.use{copyLimited(it,zip,AUDIO_LIMIT,quota,name)}
                zip.closeEntry()
                project.AudioTracks[index]=PREFIX+name
            }
            if(selection!=null){
                zip.putNextEntry(ZipEntry("renderer/selected.renderer3"))
                val file=io.github.retrofrost.cts.android.renderer3.RendererV3Container
                    .checkedProjectFile(context,selection)
                file.inputStream().use{
                    copyLimited(it,zip,RENDERER_LIMIT,quota,"Renderer3")
                }
                zip.closeEntry()
                project.Renderer3AssetName=PREFIX+"renderer/selected.renderer3"
            }
            val json=StudioModel.json(project).toByteArray(Charsets.UTF_8)
            require(json.size<=JSON_LIMIT){"Project JSON exceeds 64 MB"}
            quota.add(json.size.toLong())
            zip.putNextEntry(ZipEntry("project.ccv5"))
            zip.write(json)
            zip.closeEntry()
            zip.finish()
        }
    }

    data class Imported(val project:StudioModel.Project,
        val renderer:io.github.retrofrost.cts.android.renderer3.RendererV3Selection?)

    fun read(context:Context,source:Uri):Imported {
        val dir=File(File(context.filesDir,"portable"),UUID.randomUUID().toString())
        check(dir.mkdirs()){"Cannot allocate local project storage"}
        val quota=Quota()
        val files=HashMap<String,File>()
        val seen=HashSet<String>()
        var json:ByteArray?=null
        try{
            val input=context.contentResolver.openInputStream(source)
                ?:error("Cannot open project archive")
            ZipInputStream(input.buffered()).use{zip->
                var members=0
                while(true){
                    val entry=zip.nextEntry?:break
                    require(++members<=132){"Too many files in project archive"}
                    require(!entry.isDirectory && allowedEntry(entry.name)){
                        "Unsupported or unsafe bundle entry: "+entry.name
                    }
                    require(seen.add(entry.name.lowercase())){"Duplicate archive path"}
                    val quotaForFile=when(entry.name){
                        "project.ccv5"->JSON_LIMIT
                        "renderer/selected.renderer3"->RENDERER_LIMIT
                        else->AUDIO_LIMIT
                    }
                    if(entry.name=="project.ccv5"){
                        val bytes=ByteArrayOutputStream()
                        copyLimited(zip,bytes,quotaForFile,quota,entry.name)
                        json=bytes.toByteArray()
                    }else{
                        // Never let ZIP filenames become path components.
                        val file=File(dir,entry.name.replace('/','-'))
                        file.outputStream().buffered().use{
                            copyLimited(zip,it,quotaForFile,quota,entry.name)
                        }
                        files[entry.name]=file
                    }
                    zip.closeEntry()
                }
            }
            val raw=json?:error("The archive has no project.ccv5")
            val project=StudioModel.parse(raw.toString(Charsets.UTF_8))
            val renderer=when(project.Renderer3AssetName){
                ""->null
                PREFIX+"renderer/selected.renderer3"->{
                    val file=files["renderer/selected.renderer3"]
                        ?:error("Renderer3 was not included in the archive")
                    val installed=Renderer3Bridge.install(context,Uri.fromFile(file))
                    require(project.Renderer3Id.isBlank()||project.Renderer3Id==installed.id){
                        "Renderer3 ID does not match the project"
                    }
                    Renderer3Bridge.select(project,installed)
                    installed
                }
                else->error("Unsupported Renderer3 reference in archive")
            }
            project.AudioTracks.forEachIndexed{index,reference->
                require(reference.startsWith(PREFIX+"audio/")){
                    "Audio track "+(index+1)+" is not bundled"
                }
                val file=files[reference.removePrefix(PREFIX)]
                    ?:error("Missing audio track "+(index+1))
                project.AudioTracks[index]=Uri.fromFile(file).toString()
            }
            return Imported(project,renderer)
        }catch(error:Exception){
            dir.deleteRecursively()
            throw error
        }
    }
}
