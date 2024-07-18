package org.rivchain.cuplink

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.widget.Toast
import org.libsodium.jni.NaCl
import org.rivchain.cuplink.util.Log

/*
 * Start App on Android bootup. onReceive methods loads database and starts VPN service.
 * Activity start is not allowed from background here.
 */
class BootUpReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent != null) {
            if(intent.action == Intent.ACTION_BOOT_COMPLETED) {
                Load.databasePath = context.filesDir.toString() + "/database.bin"
                // Prevent UnsatisfiedLinkError
                NaCl.sodium()
                Log.d(this, "bootup: load database")
                // open without password
                try {
                    Load.database()
                } catch (e: Database.WrongPasswordException) {
                    // ignore and continue with initialization,
                    // the password dialog comes on the next startState
                    Load.dbEncrypted = true
                } catch (e: Exception) {
                    Log.e(this, "${e.message}")
                    return
                }

                Log.d(this, "bootup: load database complete")

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
                    context.startService(serviceIntent)
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
                Toast.makeText(context, e.toString(), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
