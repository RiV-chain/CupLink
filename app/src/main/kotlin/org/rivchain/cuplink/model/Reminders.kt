package org.rivchain.cuplink.model

import org.json.JSONArray
import org.json.JSONObject

class Reminders {

    var reminderList = mutableListOf<Reminder>()

    fun addReminder(reminder: Reminder) {
        if (reminder !in reminderList) {
            reminderList.add(reminder)

            // sort by date / oldest first
            reminderList.sortWith { lhs: Reminder, rhs: Reminder -> lhs.scheduledTime.compareTo(rhs.scheduledTime) }

            while (reminderList.size > 100) {
                // remove first item
                reminderList.removeAt(0)
            }
        }
    }

    companion object {
        fun fromJSON(obj: JSONObject): Reminders {
            val remindersList = mutableListOf<Reminder>()
            val array = obj.getJSONArray("entries")
            for (i in 0 until array.length()) {
                remindersList.add(
                    Reminder.fromJSON(array.getJSONObject(i))
                )
            }

            // sort by date / oldest first
            remindersList.sortWith { lhs: Reminder, rhs: Reminder -> lhs.scheduledTime.compareTo(rhs.scheduledTime) }

            val reminders = Reminders()
            reminders.reminderList.addAll(remindersList)
            return reminders
        }

        fun toJSON(reminders: Reminders): JSONObject {
            val obj = JSONObject()
            val array = JSONArray()
            for (reminder in reminders.reminderList) {
                array.put(Reminder.toJSON(reminder))
            }
            obj.put("entries", array)
            return obj
        }
    }
}