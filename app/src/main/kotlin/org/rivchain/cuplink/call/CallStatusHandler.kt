package org.rivchain.cuplink.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import org.rivchain.cuplink.R
import org.rivchain.cuplink.message.ActionMessageDispatcher

class CallStatusHandler(private val context: Context, private val dispatcher: ActionMessageDispatcher) {

    private var telephonyCallback: TelephonyCallback? = null
    private var callStatusListener: PhoneStateListener? = null
    private lateinit var telephonyManager: TelephonyManager
    private var onHold = false

    fun startCallStatusListening() {

        if(ActivityCompat.checkSelfPermission(this.context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {

            Toast.makeText(context, "Permission found for READ_PHONE_STATE", Toast.LENGTH_LONG)
                .show()

            telephonyManager =
                context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback =
                    object : TelephonyCallback(), TelephonyCallback.CallStateListener {

                        override fun onCallStateChanged(state: Int) {
                            processCallStateChanged(state)
                        }
                    }
                telephonyManager.registerTelephonyCallback(
                    context.mainExecutor,
                    telephonyCallback!!
                )

            } else {
                callStatusListener = object : PhoneStateListener() {

                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        super.onCallStateChanged(state, phoneNumber)
                        processCallStateChanged(state)
                    }
                }
                callStatusListener?.let {
                    telephonyManager.listen(it, PhoneStateListener.LISTEN_CALL_STATE)
                }
            }
        } else {
            Toast.makeText(context, "No permissions found for READ_PHONE_STATE", Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun processCallStateChanged(state: Int){
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                onHold = true
                Log.d("CallStatusHandler", "Incoming GSM call detected")
                Log.d("VoIP", "Muting VoIP call")
                val obj = JSONObject().apply {
                    put("action", "on_hold")
                }
                Thread {
                    dispatcher.sendMessage(obj)
                }.start()
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                onHold = true
                Log.d("CallStatusHandler", "GSM call in progress")
                val obj = JSONObject().apply {
                    put("action", "on_hold")
                }
                Thread {
                    dispatcher.sendMessage(obj)
                }.start()
                dispatcher.closeSocket()
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                Log.d("CallStatusHandler", "No active GSM call")
                if (onHold) {
                    onHold = false
                    val obj = JSONObject().apply {
                        put("action", "resume")
                    }
                    Thread {
                        dispatcher.sendMessage(obj)
                    }.start()
                    dispatcher.closeSocket()
                }
            }
        }
    }


    fun stopCallStatusListening() {
        callStatusListener?.let {
            telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
        }
        callStatusListener = null
        telephonyCallback?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyManager.unregisterTelephonyCallback(telephonyCallback!!)
            }
        }
        telephonyCallback = null
    }
}