package org.rivchain.cuplink

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import org.rivchain.cuplink.rivmesh.models.PeerInfo
import org.rivchain.cuplink.util.Log
import org.rivchain.cuplink.util.Utils.readInternalFile
import java.io.File
import java.net.InetAddress

/*
 * Base class for every Activity
*/
open class BaseActivity : AppCompatActivity(), ServiceConnection {

    var service: MainService? = null
    lateinit var database: Database
    var firstStart = false
    var databasePassword = ""
    var dbEncrypted: Boolean = false
    var databasePath = ""

    protected fun loadDatabase(databasePath: String) {
        val database: Database
        if (File(databasePath).exists()) {
            // Open an existing database
            val db = readInternalFile(databasePath)
            database = Database.fromData(db, databasePassword)
            firstStart = false
        } else {
            // Create a new database
            database = Database()
            database.mesh.invoke()
            // Generate random port from allowed range
            val port = org.rivchain.cuplink.rivmesh.util.Utils.generateRandomPort()
            val localPeer = PeerInfo("tcp", InetAddress.getByName("0.0.0.0"), port, null, false)
            database.mesh.setListen(setOf(localPeer))
            database.mesh.multicastRegex = ".*"
            database.mesh.multicastListen = true
            database.mesh.multicastBeacon = true
            database.mesh.multicastPassword = ""
            firstStart = true
        }
        this.database = database
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        bindService(Intent(this, MainService::class.java), this, 0)
        MainService.init(this)
    }

    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
        Log.d(this, "onServiceConnected")
        service = (iBinder as MainService.MainBinder).getService()
        Log.d(this, "onCreate: load database")
        databasePath = this.filesDir.toString() + "/database.bin"
        // open without password
        try {
            loadDatabase(databasePath)
        } catch (e: Database.WrongPasswordException) {
            // ignore and continue with initialization,
            // the password dialog comes on the next startState
            dbEncrypted = true
        } catch (e: Exception) {
            Log.e(this, "${e.message}")
            Toast.makeText(this, "${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
        // start MainService and call back via onServiceConnected()
        service!!.database = database
        service!!.databasePath = databasePath
        service!!.databasePassword = databasePassword
        MainService.startPacketsStream(this)
    }

    override fun onServiceDisconnected(componentName: ComponentName) {
        // nothing to do
    }

    protected open fun onServiceRestart(){

    }

    protected open fun restartService(){
        // Inflate the layout for the dialog
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_restart_service, null)
        // Create the AlertDialog
        val serviceRestartDialog = AlertDialog.Builder(this, R.style.PPTCDialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        // Show the dialog
        serviceRestartDialog.show()

        // Restart service
        val intentStop = Intent(this, MainService::class.java)
        intentStop.action = MainService.ACTION_STOP
        startService(intentStop)


        Thread {
            Thread.sleep(1000)
            bindService(Intent(this, MainService::class.java), this, 0)
            MainService.init(this)
            Thread.sleep(2000)
            runOnUiThread {
                serviceRestartDialog.dismiss()
                onServiceRestart()
            }
        }.start()
    }

    fun setDefaultNightMode(nightModeSetting: String) {
        nightMode = when (nightModeSetting) {
            "on" -> AppCompatDelegate.MODE_NIGHT_YES
            "off" -> AppCompatDelegate.MODE_NIGHT_NO
            "auto" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                } else {
                    AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
                }
            else -> {
                Log.e(this, "invalid night mode setting: $nightModeSetting")
                nightMode
            }
        }
    }

    // prefer to be called before super.onCreate()
    fun applyNightMode() {
        if (nightMode != AppCompatDelegate.getDefaultNightMode()) {
            Log.d(this, "Change night mode to $nightMode")
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }

    companion object {
        private var nightMode = AppCompatDelegate.getDefaultNightMode()

        fun isNightmodeEnabled(context: Context): Boolean {
            val mode = context.resources?.configuration?.uiMode?.and(Configuration.UI_MODE_NIGHT_MASK)
            return (mode == Configuration.UI_MODE_NIGHT_YES)
        }
    }
}