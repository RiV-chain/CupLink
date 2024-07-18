package org.rivchain.cuplink.rivmesh

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import org.rivchain.cuplink.MainService
import org.rivchain.cuplink.util.Log

class NetworkStateCallback(val context: Context) : ConnectivityManager.NetworkCallback() {

    override fun onAvailable(network: Network) {
        super.onAvailable(network)
        Log.d(this, "onAvailable")
        /*
        Thread {
            // The message often arrives before the connection is fully established
            Thread.sleep(1000)
            val intent = Intent(context, MainService::class.java)
            intent.action = MainService.ACTION_CONNECT
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
        }.start()
        */
    }

    override fun onLost(network: Network) {
        super.onLost(network)
        Log.d(this, "onLost")
    }

    fun register() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.registerNetworkCallback(request, this)
    }
}