package org.rivchain.cuplink.rivmesh

import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.CountDownTimer
import android.os.IBinder
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rivchain.cuplink.MainService
import org.rivchain.cuplink.R
import org.rivchain.cuplink.rivmesh.util.Utils
import org.rivchain.cuplink.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class PublicPeerActivity: AppCompatActivity(), ServiceConnection {

    private var binder: MainService.MainBinder? = null
    private lateinit var publicPeerAgreementDialog: AlertDialog
    private lateinit var agreeCheckbox: MaterialCheckBox
    private lateinit var okButton: MaterialButton
    private lateinit var cancelButton: MaterialButton

    private fun checkPort(url: String, requestBody: String): Int {
        val urlObject = URL(url)
        val connection = urlObject.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.doOutput = true

        // Write the request body
        val outputStream = connection.outputStream
        val writer = OutputStreamWriter(outputStream, "UTF-8")
        writer.write(requestBody)
        writer.flush()
        writer.close()

        // Get the response code
        val responseCode = connection.responseCode

        // Close the connection
        connection.disconnect()

        return responseCode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_empty)

        bindService(Intent(this, MainService::class.java), this, 0)

        // Inflate the layout for the dialog
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_checkbox, null)

        // Create the AlertDialog
        publicPeerAgreementDialog = AlertDialog.Builder(this, R.style.PPTCDialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        agreeCheckbox = dialogView.findViewById(R.id.checkbox)
        okButton = dialogView.findViewById(R.id.OkButton)
        cancelButton = dialogView.findViewById(R.id.CancelButton)
        okButton.setOnClickListener {
            // Public peers have been already assigned on MainService start
            // Now we check the status of the public ip:port
            val listenArray = binder!!.getMesh().getListen()
            val port: Int
            if(listenArray.length() == 0){
                // Generate a random port and continue
                port = Utils.generateRandomPort()
            } else {
                val listen = binder!!.getMesh().getListen().get(0).toString()
                port = URI(listen).port
            }

            val url = "https://map.rivchain.org/rest/peer"
            val requestBody = "{\"port\":$port}"
            // Start countdown
            showCountdownDialog()
            // Perform network request in a coroutine
            CoroutineScope(Dispatchers.Main).launch {

                withContext(Dispatchers.IO) {
                    try {
                    val responseCode = checkPort(url, requestBody)
                    if (responseCode == HttpURLConnection.HTTP_CREATED) {
                        showToast("Your peer has been registered successfully!")
                        publicPeerAgreementDialog.dismiss()
                        finish()
                    } else {
                        showToast("Port $port is unreachable")
                    }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        showToast("An error occurred: ${e.message}")
                    }
                }
            }
        }
        cancelButton.setOnClickListener {
            publicPeerAgreementDialog.dismiss()
            finish()
        }

        agreeCheckbox.addOnCheckedStateChangedListener { materialCheckBox: MaterialCheckBox, i: Int ->
            okButton.isEnabled = materialCheckBox.isChecked
        }

        // Show the dialog
        publicPeerAgreementDialog.show()
    }

    private fun showCountdownDialog() {
        // Inflate the dialog layout
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_countdown, null)

        // Create the AlertDialog
        val alertDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        // Get the TextView from the dialog layout
        val countdownTextView: TextView = dialogView.findViewById(R.id.countdownTextView)

        // Start the countdown timer
        object : CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = millisUntilFinished / 1000
                countdownTextView.text = "$secondsLeft sec left"
            }

            override fun onFinish() {
                // Dismiss the dialog when the countdown finishes
                alertDialog.dismiss()
            }
        }.start()

        // Show the dialog
        alertDialog.show()
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this@PublicPeerActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onServiceConnected(name: ComponentName?, iBinder: IBinder?) {
        Log.d(this, "onServiceConnected")
        binder = iBinder as MainService.MainBinder
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        // nothing todo
    }
}