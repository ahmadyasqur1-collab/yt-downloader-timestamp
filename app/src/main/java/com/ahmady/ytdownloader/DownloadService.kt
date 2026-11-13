package com.ahmady.ytdownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Penahan proses supaya unduhan tetap jalan saat app dibuka di latar belakang. */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = "download"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(channel, "Unduhan", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, channel)
            .setContentTitle("YT Downloader")
            .setContentText("Aktif — unduhan berjalan di latar belakang")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }
}
