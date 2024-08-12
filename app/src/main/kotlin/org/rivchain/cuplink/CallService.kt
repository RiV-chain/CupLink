package org.rivchain.cuplink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import org.rivchain.cuplink.call.RTCPeerConnection
import org.rivchain.cuplink.model.Contact
import org.rivchain.cuplink.model.Event
import org.rivchain.cuplink.util.Log
import org.rivchain.cuplink.util.ServiceUtil
import java.util.Date

class CallService : Service() {

    private var callServiceReceiver: CallServiceReceiver? = null
    private var screenReceiver: ScreenReceiver? = null

    private lateinit var vibrator: Vibrator
    private lateinit var ringtone: Ringtone

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
        initRinging()
    }

    private fun initRinging() {
        Log.d(this, "initRinging")

        // init ringtone
        ringtone = RingtoneManager.getRingtone(
            this,
            RingtoneManager.getActualDefaultRingtoneUri(
                applicationContext,
                RingtoneManager.TYPE_RINGTONE
            )
        )

        // init vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = ServiceUtil.getVibratorService(this)
            vibrator = vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun startRinging() {
        Log.d(this, "startRinging()")
        val ringerMode = ServiceUtil.getAudioManager(this).ringerMode
        if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
            return
        }

        val pattern = longArrayOf(1500, 800, 800, 800)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val vibe = VibrationEffect.createWaveform(pattern, 1)
            vibrator.vibrate(vibe)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, 1)
        }

        if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            return
        }
        ringtone.play()
    }

    private fun stopRinging() {
        Log.d(this, "stopRinging()")
        vibrator.cancel()
        ringtone.stop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent == null) {
            return super.onStartCommand(intent, flags, startId)
        }
        val contact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(SERVICE_CONTACT_KEY, Contact::class.java)
        } else {
            intent.getSerializableExtra(SERVICE_CONTACT_KEY)
        } as Contact
        callServiceReceiver = CallServiceReceiver(contact)
        val intentFilter = IntentFilter()
        intentFilter.addAction(START_CALL_ACTION)
        intentFilter.addAction(STOP_CALL_ACTION)
        intentFilter.addAction(DECLINE_CALL_ACTION)

        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)

        filter.addAction(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_USER_PRESENT)
        screenReceiver = ScreenReceiver(contact as Contact)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(callServiceReceiver, intentFilter, RECEIVER_EXPORTED)
            registerReceiver(screenReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(callServiceReceiver, intentFilter)
            registerReceiver(screenReceiver, filter)
        }

        // check screen lock status and run showIncomingNotification when screen is unlocked. check an existing notification.
        NotificationUtils.showIncomingCallNotification(intent, contact, this@CallService)
        Thread {
            RTCPeerConnection.incomingRTCCall?.continueOnIncomingSocket()
        }.start()
        startRinging()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        this.unregisterReceiver(callServiceReceiver)
        this.unregisterReceiver(screenReceiver)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager?)?.cancel(ID_ONGOING_CALL_NOTIFICATION)
        super.onDestroy()
    }

    override fun onBind(arg0: Intent?): IBinder? {
        return null
    }

    inner class CallServiceReceiver(private val contact: Contact) : BroadcastReceiver() {
        override fun onReceive(arg0: Context?, intent: Intent) {
            Log.d(this, "onReceive() action=$intent.action")
            when (intent.action) {
                START_CALL_ACTION -> {
                    // Do nothing
                }
                STOP_CALL_ACTION -> RTCPeerConnection.incomingRTCCall?.decline()
                DECLINE_CALL_ACTION -> {
                    // Notify missed call
                    val event = Event(contact.publicKey, contact.lastWorkingAddress, Event.Type.INCOMING_MISSED, Date())
                    addEvent(event)
                }
                else -> {
                    // For all other actions, do nothing
                }
            }
            stopRinging()
            stopSelf()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager?)?.cancel(ID_ONGOING_CALL_NOTIFICATION)
        }

        private fun addEvent(event: Event) {
            Log.d(this, "addEvent() event.type=${event.type}")

            if (!DatabaseCache.database.settings.disableCallHistory) {
                DatabaseCache.database.events.addEvent(event)
                DatabaseCache.save()
                NotificationUtils.refreshEvents(this@CallService)
            }

            // update notification
            NotificationUtils.updateNotification(this@CallService)
        }
    }

    inner class ScreenReceiver(private var contact: Contact) : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(this, "In Method: ACTION_SCREEN_OFF")
                    // onPause() will be called.
                    // Stop ringing when screen switched off
                    stopRinging()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(this, "In Method: ACTION_SCREEN_ON")
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(this, "In Method: ACTION_USER_PRESENT")
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager?)?.cancelAll()
                    NotificationUtils.showIncomingCallNotification(intent, contact, this@CallService)
                }
            }
        }
    }

    companion object {
        const val START_CALL_ACTION: String = "StartCall"
        const val STOP_CALL_ACTION: String = "StopCall"
        const val DECLINE_CALL_ACTION: String = "DeclineCall"
        const val SERVICE_CONTACT_KEY: String = "ServiceContactKey"
        const val ID_ONGOING_CALL_NOTIFICATION = 201

        var OTHER_NOTIFICATIONS_CHANNEL = 99
    }
}