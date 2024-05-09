package org.rivchain.cuplink

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.IBinder

class CallService : Service() {
    var notifyServiceReceiver: NotifyServiceReceiver? = null


    private val myBlog = "http://www.cs.dartmouth.edu/~campbell/cs65/cs65.html"

    override fun onCreate() {
        notifyServiceReceiver = NotifyServiceReceiver()
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val intentFilter = IntentFilter()
        intentFilter.addAction(ACTION)
        registerReceiver(notifyServiceReceiver, intentFilter)


        // Send Notification
        val notificationTitle = "Calling"
        val notificationText = ""
        val myIntent = Intent(Intent.ACTION_VIEW, Uri.parse(myBlog))
        val pendingIntent = PendingIntent.getActivity(
            baseContext,
            0, myIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )


        val notification: Notification = Notification.Builder(this)
            .setContentTitle(notificationTitle)
            .setContentText(notificationText).setSmallIcon(R.drawable.cup_link_small)
            .setContentIntent(pendingIntent).build()
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager?
        notification.flags = (notification.flags
                or Notification.FLAG_ONGOING_EVENT)
        notification.flags = notification.flags or Notification.FLAG_AUTO_CANCEL

        notificationManager!!.notify(0, notification)

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
    }
}