package org.rivchain.cuplink

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.Service.NOTIFICATION_SERVICE
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.RemoteViews
import androidx.appcompat.content.res.AppCompatResources
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.toBitmap
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.rivchain.cuplink.CallService.Companion.ID_ONGOING_CALL_NOTIFICATION
import org.rivchain.cuplink.model.Contact
import org.rivchain.cuplink.model.Event
import org.rivchain.cuplink.util.Log
import org.rivchain.cuplink.util.RlpUtils

internal object NotificationUtils {

    fun refreshContacts(context: Context) {
        LocalBroadcastManager.getInstance(context.applicationContext)
            .sendBroadcast(Intent("refresh_contact_list"))
    }

    fun refreshEvents(context: Context) {
        LocalBroadcastManager.getInstance(context)
            .sendBroadcast(Intent("refresh_event_list"))
    }

    fun updateNotification(context: Context) {
        Log.d(this, "updateNotification()")

        if (!DatabaseCache.database.settings.disableCallHistory) {
            val eventList = DatabaseCache.database.events.eventList
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Map to track the last event per callerChannelId
            val eventsMap = mutableMapOf<Int, ArrayList<Event>>()

            // Populate the events per callerChannelId
            for (event in eventList) {
                val callerChannelId = org.rivchain.cuplink.util.Utils.byteArrayToCRC32Int(event.publicKey)
                if(eventsMap[callerChannelId] == null){
                    eventsMap[callerChannelId] = ArrayList()
                }
                eventsMap[callerChannelId]!!.add(event)
            }

            // Filter the last events to get only those that are missed calls
            val lastMissedEvents = eventsMap.values.filter { e ->
                e.last().type == Event.Type.INCOMING_MISSED
            }

            // Filter the last events to get only those that are not missed calls
            val nonLastMissedEvents = eventsMap.values.filter { e ->
                e.last().type != Event.Type.INCOMING_MISSED
            }

            // Map to track missed calls per callerChannelId
            val missedCallCounts = mutableMapOf<Int, Int>()

            // Populate the missed call counts map
            for (el in lastMissedEvents) {
                for (e in el.reversed()) {
                    if(e.type != Event.Type.INCOMING_MISSED){
                        break
                    }
                    val callerChannelId =
                        org.rivchain.cuplink.util.Utils.byteArrayToCRC32Int(e.publicKey)
                    val currentCount = missedCallCounts[callerChannelId] ?: 0
                    missedCallCounts[callerChannelId] = currentCount + 1
                }
            }

            // Generate notifications for each callerChannelId if missedCount > 1
            for ((callerChannelId, missedCount) in missedCallCounts) {
                val publicKey = lastMissedEvents.find { event ->
                    org.rivchain.cuplink.util.Utils.byteArrayToCRC32Int(event.last().publicKey) == callerChannelId
                }?.last()!!.publicKey

                val contact = publicKey.let { DatabaseCache.database.contacts.getContactByPublicKey(it) }
                val name = contact?.name ?: context.getString(R.string.unknown_caller)
                val message = String.format(context.getString(R.string.missed_call_from), name, missedCount)

                val notification = createNotification(context, message, false)
                manager.notify(callerChannelId, notification)
            }

            // Cancel notifications for each caller having no missed calls
            for (e in nonLastMissedEvents) {
                val callerChannelId = org.rivchain.cuplink.util.Utils.byteArrayToCRC32Int(e.last().publicKey)
                manager.cancel(callerChannelId)
            }
        }
    }

