package org.rivchain.cuplink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent


class StopCallServiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        context.stopService(Intent(context, CallService::class.java))
    }
    companion object {
        const val REQUEST_CODE: Int = 333
    }
}