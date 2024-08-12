package org.rivchain.cuplink.model

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.rivchain.cuplink.CallService.Companion.SERVICE_CONTACT_KEY
import org.rivchain.cuplink.DatabaseCache
import org.rivchain.cuplink.R
import org.rivchain.cuplink.ReminderReceiver

class ReminderViewModel(application: Application) : AndroidViewModel(application) {


    fun addReminder(topic: String, scheduledTime: Long, contact: Contact) {
        val reminder = Reminder(topic = topic, scheduledTime = scheduledTime, contact = contact)
        viewModelScope.launch {
            DatabaseCache.database.reminders.addReminder(reminder)
            DatabaseCache.save()
            scheduleReminder(reminder)
        }
    }

    fun rescheduleAllReminders() {
        viewModelScope.launch(Dispatchers.IO) {
            val reminders = DatabaseCache.database.reminders.reminders
            reminders.forEach { reminder ->
                scheduleReminder(reminder)
            }
        }
    }

    private fun scheduleReminder(reminder: Reminder) {
        val alarmManager = getApplication<Application>().getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Check the permission for Android 12 and above
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
            val intent = Intent(getApplication(), ReminderReceiver::class.java).apply {
                putExtra("topic", reminder.topic)
                putExtra(SERVICE_CONTACT_KEY, reminder.contact)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                getApplication(),
                System.currentTimeMillis().toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
            )

            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                reminder.scheduledTime,
                pendingIntent
            )
        } else {
            Toast.makeText(getApplication(), R.string.missing_alarm_permission, Toast.LENGTH_SHORT).show()
        }
    }
}