    private fun createNotification(context: Context, text: String, showSinceWhen: Boolean): Notification {
        Log.d(this, "createNotification() text=$text setShowWhen=$showSinceWhen")
        val channelId = "cuplink_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId,
                "CupLink Call Listener",
                NotificationManager.IMPORTANCE_LOW // display notification as collapsed by default
            )
            chan.lightColor = Color.RED
            chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val service = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            service.createNotificationChannel(chan)
        }

        // start MainActivity
        val notificationIntent = Intent(context, MainActivity::class.java)
        val pendingNotificationIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(context, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        return NotificationCompat.Builder(context.applicationContext, channelId)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(showSinceWhen)
            .setUsesChronometer(showSinceWhen)
            .setSmallIcon(R.drawable.cup_link_small)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentText(text)
            .setContentIntent(pendingNotificationIntent)
            .build()
    }

    fun showIncomingCallNotification(
        intent: Intent,
        contact: Contact,
        service: Service,
    ) {

        val builder = NotificationCompat.Builder(service)
            .setContentTitle(
                service.getString(R.string.is_calling)
            )
            .setSmallIcon(R.drawable.ic_call_accept)
            .setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE
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
                    service.stopSelf()
                    return
                }
            }
            builder.setChannelId("incoming_calls4$chanIndex")
        } else {
            builder.setSound(null)
        }
        var endTitle: CharSequence =
            service.getString(R.string.call_denied)
        var answerTitle: CharSequence =
            service.getString(R.string.call_connected)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            endTitle = SpannableString(endTitle)
            endTitle.setSpan(ForegroundColorSpan(-0xbbcca), 0, endTitle.length, 0)
            answerTitle = SpannableString(answerTitle)
            answerTitle.setSpan(ForegroundColorSpan(-0xff5600), 0, answerTitle.length, 0)
        }

        val flag =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        val endPendingIntent = PendingIntent.getBroadcast(
            service,
            System.currentTimeMillis().toInt(),
            Intent().apply {
                action = CallService.STOP_CALL_ACTION
            },
            flag
        )

        val answerPendingIntent = PendingIntent.getActivity(
            service,
            System.currentTimeMillis().toInt(),
            Intent(
                service,
                CallActivity::class.java
            ).setAction("ANSWER_INCOMING_CALL").putExtra("EXTRA_CONTACT", contact),
            PendingIntent.FLAG_IMMUTABLE
        )

        builder.setPriority(NotificationCompat.PRIORITY_MAX)
            //.setWhen(0)
            .setOngoing(true)
            //.setShowWhen(false)
            .setColor(-0xd35a20)
            .setVibrate(LongArray(0))
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(
                PendingIntent.getActivity(
                    service,
                    System.currentTimeMillis().toInt(),
                    intent,
                    PendingIntent.FLAG_IMMUTABLE
                ), true
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        val incomingNotification: Notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val avatar: Bitmap? = AppCompatResources.getDrawable(service, R.drawable.ic_contacts)?.toBitmap()
            var personName: String = contact.name
            if (TextUtils.isEmpty(personName)) {
                //java.lang.IllegalArgumentException: person must have a non-empty a name
                personName = "Unknown contact"
            }
            val person: Person.Builder = Person.Builder()
                .setImportant(true)
                .setName(personName)
            //.setIcon(Icon.createWithAdaptiveBitmap(avatar)).build()
            val notificationStyle =
                NotificationCompat.CallStyle.forIncomingCall(person.build(), endPendingIntent, answerPendingIntent)

            builder.setStyle(notificationStyle)
            incomingNotification = builder.build()
        } else {
            builder.addAction(R.drawable.ic_close, endTitle, endPendingIntent)
            builder.addAction(R.drawable.ic_audio_device_phone, answerTitle, answerPendingIntent)
            builder.setContentText(contact.name)

            val customView = RemoteViews(
                service.packageName,
                R.layout.notification_call_rtl
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
            incomingNotification = builder.build()
            incomingNotification.bigContentView = customView
            incomingNotification.headsUpContentView = incomingNotification.bigContentView
        }
        incomingNotification.flags = incomingNotification.flags or (Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT)
        if(contact.name.isEmpty()){
            contact.name = "Unknown caller"
            contact.addresses = arrayListOf(contact.lastWorkingAddress!!.address.toString())
        }
        Thread {
            val answerCarPendingIntent = CarPendingIntent.getCarApp(
                service.applicationContext,
                System.currentTimeMillis().toInt(),
                Intent(Intent.ACTION_ANSWER)
                    .setComponent(ComponentName(service, CarService::class.java))
                    .setData(Uri.parse(RlpUtils.generateLink(contact))),
                PendingIntent.FLAG_IMMUTABLE
            )

            builder.extend(
                CarAppExtender.Builder()
                    .setLargeIcon(
                        AppCompatResources.getDrawable(service, R.drawable.cup_link)!!.toBitmap()
                    )
                    .setImportance(NotificationManager.IMPORTANCE_HIGH)
                    .setSmallIcon(R.drawable.dialog_rounded_corner)
                    .addAction(
                        R.drawable.ic_audio_device_phone,
                        answerTitle,
                        answerCarPendingIntent
                    )
                    .addAction(R.drawable.ic_close, endTitle, endPendingIntent)
                    .build()
            )

            service.startForeground(ID_ONGOING_CALL_NOTIFICATION, incomingNotification)
            CarNotificationManager.from(service).notify(ID_ONGOING_CALL_NOTIFICATION, builder)
        }.start()
    }
}