package org.rivchain.cuplink

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.rivchain.cuplink.model.Settings

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

    override fun onCreateSession() = SettingsSession()
}

class SettingsSession : Session(), DefaultLifecycleObserver {

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

    override fun onCreateScreen(intent: Intent) = AutoControlScreen(carContext)
}

class AutoControlScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val itemList = ItemList.Builder().apply {
        }.build()

        return ListTemplate.Builder().apply {
            setSingleList(itemList)
            setHeaderAction(Action.BACK)
        }.build()
    }
}