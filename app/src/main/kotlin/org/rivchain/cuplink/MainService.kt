package org.rivchain.cuplink

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.OsConstants

import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import mobile.Mesh
import org.json.JSONArray
import org.rivchain.cuplink.NotificationUtils.showToastMessageMainLooper
import org.rivchain.cuplink.call.PacketWriter
import org.rivchain.cuplink.call.RTCPeerConnection
import org.rivchain.cuplink.model.Contact
import org.rivchain.cuplink.rivmesh.AppStateReceiver
import org.rivchain.cuplink.rivmesh.NetworkStateCallback
import org.rivchain.cuplink.rivmesh.STATE_CONNECTED
import org.rivchain.cuplink.rivmesh.STATE_DISABLED
import org.rivchain.cuplink.rivmesh.STATE_ENABLED
import org.rivchain.cuplink.rivmesh.State
import org.rivchain.cuplink.util.Log
import org.rivchain.cuplink.util.NetworkUtils
import org.rivchain.cuplink.util.ServiceUtil
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

const val TAG = "VPN service"
const val SERVICE_NOTIFICATION_ID = 1000
class MainService : VpnService() {
    /**
     * App variables
     */
    private val binder = MainBinder()
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    /**
     * VPN variables
     */
    private var mesh = Mesh()
    private var started = AtomicBoolean()

    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    private var updateThread: Thread? = null

    private var parcel: ParcelFileDescriptor? = null
    private var readerStream: FileInputStream? = null
    private var writerStream: FileOutputStream? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var KEY_ENABLE_CHROME_FIX = "enable_chrome_fix"
    private var KEY_DNS_SERVERS = "dns_servers"

    override fun onCreate() {
        super.onCreate()
        val callback = NetworkStateCallback(this)
        callback.register()
    }

    private fun createCommSocket(contact: Contact): Socket? {
        val settings = DatabaseCache.database.settings
        val useNeighborTable = settings.useNeighborTable
        val connectTimeout = settings.connectTimeout

        for (address in NetworkUtils.getAllSocketAddresses(contact, useNeighborTable)) {
            Log.d(this, "try address: $address")
            val socket = Socket()

            try {
                socket.connect(address, connectTimeout)
                return socket
            } catch (e: SocketTimeoutException) {
                Log.d(this, "createCommSocket() SocketTimeoutException address=$address")
            } catch (e: ConnectException) {
                // device is online, but does not listen on the given port
                Log.d(this, "createCommSocket() ConnectException address=$address")
            } catch (e: UnknownHostException) {
                // hostname did not resolve
                Log.d(this, "createCommSocket() UnknownHostException address=$address")
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                socket.close()
            } catch (e: Exception) {
                // ignore
            }
        }
        return null
    }

