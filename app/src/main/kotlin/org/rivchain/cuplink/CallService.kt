package org.rivchain.cuplink

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class CallService: AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

    }

    override fun onInterrupt() {
    }
}