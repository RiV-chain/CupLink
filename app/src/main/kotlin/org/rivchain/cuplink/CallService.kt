package org.rivchain.cuplink

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.RemoteViews
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toBitmap
import org.rivchain.cuplink.model.Contact
import org.rivchain.cuplink.util.Log

class CallService : Service() {

    var notifyServiceReceiver: NotifyServiceReceiver? = null

    override fun onCreate() {
        notifyServiceReceiver = NotifyServiceReceiver()
        super.onCreate()
    }

    private fun showIncomingNotification(
        intent: Intent,
        contact: Contact?,
        service: CallService
    ) {

        val builder: Notification.Builder = Notification.Builder(service)
            .setContentTitle(
                service.getString(R.string.is_calling)
            )
            .setSmallIcon(R.drawable.ic_call_accept)
            .setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    intent,
                    PendingIntent.FLAG_MUTABLE
                )
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nprefs: SharedPreferences = service.application.getSharedPreferences("Notifications", Activity.MODE_PRIVATE)
            var chanIndex = nprefs.getInt("calls_notification_channel", 0)
            val nm = service.getSystemService(NOTIFICATION_SERVICE) as NotificationManager?
            var oldChannel = nm!!.getNotificationChannel("incoming_calls2$chanIndex")
            if (oldChannel != null) {
                nm.deleteNotificationChannel(oldChannel.id)
            }
            oldChannel = nm.getNotificationChannel("incoming_calls3$chanIndex")
            if (oldChannel != null) {
                nm.deleteNotificationChannel(oldChannel.id)
            }
            val existingChannel = nm.getNotificationChannel("incoming_calls4$chanIndex")
            var needCreate = true
            if (existingChannel != null) {
                if (existingChannel.importance < NotificationManager.IMPORTANCE_HIGH || existingChannel.sound != null) {

                    Log.d(this, "User messed up the notification channel; deleting it and creating a proper one")

                    nm.deleteNotificationChannel("incoming_calls4$chanIndex")
                    chanIndex++
                    nprefs.edit().putInt("calls_notification_channel", chanIndex).apply()
                } else {
                    needCreate = false
                }
            }
            if (needCreate) {
                val attrs = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setLegacyStreamType(AudioManager.STREAM_RING)
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .build()
                val chan = NotificationChannel(
                    "incoming_calls4$chanIndex",
                    service.getString(
                        R.string.call_ringing
                    ),
                    NotificationManager.IMPORTANCE_HIGH
                )
                try {
                    chan.setSound(null, attrs)
                } catch (e: java.lang.Exception) {
                    Log.e(this, e.toString())
                }
                chan.description = service.getString(
                    R.string.call_ringing
                )
                chan.enableVibration(false)
                chan.enableLights(false)
                chan.setBypassDnd(true)
                try {
                    nm.createNotificationChannel(chan)
                } catch (e: java.lang.Exception) {
                    Log.e(this, e.toString())
                    //this.stopSelf()
                    return
                }
            }
            builder.setChannelId("incoming_calls4$chanIndex")
        } else {
            builder.setSound(null)
        }
        var endTitle: CharSequence =
            service.getString(R.string.call_denied)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            endTitle = SpannableString(endTitle)
            endTitle.setSpan(ForegroundColorSpan(-0xbbcca), 0, endTitle.length, 0)
        }
        val endPendingIntent = PendingIntent.getBroadcast(
            service,
            0,
            Intent().setAction(CallService.ACTION).putExtra(CallService.SERVICE_BROADCAST_KEY,
                CallService.RQS_STOP_SERVICE),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        var answerTitle: CharSequence =
            service.getString(R.string.call_connected)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            answerTitle = SpannableString(answerTitle)
            answerTitle.setSpan(ForegroundColorSpan(-0xff5600), 0, answerTitle.length, 0)
        }

        val answerPendingIntent = PendingIntent.getActivity(
            service,
            0,
            Intent(service, CallActivity::class.java).setAction("ANSWER_INCOMING_CALL").putExtra("EXTRA_CONTACT", contact),
            PendingIntent.FLAG_MUTABLE
        )

        builder.setPriority(Notification.PRIORITY_MAX)
        builder.setShowWhen(false)
        builder.setColor(-0xd35a20)
        builder.setVibrate(LongArray(0))
        builder.setCategory(Notification.CATEGORY_CALL)
        builder.setFullScreenIntent(
            PendingIntent.getActivity(
                service,
                0,
                intent,
                PendingIntent.FLAG_MUTABLE
            ), true
        )
        var incomingNotification: Notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val avatar: Bitmap? = AppCompatResources.getDrawable(service, R.drawable.ic_contacts)?.toBitmap()
            var personName: String = contact!!.name
            if (TextUtils.isEmpty(personName)) {
                //java.lang.IllegalArgumentException: person must have a non-empty a name
                personName = "___"
            }
            val person: Person.Builder = Person.Builder()
                .setName(personName)
            //.setIcon(Icon.createWithAdaptiveBitmap(avatar)).build()
            val notificationStyle =
                Notification.CallStyle.forIncomingCall(person.build(), endPendingIntent, answerPendingIntent)

            builder.setStyle(notificationStyle)
            incomingNotification = builder.build()
        } else {
            builder.addAction(R.drawable.checkbox_rounded_corner, endTitle, endPendingIntent)
            builder.addAction(
                R.drawable.ic_audio_device_phone,
                answerTitle,
                answerPendingIntent
            )
            builder.setContentText(contact!!.name)

            val customView: RemoteViews = RemoteViews(
                service.packageName,
                R.layout.call_notification_rtl
            )
            customView.setTextViewText(R.id.name, contact.name)
            customView.setViewVisibility(R.id.subtitle, View.GONE)
            customView.setTextViewText(
                R.id.title,
                contact.name,
            )

            val avatar: Bitmap? = AppCompatResources.getDrawable(service, R.drawable.ic_contacts)?.toBitmap()
            customView.setTextViewText(
                R.id.answer_text,
                service.getString(R.string.call_connected)
            )
            customView.setTextViewText(
                R.id.decline_text,
                service.getString(R.string.button_abort)
            )
            //customView.setImageViewBitmap(R.id.photo, avatar)
            customView.setOnClickPendingIntent(R.id.answer_btn, answerPendingIntent)
            customView.setOnClickPendingIntent(R.id.decline_btn, endPendingIntent)
            //builder.setLargeIcon(avatar)
            incomingNotification = builder.getNotification()
            incomingNotification.bigContentView = customView
            incomingNotification.headsUpContentView = incomingNotification.bigContentView
        }
        incomingNotification.flags = incomingNotification.flags or (Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT)
        service.startForeground(ID_ONGOING_CALL_NOTIFICATION, incomingNotification)
        //startRingtoneAndVibration()
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
            val rqs = arg1.getIntExtra(SERVICE_BROADCAST_KEY, 0)

            if (rqs == RQS_START_SERVICE) {
                val contact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arg1.getSerializableExtra(SERVICE_CONTACT_KEY, Contact::class.java)
                } else {
                    arg1.getSerializableExtra(SERVICE_CONTACT_KEY)
                }
                showIncomingNotification(arg1, contact as Contact, this@CallService)
            } else
            if (rqs == RQS_STOP_SERVICE) {
                stopSelf()
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager?)
                    ?.cancelAll()
            }
        }
    }

    companion object {
        const val ACTION: String = "NotifyServiceAction"
        const val SERVICE_BROADCAST_KEY: String = "ServiceBroadcastKey"
        const val SERVICE_CONTACT_KEY: String = "ServiceContactKey"
        const val RQS_START_SERVICE: Int = 0
        const val RQS_STOP_SERVICE: Int = 1
        private const val ID_ONGOING_CALL_NOTIFICATION = 201

        var OTHER_NOTIFICATIONS_CHANNEL = 99
    }
}