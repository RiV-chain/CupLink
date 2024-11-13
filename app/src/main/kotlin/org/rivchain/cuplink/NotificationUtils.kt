package org.rivchain.cuplink

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service.NOTIFICATION_SERVICE
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RemoteViews
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.rivchain.cuplink.BaseActivity.Companion.isNightmodeEnabled
import org.rivchain.cuplink.CallService.Companion.ID_ONGOING_CALL_NOTIFICATION
import org.rivchain.cuplink.CallStatusService.Companion.CHANNEL_ID
import org.rivchain.cuplink.model.Contact
import org.rivchain.cuplink.model.Event
import org.rivchain.cuplink.util.Log
import org.rivchain.cuplink.util.RlpUtils
import org.rivchain.cuplink.util.ViewUtil

internal object NotificationUtils {

    private fun showToastDialog(context: AppCompatActivity, message: String?, duration: Long = 2000L) {
        val inflater = context.layoutInflater

        val toolbar = context.findViewById<View>(R.id.toolbar) // Find toolbar by ID
        if (toolbar != null) {
            val logo = context.findViewById<View>(R.id.logo)
            if(logo != null){
                logo.visibility = View.INVISIBLE
            }
            // Inflate the custom layout
            val popupView = inflater.inflate(R.layout.toast_message, null)
            val toastText = popupView.findViewById<TextView>(R.id.toast_text)
            toastText.text = message

            // Create the PopupWindow with wrap content dimensions
            val popupWindow = PopupWindow(popupView,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                false
            )

            // Position the popup above or near the toolbar
            popupWindow.showAtLocation(toolbar, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, toolbar.height/2)

            // Dismiss the PopupWindow after the specified duration
            Handler(Looper.getMainLooper()).postDelayed({
                popupWindow.dismiss()
                if (!context.isFinishing ) {
                    popupWindow.dismiss()
                    if(logo != null){
                        logo.visibility = View.VISIBLE
                    }
                }
            }, duration)
        } else {

            val dialogView = inflater.inflate(R.layout.toast_message, null)
            val toastText = dialogView.findViewById<TextView>(R.id.toast_text)
            toastText.text = message

            val dialog = AlertDialog.Builder(context, R.style.ToastDialog)
                .setView(dialogView)
                .setCancelable(true)
                .create()
            dialog.show()
            // Dismiss the dialog after the specified duration
            Handler(Looper.getMainLooper()).postDelayed({
                if (dialog.isShowing) {
                    if (!context.isFinishing ) {
                        dialog.dismiss()
                    }
                }
            }, duration)
        }
    }

    private fun showToastDialog(context: AppCompatActivity, resId: Int) {
        val message = context.resources.getText(resId).toString()
        showToastDialog(context, message)
    }

