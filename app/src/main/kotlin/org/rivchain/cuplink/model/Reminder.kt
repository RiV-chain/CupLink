package org.rivchain.cuplink.model

import org.json.JSONObject
import org.rivchain.cuplink.util.RlpUtils

data class Reminder(
    val topic: String,
    val scheduledTime: Long,
    val contact: Contact // Assuming reminder is for a call
) {
    companion object {

        fun toJSON(reminder: Reminder): JSONObject {
            val obj = JSONObject()
            obj.put("topic", reminder.topic)
            obj.put("contact", RlpUtils.generateLink(reminder.contact))
            obj.put("scheduled_time", reminder.scheduledTime)
            return obj
        }

        fun fromJSON(obj: JSONObject): Reminder {
            var contact = RlpUtils.parseLink(obj.getString("contact"))
            if (contact == null) {
                contact = Contact()
            }
            return Reminder(
                topic = obj.getString("topic"),
                contact = contact,
                scheduledTime = obj.getLong("scheduled_time")
            )
        }
    }
}