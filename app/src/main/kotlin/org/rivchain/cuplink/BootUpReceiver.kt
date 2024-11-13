package org.rivchain.cuplink

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService

import androidx.core.content.ContextCompat
import org.rivchain.cuplink.NotificationUtils.showToastMessage
import org.rivchain.cuplink.util.Log

/*
 * Start App on Android bootup. onReceive methods loads database and starts VPN service.
 * Activity start is not allowed from background here.
 */
class BootUpReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent != null) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                Log.i(TAG, "CupLink enabled, starting service")
                val serviceIntent = Intent(context, MainService::class.java)
                serviceIntent.action = MainService.ACTION_START

                val vpnIntent = VpnService.prepare(context)
                if (vpnIntent != null) {
                    Log.i(TAG, "Need to ask for VPN permission")
                    val notification = createPermissionMissingNotification(context)
                    val manager =
                        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(444, notification)
                } else {
                    ContextCompat.startForegroundService(context, serviceIntent)
                }
            }
        }
    }

    companion object {

        const val TAG = "BootUpReceiver"

        const val IS_START_ON_BOOTUP = "IS_START_ON_BOOTUP"

        fun setEnabled(context: Context, enabled: Boolean) {
            val newState = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }

            try {
                context.packageManager
                    .setComponentEnabledSetting(
                        ComponentName(context, BootUpReceiver::class.java),
                        newState, PackageManager.DONT_KILL_APP)
            } catch (e: Exception) {
                showToastMessage(context,  e.toString())
            }
        }
    }
}
