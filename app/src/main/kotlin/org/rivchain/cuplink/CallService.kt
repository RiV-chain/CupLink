package org.rivchain.cuplink

import android.app.Notification
import android.app.Service
import android.content.Intent
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
            startForeground(ID_ONGOING_CALL_NOTIFICATION, bldr.build())
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null;
    }
}