    fun showToastMessage(context: Context, message: String?){
        val lifecycle = ViewUtil.getActivityLifecycle(context)
        if(lifecycle != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            if (context is AppCompatActivity) {
                showToastDialog(context, message)
            }
        } else {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    fun showToastMessage(context: Context, resId: Int){
        val lifecycle = ViewUtil.getActivityLifecycle(context)
        if(lifecycle != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            if (context is AppCompatActivity) {
                showToastDialog(context, resId)
            }
        } else {
            Toast.makeText(context, resId, Toast.LENGTH_LONG).show()
        }
    }

    fun showToastMessageMainLooper(context: Context, message: String?){
        Handler(context.mainLooper).post {
            showToastMessage(context, message)
        }
    }

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
                val e = lastMissedEvents.find { event ->
                    org.rivchain.cuplink.util.Utils.byteArrayToCRC32Int(event.last().publicKey) == callerChannelId
                }?.last()!!

                var contact = e.publicKey.let { DatabaseCache.database.contacts.getContactByPublicKey(it) }
                if (contact == null) {
                    // unknown caller
                    contact = Contact(context.getString(R.string.unknown_caller), e.publicKey.clone(), listOf(e.address?.address?.hostAddress!!))
                }
                val name = contact.name
                val message = String.format(context.getString(R.string.missed_call_from), name, missedCount)

                val notification = createNotification(context, contact, message, true)
                manager.notify(callerChannelId, notification)
            }

            // Cancel notifications for each caller having no missed calls
            for (e in nonLastMissedEvents) {
                val callerChannelId = org.rivchain.cuplink.util.Utils.byteArrayToCRC32Int(e.last().publicKey)
                manager.cancel(callerChannelId)
            }
        }
    }

    private fun createNotification(context: Context, contact: Contact, text: String, showSinceWhen: Boolean): Notification {
        Log.d(this, "createNotification() text=$text setShowWhen=$showSinceWhen")
        val channelId = "cuplink_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId,
                "CupLink Call Listener",
                NotificationManager.IMPORTANCE_LOW, // display notification as collapsed by default
            ).apply {
                setShowBadge(true)
                lightColor = Color.RED
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
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

        var endTitle: CharSequence =
            context.getString(R.string.mark_as_read)
        var answerTitle: CharSequence =
            context.getString(R.string.call)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            endTitle = SpannableString(endTitle)
            endTitle.setSpan(ForegroundColorSpan(-0xbbcca), 0, endTitle.length, 0)
            answerTitle = SpannableString(answerTitle)
            answerTitle.setSpan(ForegroundColorSpan(-0xff5600), 0, answerTitle.length, 0)
        }

        val callPendingIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            Intent(
                context,
                CallActivity::class.java
            ).setAction("ACTION_OUTGOING_CALL").putExtra("EXTRA_CONTACT", contact),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context.applicationContext, channelId)
            .setSilent(true)
            .setAutoCancel(true)
            .setOngoing(true)
            .setShowWhen(showSinceWhen)
            .setUsesChronometer(false)
            .setSmallIcon(R.drawable.cup_link_small)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentText(text)
            .setContentIntent(pendingNotificationIntent)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())

        builder.setContentText(text)

        val customView = RemoteViews(
            context.packageName,
            R.layout.notification_missed_call
        )
        customView.setTextViewText(R.id.name, contact.name)
        customView.setTextViewText(
            R.id.title,
            text
        )

        val avatar: Bitmap? = AppCompatResources.getDrawable(context, R.drawable.ic_contacts)?.toBitmap()
        customView.setTextViewText(
            R.id.answer_text,
            context.getString(R.string.call)
        )
        customView.setTextViewText(
            R.id.decline_text,
            context.getString(R.string.mark_as_read)
        )

        val defaultColor = Color.parseColor(if (isNightmodeEnabled(context)) "#ffffff" else "#000000")
        customView.setTextColor(
            R.id.title,
            defaultColor
        )
        customView.setTextColor(
            R.id.answer_text,
            defaultColor
        )
        customView.setTextColor(
            R.id.decline_text,
            defaultColor
        )
        //customView.setImageViewBitmap(R.id.photo, avatar)
        customView.setOnClickPendingIntent(R.id.answer_btn, callPendingIntent)
        //builder.setLargeIcon(avatar)
        val n: Notification = builder.build()
        n.bigContentView = customView
        n.headsUpContentView = n.bigContentView

        n.flags = n.flags or (Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT)
        if(contact.name.isEmpty()){
            contact.name = "Unknown caller"
            contact.addresses = arrayListOf(contact.lastWorkingAddress!!.address.toString())
        }
        return n
    }

    fun showIncomingNotification(
        intent: Intent,
        contact: Contact,
        service: CallService,
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
                ).apply {
                    description = service.getString(
                        R.string.call_ringing
                    )
                    enableVibration(false)
                    enableLights(false)
                    setBypassDnd(true)
                    setShowBadge(false)
                }
                try {
                    chan.setSound(null, attrs)
                } catch (e: java.lang.Exception) {
                    Log.e(this, e.toString())
                }

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                service.startForeground(
                    ID_ONGOING_CALL_NOTIFICATION,
                    incomingNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            } else {
                service.startForeground(
                    ID_ONGOING_CALL_NOTIFICATION,
                    incomingNotification
                )
            }
            CarNotificationManager.from(service).notify(ID_ONGOING_CALL_NOTIFICATION, builder)
        }.start()
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Status Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    fun buildNotification(context: Context, contact: Contact?): Notification {
        // Create an Intent to open CallActivity when the user taps the notification
        val notificationIntent = Intent(context, CallActivity::class.java)
        notificationIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT

        // Create a PendingIntent that wraps the intent for launching CallActivity
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val text: String = if(contact != null){
            "Calling "+contact.name
        } else {
            ""
        }
        createNotificationChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(text)
            .setSmallIcon(R.drawable.cup_link_small)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent) // Attach the PendingIntent to the notification
            .setAutoCancel(false) // Make sure notification is not dismissed when tapped
            .setShowWhen(false)
            .setUsesChronometer(true)
            .build()
    }
}