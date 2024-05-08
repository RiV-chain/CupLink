package org.rivchain.cuplink

import android.app.Service
import android.content.Intent
import android.os.IBinder

class CallService: Service() {

    override fun onBind(intent: Intent?): IBinder? {
        return null;
    }
}