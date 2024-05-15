package org.rivchain.cuplink

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.IBinder
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.rivchain.cuplink.automotive.AutoControlScreen

@RequiresApi(Build.VERSION_CODES.O)
class CupLinkCarService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            // FIXME
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
            //HostValidator.Builder(applicationContext)
            //    .addAllowedHosts(R.array.hosts_allowlist)
            //    .build()
        }
    }

    override fun onCreate() {
        bindToMainService(this)
        super.onCreate()
    }

    override fun onDestroy() {
        unbindFromMainService(this)
        super.onDestroy()
    }

    override fun onCreateSession() = SettingsSession(mainServiceBinder)

    private var mainServiceBinder: MainService.MainBinder? = null
    private var mainServiceConnection: ServiceConnection? = null
    private fun bindToMainService(context: Context) {
        mainServiceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                mainServiceBinder = service as? MainService.MainBinder
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                mainServiceBinder = null
            }
        }

        val intent = Intent(context, MainService::class.java)
        context.bindService(intent, mainServiceConnection!!, Context.BIND_AUTO_CREATE)
    }

    private fun unbindFromMainService(context: Context) {
        mainServiceBinder = null
        mainServiceConnection?.let {
            context.unbindService(it)
            mainServiceConnection = null
        }
    }

}
@RequiresApi(Build.VERSION_CODES.M)
class SettingsSession(private var mainServiceBinder: MainService.MainBinder?) : Session(), DefaultLifecycleObserver {

    init {
        lifecycle.addObserver(this@SettingsSession)
    }

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        carContext.onBackPressedDispatcher.addCallback(this@SettingsSession, object : OnBackPressedCallback(true) {
            /**
             * Finish the app when the back button is pressed on the root menu
             */
            override fun handleOnBackPressed() {
                val screenManager = carContext.getCarService(ScreenManager::class.java)
                when {
                    screenManager.stackSize > 1 -> screenManager.pop()
                    else -> carContext.finishCarApp()
                }
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        // fix NPE below for mainServiceBinder
        return AutoControlScreen(carContext, mainServiceBinder!!.getContacts())
    }
}