    override fun onDestroy() {
        Log.d(this, "onDestroy()")

        stopServer()
        stopPacketsStream()

        // say goodbye
        val database = DatabaseCache.database
        if (serverSocket != null && serverSocket!!.isBound && !serverSocket!!.isClosed) {
            try {
                val ownPublicKey = database.settings.publicKey
                val ownSecretKey = database.settings.secretKey
                val message = "{\"action\": \"status_change\", \"status\", \"offline\"}"
                for (contact in database.contacts.contactList) {
                    if (contact.state != Contact.State.CONTACT_ONLINE) {
                        continue
                    }
                    val encrypted = Crypto.encryptMessage(message, contact.publicKey, ownPublicKey, ownSecretKey) ?: continue
                    var socket: Socket? = null
                    try {
                        socket = createCommSocket(contact)
                        if (socket == null) {
                            continue
                        }
                        val pw = PacketWriter(socket)
                        pw.writeMessage(encrypted)
                        socket.close()
                    } catch (e: Exception) {
                        if (socket != null) {
                            try {
                                socket.close()
                            } catch (ee: Exception) {
                                // ignore
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }

        database.destroy()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(this, "onStartCommand()")

        if (intent == null || intent.action == null) {
            Log.d(this, "onStartCommand() Received invalid intent")
            return START_NOT_STICKY
        } else if (intent.action == ACTION_START) {
            Log.d(this, "onStartCommand() Received Start Foreground Intent")
            val notification = createServiceNotification(this, State.Starting)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                startForeground(SERVICE_NOTIFICATION_ID, notification)
            } else {
                startForeground(SERVICE_NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            }
        }

        return when (intent.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stopping...")
                Log.d(this, "onStartCommand() Received Stop Foreground Intent")
                stopPacketsStream()
                shutdown(); START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                Log.d(TAG, "Toggling...")
                if (started.get()) {
                    stopPacketsStream(); START_NOT_STICKY
                } else {
                    startPacketsStream(); START_STICKY
                }
            }
            else -> {
                val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
                val enabled = preferences.getBoolean(PREF_KEY_ENABLED, true)
                if (!enabled) {
                    Log.d(TAG, "Service is disabled")
                    return START_NOT_STICKY
                }
                Log.d(TAG, "Connecting...")
                if (started.get()) {
                    connect()
                    acquireMulticastLock()
                } else {
                    startPacketsStream()
                }
                START_STICKY
            }
        }

    }

    private fun acquireMulticastLock(){
        // Acquire multicast lock
        val wifi = ServiceUtil.getWifiManager(this)
        multicastLock = wifi.createMulticastLock("Mesh").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    private fun startPacketsStream() {
        // !this::database.isInitialized means db is encrypted
        // we will re-try to load it after the next db password prompt
        if (!started.compareAndSet(false, true)) {
            return
        }

        // handle incoming connections
        startServer()

        acquireMulticastLock()

        val notification = createServiceNotification(this, State.Enabled)
        startForeground(SERVICE_NOTIFICATION_ID, notification)

        Log.d(TAG, "getting Mesh configuration")
        val jsonConfig = DatabaseCache.database.mesh.getJSONByteArray()
        mesh.startJSON(jsonConfig)
        val address = mesh.addressString
        val builder = Builder()
            .addAddress(address, 7)
            // We do this to trick the DNS-resolver into thinking that we have "regular" IPv6,
            // and therefore we need to resolve AAAA DNS-records.
            // See: https://android.googlesource.com/platform/bionic/+/refs/heads/master/libc/dns/net/getaddrinfo.c#1935
            // and: https://android.googlesource.com/platform/bionic/+/refs/heads/master/libc/dns/net/getaddrinfo.c#365
            // If we don't do this the DNS-resolver just doesn't do DNS-requests with record type AAAA,
            // and we can't use DNS with RiV-mesh addresses.
            .addRoute("2000::", 128)
            .allowFamily(OsConstants.AF_INET)
            .allowBypass()
            .setBlocking(true)
            .setMtu(mesh.mtu.toInt())
            .setSession("Mesh")
        // On Android API 29+ apps can opt-in/out to using metered networks.
        // If we don't set metered status of VPN it is considered as metered.
        // If we set it to false, then it will inherit this status from underlying network.
        // See: https://developer.android.com/reference/android/net/VpnService.Builder#setMetered(boolean)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
        val serverString = preferences.getString(KEY_DNS_SERVERS, "")
        if (serverString!!.isNotEmpty()) {
            val servers = serverString.split(",")
            if (servers.isNotEmpty()) {
                servers.forEach {
                    Log.i(TAG, "Using DNS server $it")
                    builder.addDnsServer(it)
                }
            }
        }
        if (preferences.getBoolean(KEY_ENABLE_CHROME_FIX, true)) {
            builder.addRoute("2001:4860:4860::8888", 128)
        }

        parcel = builder.establish()
        val parcel = parcel
        if (parcel == null || !parcel.fileDescriptor.valid()) {
            stopPacketsStream()
            return
        }

        readerStream = FileInputStream(parcel.fileDescriptor)
        writerStream = FileOutputStream(parcel.fileDescriptor)

        readerThread = thread {
            reader()
        }
        writerThread = thread {
            writer()
        }
        updateThread = thread {
            updater()
        }

        val intent = Intent(AppStateReceiver.APP_STATE_INTENT)
        intent.putExtra("state", STATE_ENABLED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun stopPacketsStream() {
        if (!started.compareAndSet(true, false)) {
            return
        }

        mesh.stop()

        readerStream?.let {
            it.close()
            readerStream = null
        }
        writerStream?.let {
            it.close()
            writerStream = null
        }
        parcel?.let {
            it.close()
            parcel = null
        }

        readerThread?.let {
            it.interrupt()
            readerThread = null
        }
        writerThread?.let {
            it.interrupt()
            writerThread = null
        }
        updateThread?.let {
            it.interrupt()
            updateThread = null
        }

        var intent = Intent(STATE_INTENT)
        intent.putExtra("type", "state")
        intent.putExtra("started", false)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        intent = Intent(AppStateReceiver.APP_STATE_INTENT)
        intent.putExtra("state", STATE_DISABLED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        stopForeground(true)
        stopSelf()
        multicastLock?.release()
    }

    private fun connect() {
        if (!started.get()) {
            return
        }
        mesh.retryPeersNow()
    }

    private fun updater() {
        var lastStateUpdate = System.currentTimeMillis()
        updates@ while (started.get()) {
            if ((application as  MainApplication).needUiUpdates()) {
                val intent = Intent(STATE_INTENT)
                intent.putExtra("type", "state")
                intent.putExtra("started", true)
                intent.putExtra("ip", mesh.addressString)
                intent.putExtra("subnet", mesh.subnetString)
                intent.putExtra("coords", mesh.coordsString)
                intent.putExtra("peers", mesh.peersJSON)
                intent.putExtra("dht", mesh.dhtjson)
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            }
            val curTime = System.currentTimeMillis()
            if (lastStateUpdate + 10000 < curTime) {
                val intent = Intent(AppStateReceiver.APP_STATE_INTENT)
                var state = STATE_ENABLED
                val dht = mesh.dhtjson
                if (dht != null && dht != "null") {
                    val dhtState = JSONArray(dht)
                    val count = dhtState.length()
                    if (count > 1)
                        state = STATE_CONNECTED
                }
                intent.putExtra("state", state)
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                lastStateUpdate = curTime
            }

            if (Thread.currentThread().isInterrupted) {
                break@updates
            }
            if (sleep()) return
        }
    }

    private fun sleep(): Boolean {
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            return true
        }
        return false
    }

    private fun writer() {
        val buf = ByteArray(65535)
        writes@ while (started.get()) {
            val writerStream = writerStream
            val writerThread = writerThread
            if (writerThread == null || writerStream == null) {
                Log.i(TAG, "Write thread or stream is null")
                break@writes
            }
            if (Thread.currentThread().isInterrupted || !writerStream.fd.valid()) {
                Log.i(TAG, "Write thread interrupted or file descriptor is invalid")
                break@writes
            }
            try {
                val len = mesh.recvBuffer(buf)
                if (len > 0) {
                    writerStream.write(buf, 0, len.toInt())
                }
            } catch (e: Exception) {
                Log.i(TAG, "Error in write: $e")
                if (e.toString().contains("ENOBUFS")) {
                    //TODO Check this by some error code
                    //More info about this: https://github.com/AdguardTeam/AdguardForAndroid/issues/724
                    continue
                }
                break@writes
            }
        }
        writerStream?.let {
            it.close()
            writerStream = null
        }
    }

    private fun reader() {
        val b = ByteArray(65535)
        reads@ while (started.get()) {
            val readerStream = readerStream
            val readerThread = readerThread
            if (readerThread == null || readerStream == null) {
                Log.i(TAG, "Read thread or stream is null")
                break@reads
            }
            if (Thread.currentThread().isInterrupted ||!readerStream.fd.valid()) {
                Log.i(TAG, "Read thread interrupted or file descriptor is invalid")
                break@reads
            }
            try {
                val n = readerStream.read(b)
                mesh.sendBuffer(b, n.toLong())
            } catch (e: Exception) {
                Log.i(TAG, "Error in sendBuffer: $e")
                break@reads
            }
        }
        readerStream?.let {
            it.close()
            readerStream = null
        }
    }

    private fun startServer() {
        try {
            if(serverThread == null || serverThread!!.isInterrupted) {
                serverThread = Thread {
                    try {
                        serverSocket = ServerSocket(serverPort)
                        while (true) {
                            Log.d(TAG, "Server listening on port $serverPort")
                            val clientSocket: Socket = serverSocket!!.accept()
                            // Handle client connection
                            Log.d(TAG, "Client connected: ${clientSocket.inetAddress}")
                            Log.d(this, "run() new incoming connection")

                            RTCPeerConnection.createIncomingCall(this, clientSocket)
                        }
                    } catch (e: IOException) {
                        e.printStackTrace()
                        showToastMessageMainLooper(this, e.message)
                    }
                }
                serverThread!!.start()
            }
        } catch (e: IOException) {
            Log.e(this, "run() e=$e")
            e.printStackTrace()
            showToastMessageMainLooper(this, e.message)
            shutdown()
        }
    }

    private fun stopServer() {
        serverThread?.interrupt()
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    /*
    * Allows communication between MainService and other objects
    */
    inner class MainBinder : Binder() {
        fun getService(): MainService {
            return this@MainService
        }
    }

    fun shutdown() {
        Log.i(this, "shutdown()")
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    companion object {

        /**
         * VPN variables
         */
        const val STATE_INTENT = "org.rivchain.cuplink.rivmesh.MainService.STATE_MESSAGE"
        const val ACTION_START = "org.rivchain.cuplink.rivmesh.MainService.START"
        const val ACTION_STOP = "org.rivchain.cuplink.rivmesh.MainService.STOP"
        const val ACTION_TOGGLE = "org.rivchain.cuplink.rivmesh.MainService.TOGGLE"

        const val serverPort = 10001
        private const val NOTIFICATION_ID = 42

        fun startPacketsStream(ctx: Context) {
            val startIntent = Intent(ctx, MainService::class.java)
            startIntent.action = ACTION_START
            ContextCompat.startForegroundService(ctx, startIntent)
        }

        fun stopPacketsStream(ctx: Context) {
            val stopIntent = Intent(ctx, MainService::class.java)
            stopIntent.action = ACTION_STOP
            ctx.startService(stopIntent)
        }
    }
}
