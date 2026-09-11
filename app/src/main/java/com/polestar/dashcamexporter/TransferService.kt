package com.polestar.dashcamexporter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var running = false
    private var workInProgress = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val controller get() = (application as ExporterApplication).controller
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") { controller.cancel(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        running = true
        workInProgress = true
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("transfer", "파일 전송", NotificationManager.IMPORTANCE_LOW))
        fun notification(text: String) = NotificationCompat.Builder(this, "transfer")
            .setSmallIcon(R.drawable.ic_dashcam).setContentTitle("Dashcam Exporter")
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "취소", PendingIntent.getService(this, 1,
                Intent(this, TransferService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE))
            .build()
        startForeground(1, notification("파일 전송 준비 중"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dashcam:transfer")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        scope.launch {
            val updates = launch { controller.state.collect { manager.notify(1, notification(it.progressText)) } }
            try { controller.runPendingTransfer() }
            finally { workInProgress = false; updates.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { controller.cancel(); stopSelf() }
    override fun onDestroy() {
        if (workInProgress) controller.cancel()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
