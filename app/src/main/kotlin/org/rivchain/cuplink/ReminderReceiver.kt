package org.rivchain.cuplink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.rivchain.cuplink.CallService.Companion.SERVICE_CONTACT_KEY
import org.rivchain.cuplink.model.Contact

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val topic = intent?.getStringExtra("topic")
        val contact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getSerializableExtra(SERVICE_CONTACT_KEY, Contact::class.java)
        } else {
            intent?.getSerializableExtra(SERVICE_CONTACT_KEY)
        } as Contact

        // Show a notification or start an activity to make the call
        // This is a placeholder for notification or call logic
    }
}