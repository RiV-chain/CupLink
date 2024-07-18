package org.rivchain.cuplink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.rivchain.cuplink.call.Pinger
import org.rivchain.cuplink.model.Contact
import org.rivchain.cuplink.model.Event
import org.rivchain.cuplink.util.Log
import org.rivchain.cuplink.util.Utils.writeInternalFile
import java.util.Date

/*
 * Base class for every Activity
*/
open class BaseActivity : AppCompatActivity() {

    lateinit var databasePath: String
    var dbEncrypted: Boolean = false
    var firstStart = false
    var databasePassword = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        databasePath = this.filesDir.toString() + "/database.bin"
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
            val intentStart = Intent(this, MainService::class.java)
            intentStart.action = MainService.ACTION_START
            startService(intentStart)
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

    fun getContactOrOwn(otherPublicKey: ByteArray): Contact? {
        val db = Load.database
        return if (db.settings.publicKey.contentEquals(otherPublicKey)) {
            db.settings.getOwnContact()
        } else {
            db.contacts.getContactByPublicKey(otherPublicKey)
        }
    }

    open fun addContact(contact: Contact) {
        Load.database.contacts.addContact(contact)
        saveDatabase()

        pingContacts(listOf(contact))

        refreshContacts()
        refreshEvents()
    }

    fun deleteContact(publicKey: ByteArray) {
        Load.database.contacts.deleteContact(publicKey)
        Load.database.events.deleteEventsByPublicKey(publicKey)
        saveDatabase()

        refreshContacts()
        refreshEvents()
    }

    fun deleteEvents(eventDates: List<Date>) {
        Load.database.events.deleteEventsByDate(eventDates)
        saveDatabase()

        refreshContacts()
        refreshEvents()
    }

    fun pingContacts(contactList: List<Contact>) {
        Log.d(this, "pingContacts()")
        Thread(
            //fix ConcurrentModificationException
            Pinger(this, ArrayList(contactList))
        ).start()
    }

    fun addEvent(event: Event) {
        Log.d(this, "addEvent() event.type=${event.type}")

        if (!Load.database.settings.disableCallHistory) {
            Load.database.events.addEvent(event)
            saveDatabase()
            refreshEvents()
        }

        // update notification
        updateNotification()
    }

    fun clearEvents() {
        Load.database.events.clearEvents()
        refreshEvents()
    }

    fun isDatabaseEncrypted(): Boolean {
        return dbEncrypted
    }


    fun importContacts(newDb: Database){
        val oldDatabase = Load.database
        for (contact in newDb.contacts.contactList) {
            oldDatabase.contacts.addContact(contact)
        }
    }

    fun importCalls(newDb: Database){
        val oldDatabase = Load.database
        for (event in newDb.events.eventList) {
            oldDatabase.events.addEvent(event)
        }
    }

    fun importSettings(newDb: Database){
        val oldDatabase = Load.database
        oldDatabase.settings = newDb.settings
        oldDatabase.mesh = newDb.mesh
    }

    fun saveDatabase() {
        try {
            val db = Load.database
            val dbData = Database.toData(db, databasePassword)
            if (dbData != null) {
                writeInternalFile(databasePath, dbData)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun refreshContacts() {
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent("refresh_contact_list"))
    }

    fun refreshEvents() {
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent("refresh_event_list"))
    }

    private fun updateNotification() {
        Log.d(this, "updateNotification()")

        if (!Load.database.settings.disableCallHistory) {
            val eventList = Load.database.events.eventList
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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

                val contact = publicKey.let { Load.database.contacts.getContactByPublicKey(it) }
                val name = contact?.name ?: getString(R.string.unknown_caller)
                val message = String.format(getString(R.string.missed_call_from), name, missedCount)

                val notification = createNotification(message, false)
                manager.notify(callerChannelId, notification)
            }

            // Cancel notifications for each caller having no missed calls
            for (e in nonLastMissedEvents) {
                val callerChannelId = org.rivchain.cuplink.util.Utils.byteArrayToCRC32Int(e.last().publicKey)
                manager.cancel(callerChannelId)
            }
        }
    }

    private fun createNotification(text: String, showSinceWhen: Boolean): Notification {
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
            val service = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            service.createNotificationChannel(chan)
        }

        // start MainActivity
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingNotificationIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        return NotificationCompat.Builder(applicationContext, channelId)
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

    companion object {
        private var nightMode = AppCompatDelegate.getDefaultNightMode()

        fun isNightmodeEnabled(context: Context): Boolean {
            val mode = context.resources?.configuration?.uiMode?.and(Configuration.UI_MODE_NIGHT_MASK)
            return (mode == Configuration.UI_MODE_NIGHT_YES)
        }
    }
}