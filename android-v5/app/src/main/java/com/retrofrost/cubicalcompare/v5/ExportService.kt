package com.retrofrost.cubicalcompare.v5

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** User-started, visible export continues when the editor is backgrounded or recreated. */
class ExportService:Service(){
    data class Status(val busy:Boolean=false,val percent:Int=0,val message:String="Ready")
    companion object {
        private const val CHANNEL="cts-video-export"
        private const val NOTICE=51
        private val mutableStatus=MutableStateFlow(Status())
        val state=mutableStatus.asStateFlow()
        @Synchronized fun start(context:Context,project:StudioModel.Project,destination:Uri){
            check(!state.value.busy){"An export is already running"}
            val snapshot=File(context.cacheDir,"export-request-${java.util.UUID.randomUUID()}.ccv5")
            snapshot.writeText(StudioModel.json(project))
            mutableStatus.value=Status(true,0,"Starting video export…")
            try{
                context.startForegroundService(Intent(context,ExportService::class.java)
                    .putExtra("snapshot",snapshot.name).putExtra("destination",destination.toString()))
            }catch(e:Exception){snapshot.delete();mutableStatus.value=Status(false,0,"Export could not start: ${e.message}");throw e}
        }
        fun cancel(context:Context){
            if(state.value.busy)context.startService(Intent(context,ExportService::class.java).setAction("cancel"))
        }
    }
    private lateinit var exporter:VideoExporter
    private var wake:PowerManager.WakeLock?=null
    override fun onCreate(){
        super.onCreate();exporter=VideoExporter(applicationContext)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"Video export",NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(message:String,percent:Int,busy:Boolean):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,ComposeStudioActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder=Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Cubical Compare").setContentText(message).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(busy)
        if(busy){
            val cancel=PendingIntent.getService(this,1,Intent(this,ExportService::class.java).setAction("cancel"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.setProgress(100,percent,percent==0).addAction(android.R.drawable.ic_menu_close_clear_cancel,"Cancel",cancel)
        }else builder.setAutoCancel(true)
        return builder.build()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="cancel"){
            exporter.cancel();finish("Export cancelled",0);return START_NOT_STICKY
        }
        if(exporter.isBusy)return START_NOT_STICKY
        val initial=notification("Preparing video export…",0,true)
        try{
            if(Build.VERSION.SDK_INT>=29)startForeground(NOTICE,initial,
                if(Build.VERSION.SDK_INT>=35)ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTICE,initial)
            val name=intent?.getStringExtra("snapshot").orEmpty()
            require(name.matches(Regex("export-request-[a-f0-9-]+\\.ccv5"))){"Invalid export request"}
            val snapshot=File(cacheDir,name)
            val project=try{StudioModel.parse(snapshot.readText())}finally{snapshot.delete()}
            val destination=Uri.parse(requireNotNull(intent?.getStringExtra("destination")))
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"cts:video-export")
                .also{it.acquire(6*60*60*1000L)}
            exporter.export(project,destination,object:VideoExporter.Listener{
                override fun update(message:String,percent:Int){
                    mutableStatus.value=Status(true,percent,message)
                    getSystemService(NotificationManager::class.java).notify(NOTICE,notification(message,percent,true))
                }
                override fun finished(message:String){
                    android.util.Log.i("CTS_EXPORT_SERVICE","PASS: export finished from service")
                    finish(message,100)
                }
                override fun error(error:String){android.util.Log.e("CTS_EXPORT_SERVICE",error);finish("Export failed: $error",0)}
            })
        }catch(e:Exception){finish("Export failed: ${e.message}",0)}
        return START_NOT_STICKY
    }
    private fun finish(message:String,percent:Int){
        mutableStatus.value=Status(false,percent,message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(NOTICE,notification(message,percent,false))
        stopSelf()
    }
    override fun onTimeout(startId:Int,fgsType:Int){exporter.cancel();finish("Export stopped at Android's background processing time limit",0)}
    override fun onDestroy(){
        exporter.close()
        wake?.let{if(it.isHeld)it.release()};wake=null
        if(state.value.busy)mutableStatus.value=Status(false,0,"Export interrupted; retry from the editor")
        super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
}
