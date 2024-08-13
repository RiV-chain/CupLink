package org.rivchain.cuplink.model

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
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
import org.rivchain.cuplink.util.ServiceUtil
import java.util.Calendar
import java.util.TimeZone

class ReminderViewModel(application: Application) : AndroidViewModel(application) {

    private fun convertToUTC(localTime: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = localTime
        }
        val timeZoneOffset = calendar.timeZone.getOffset(localTime)
        return localTime - timeZoneOffset
    }

    private fun convertToLocalTime(utcTime: Long): Long {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = utcTime
        }
        val localCalendar = Calendar.getInstance().apply {
            set(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH),
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE)
            )
        }
        return localCalendar.timeInMillis
    }

    fun addReminder(topic: String, scheduledTime: Long, contact: Contact) {
        val reminder = Reminder(topic = topic, scheduledTime = convertToUTC(scheduledTime), contactPublicKey = contact.publicKey)
        viewModelScope.launch {
            DatabaseCache.database.reminders.addReminder(reminder)
            DatabaseCache.save()
            scheduleReminder(reminder)
        }
    }

    fun rescheduleAllReminders() {
        viewModelScope.launch(Dispatchers.IO) {
            val reminders = DatabaseCache.database.reminders.reminderList
            reminders.forEach { reminder ->
                scheduleReminder(reminder)
            }
        }
    }

    private fun scheduleReminder(reminder: Reminder) {
        val alarmManager = ServiceUtil.getAlarmManager(getApplication())

        // Check the permission for Android 12 and above
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
            val intent = Intent(getApplication(), ReminderReceiver::class.java).apply {
                putExtra("topic", reminder.topic)
                putExtra(SERVICE_CONTACT_KEY, reminder.contactPublicKey)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                getApplication(),
                System.currentTimeMillis().toInt(),
                intent,
                PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                reminder.scheduledTime,
                pendingIntent
            )
            else alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                convertToLocalTime(reminder.scheduledTime),
                pendingIntent
            )
        } else {
            Toast.makeText(getApplication(), R.string.missing_alarm_permission, Toast.LENGTH_SHORT).show()
        }
    }
}