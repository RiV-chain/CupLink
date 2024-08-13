package org.rivchain.cuplink.model

import org.json.JSONException
import org.json.JSONObject
import org.libsodium.jni.Sodium
import org.rivchain.cuplink.util.Utils

data class Reminder(
    val topic: String,
    val scheduledTime: Long,
    val contactPublicKey: ByteArray // Assuming reminder is for a call
) {
    companion object {

        fun toJSON(reminder: Reminder): JSONObject {
            val obj = JSONObject()
            obj.put("topic", reminder.topic)
            obj.put("public_key", Utils.byteArrayToHexString(reminder.contactPublicKey))
            obj.put("scheduled_time", reminder.scheduledTime)
            return obj
        }

        fun fromJSON(obj: JSONObject): Reminder {
            val publicKey = Utils.hexStringToByteArray(obj.getString("public_key"))
            if ((publicKey == null) || (publicKey.size != Sodium.crypto_sign_publickeybytes())) {
                throw JSONException("Invalid Public Key")
            }
            return Reminder(
                topic = obj.getString("topic"),
                contactPublicKey = publicKey,
                scheduledTime = obj.getLong("scheduled_time")
            )
        }
    }
}