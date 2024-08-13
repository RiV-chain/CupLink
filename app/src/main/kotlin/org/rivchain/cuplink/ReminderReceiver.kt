package org.rivchain.cuplink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.rivchain.cuplink.CallService.Companion.SERVICE_CONTACT_KEY
import org.rivchain.cuplink.model.Contact
import org.rivchain.cuplink.util.Log

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val topic = intent?.getStringExtra("topic")
        val contactPublicKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getSerializableExtra(SERVICE_CONTACT_KEY, ByteArray::class.java)
        } else {
            intent?.getSerializableExtra(SERVICE_CONTACT_KEY)
        } as ByteArray

        // Show a notification or start an activity to make the call
        // This is a placeholder for notification or call logic
        var contact = DatabaseCache.database.contacts.getContactByPublicKey(contactPublicKey)
        if (contact == null){
            contact = Contact("Unknown contact", ByteArray(0), emptyList(), false)
        }
        Log.i(this, "Call Notification received for contact: ${contact.name}")
    }
}