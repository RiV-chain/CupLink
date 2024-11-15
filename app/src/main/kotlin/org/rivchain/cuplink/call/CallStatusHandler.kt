package org.rivchain.cuplink.call

import android.content.Context
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONObject
import org.rivchain.cuplink.message.MessageDispatcher

class CallStatusHandler(private val context: Context, private val dispatcher: MessageDispatcher) {

    private var callStatusListener: PhoneStateListener? = null
    private lateinit var telephonyManager: TelephonyManager

    fun startCallStatusListening() {
        telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        callStatusListener = object : PhoneStateListener() {

            private var onHold = false

            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                super.onCallStateChanged(state, phoneNumber)
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        onHold = true
                        Log.d("CallStatusHandler", "Incoming GSM call detected")
                        Log.d("VoIP", "Muting VoIP call")
                        val obj = JSONObject().apply {
                            put("action", "on_hold")
                        }
                        dispatcher.sendMessage(obj)
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        onHold = true
                        Log.d("CallStatusHandler", "GSM call in progress")
                        val obj = JSONObject().apply {
                            put("action", "on_hold")
                        }
                        dispatcher.sendMessage(obj)
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        Log.d("CallStatusHandler", "No active GSM call")
                        if (onHold) {
                            onHold = false
                            val obj = JSONObject().apply {
                                put("action", "resume")
                            }
                            dispatcher.sendMessage(obj)
                        }
                    }
                }
            }
        }
        callStatusListener?.let {
            telephonyManager.listen(it, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    fun stopCallStatusListening() {
        callStatusListener?.let {
            telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
        }
        callStatusListener = null
    }
}