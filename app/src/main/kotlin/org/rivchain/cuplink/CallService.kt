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


class CallService: Service() {

    private val ID_ONGOING_CALL_NOTIFICATION = 201

    var OTHER_NOTIFICATIONS_CHANNEL = 99

    override fun onCreate() {
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

    override fun onBind(intent: Intent?): IBinder? {
        return null;
    }

    val closeService = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            /** here you can pass params, which you want to
             * true use STOP_FOREGROUND_REMOVE,or directyly useing
             * STOP_FOREGROUND_REMOVE, you can also use
             * remove nofitication
             */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }
        }
    }

    var filter = IntentFilter("close.service");
    override fun registerReceiver(r: BroadcastReceiver?, f: IntentFilter?): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                super.registerReceiver(closeService, filter, RECEIVER_NOT_EXPORTED)
            } else {
                super.registerReceiver(closeService, filter)
            }
        } else {
            super.registerReceiver(closeService, filter)
        }
    }
}