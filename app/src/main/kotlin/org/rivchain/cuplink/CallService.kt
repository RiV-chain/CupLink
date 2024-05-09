package org.rivchain.cuplink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder

class CallService : Service() {
    var notifyServiceReceiver: NotifyServiceReceiver? = null


    private val myBlog = "http://www.cs.dartmouth.edu/~campbell/cs65/cs65.html"

    override fun onCreate() {
        notifyServiceReceiver = NotifyServiceReceiver()
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val bldr =
                Notification.Builder(this, OTHER_NOTIFICATIONS_CHANNEL.toString())
                    .setContentTitle(
                        getString(
                            R.string.is_calling
                        )
                    )
                    .setShowWhen(false)
            bldr.setSmallIcon(R.drawable.ic_audio_device_phone)

            val channel = NotificationChannel(
                OTHER_NOTIFICATIONS_CHANNEL.toString(),
                "CupLink",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = "CupLink calls channel for foreground service notification"
            val n = bldr.build()
            val notificationManager =
                getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            startForeground(ID_ONGOING_CALL_NOTIFICATION, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val intentFilter = IntentFilter()
        intentFilter.addAction(ACTION)
        registerReceiver(notifyServiceReceiver, intentFilter)

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        this.unregisterReceiver(notifyServiceReceiver)
        super.onDestroy()
    }

    override fun onBind(arg0: Intent?): IBinder? {
        return null
    }

    inner class NotifyServiceReceiver : BroadcastReceiver() {
        override fun onReceive(arg0: Context?, arg1: Intent) {
            val rqs = arg1.getIntExtra(STOP_SERVICE_BROADCAST_KEY, 0)

            if (rqs == RQS_STOP_SERVICE) {
                stopSelf()
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager?)
                    ?.cancelAll()
            }
        }
    }

    companion object {
        const val ACTION: String = "NotifyServiceAction"
        const val STOP_SERVICE_BROADCAST_KEY: String = "StopServiceBroadcastKey"
        const val RQS_STOP_SERVICE: Int = 1
        private const val ID_ONGOING_CALL_NOTIFICATION = 201

        var OTHER_NOTIFICATIONS_CHANNEL = 99
    }
}