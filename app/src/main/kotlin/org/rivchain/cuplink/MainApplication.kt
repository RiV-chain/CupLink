package org.rivchain.cuplink

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import org.acra.config.dialog
import org.acra.config.httpSender
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import org.acra.sender.HttpSender
import org.libsodium.jni.NaCl
import org.rivchain.cuplink.call.CallActivity
import org.rivchain.cuplink.rivmesh.AppStateReceiver
import org.rivchain.cuplink.rivmesh.MeshTileService
import org.rivchain.cuplink.rivmesh.State
import org.rivchain.cuplink.util.Log

const val PREF_KEY_ENABLED = "enabled"
const val MAIN_CHANNEL_ID = "CupLink Service"
class MainApplication : Application(), AppStateReceiver.StateReceiver, LifecycleObserver {

    private var currentState: State = State.Disabled

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        initAcra {
            buildConfigClass = BuildConfig::class.java
            reportFormat = StringFormat.JSON
            //each plugin you chose above can be configured in a block like this:
            httpSender {
                uri = "https://acrarium.rivchain.org/acrarium/report"
                basicAuthLogin = "TaQSCEkjRDuoCBCi"
                basicAuthPassword = "j4LeJkjPoAtU2afo"
                httpMethod = HttpSender.Method.POST
            }
            dialog {
                //required
                text = getString(R.string.report_dialog_text)
                //optional, enables the dialog title
                title = getString(R.string.app_name)
                //defaults to android.R.string.ok
                positiveButtonText = getString(android.R.string.ok)
                //defaults to android.R.string.cancel
                negativeButtonText = getString(android.R.string.cancel)
                //optional, enables the comment input
                commentPrompt = getString(R.string.report_dialog_comment)
                //optional, enables the email input
                //emailPrompt = getString(R.string.report_dialog_email)
                //defaults to android.R.drawable.ic_dialog_alert
                resIcon = android.R.drawable.ic_dialog_alert
                //optional, defaults to @android:style/Theme.Dialog
                resTheme = R.style.AppTheme
            }
        }
    }

    var updaterConnections: Int = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleObserver(this))
        val receiver = AppStateReceiver(this)
        receiver.register(this)
        // Prevent UnsatisfiedLinkError
        NaCl.sodium()
        // open without password
        Log.d(this, "init 1: load database")
        DatabaseCache.databasePath = this.filesDir.toString() + "/database.bin"
        DatabaseCache.isDecrypted()
        Log.d(this, "init 1: load database complete")
    }

    fun subscribe() {
        updaterConnections++
    }

    fun unsubscribe() {
        if (updaterConnections > 0) {
            updaterConnections--
        }
    }

    fun needUiUpdates(): Boolean {
        return updaterConnections > 0
    }

    fun getCurrentState(): State {
        return currentState
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onStateChange(state: State) {
        if (state != currentState) {
            val componentName = ComponentName(this, MeshTileService::class.java)
            TileService.requestListeningState(this, componentName)

            if (state != State.Disabled) {
                val notification = createServiceNotification(this, state)
                val notificationManager: NotificationManager =
                    this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(SERVICE_NOTIFICATION_ID, notification)
            }

            currentState = state
        }
    }

    companion object {
        @Volatile
        private var instance: MainApplication? = null

        @JvmStatic
        fun getAppContext(): Context {
            return instance?.applicationContext
                ?: throw IllegalStateException("Application instance is null!")
        }
    }
}

fun createServiceNotification(context: Context, state: State): Notification {
    createNotificationChannels(context)

    val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT }
    var flags = PendingIntent.FLAG_UPDATE_CURRENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
    val pendingIntent: PendingIntent = PendingIntent.getActivity(context, 0, intent, flags)

    val text = when (state) {
        State.Disabled -> context.getText(R.string.tile_connecting)
        State.Enabled -> context.getText(R.string.tile_enabled)
        State.Connected -> context.getText(R.string.tile_connected)
        State.Calling -> context.getText(R.string.is_calling)
        State.CallEnded -> context.getText(R.string.call_ended)
        else -> context.getText(R.string.tile_connecting)
    }

    return NotificationCompat.Builder(context, MAIN_CHANNEL_ID)
        .setShowWhen(false)
        .setContentTitle(text)
        .setSmallIcon(R.drawable.cup_link_small)
        .setContentIntent(pendingIntent)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()
}

fun createPermissionMissingNotification(context: Context): Notification {
    createNotificationChannels(context)
    val intent = Intent(context, MainActivity::class.java).apply {
        this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    var flags = PendingIntent.FLAG_UPDATE_CURRENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
    val pendingIntent: PendingIntent = PendingIntent.getActivity(context, 0, intent, flags)

    return NotificationCompat.Builder(context, MAIN_CHANNEL_ID)
        .setShowWhen(false)
        .setContentTitle(context.getText(R.string.app_name))
        .setContentText(context.getText(R.string.permission_notification_text))
        .setSmallIcon(R.drawable.cup_link_small)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
}

private fun createNotificationChannels(context: Context) {
    // Create the NotificationChannel, but only on API 26+ because
    // the NotificationChannel class is new and not in the support library
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val name = context.getString(R.string.channel_name)
        val descriptionText = context.getString(R.string.channel_description)
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(MAIN_CHANNEL_ID, name, importance).apply {
            description = descriptionText
            setShowBadge(false)
        }
        // Register the channel with the system
        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}

class AppLifecycleObserver(private val context: Context) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        if (CallActivity.isCallInProgress) {
            val intent = Intent(context, CallActivity::class.java).apply {
                flags =  Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            context.startActivity(intent)
        }
    }
}