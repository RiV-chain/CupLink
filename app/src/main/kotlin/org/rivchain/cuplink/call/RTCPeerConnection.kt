package org.rivchain.cuplink.call

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.os.Build
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.libsodium.jni.Sodium
import org.rivchain.cuplink.CallActivity
import org.rivchain.cuplink.CallService
import org.rivchain.cuplink.Crypto
import org.rivchain.cuplink.DatabaseCache
import org.rivchain.cuplink.MainService
import org.rivchain.cuplink.R
import org.rivchain.cuplink.message.ActionMessageDispatcher
import org.rivchain.cuplink.model.Contact
import org.rivchain.cuplink.util.Log
import org.rivchain.cuplink.util.NetworkUtils
import org.rivchain.cuplink.util.Utils
import java.io.IOException
import java.lang.Integer.max
import java.lang.Integer.min
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

abstract class RTCPeerConnection(
    var service: MainService,
    protected var contact: Contact,
    protected var commSocket: Socket?
) {
    protected var state = CallState.WAITING
    var callActivity: RTCCall.CallContext? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var mediaPlayer: MediaPlayer? = null
    var callStatusHandler: CallStatusHandler? = null

    // BroadcastReceiver to listen for screen lock/unlock events
    open val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Disable the video track when the screen is off
                    screenLocked()
                }
                Intent.ACTION_USER_PRESENT -> {
                    // Optionally enable the video track when the screen is on
                    screenUnlocked()
                }
            }
        }
    }

    open fun screenLocked() {

    }

    open fun screenUnlocked() {

    }

    fun playTone(state: CallState) {
        stopTone() // Stop any currently playing tone

        val toneResId: Int = when (state) {
            CallState.WAITING -> R.raw.waiting
            CallState.CONNECTING -> R.raw.waiting
            CallState.RINGING -> R.raw.ringing
            CallState.DISMISSED -> R.raw.stop
            CallState.ENDED -> R.raw.ended
            CallState.CONNECTED -> R.raw.connected
            CallState.ON_HOLD -> R.raw.waiting
            CallState.RESUME -> R.raw.connected
            CallState.ERROR_COMMUNICATION -> R.raw.stop
            CallState.ERROR_AUTHENTICATION -> R.raw.stop
            CallState.ERROR_DECRYPTION -> R.raw.stop
            CallState.ERROR_CONNECT_PORT -> R.raw.stop
            CallState.ERROR_UNKNOWN_HOST -> R.raw.stop
            CallState.ERROR_NO_CONNECTION -> R.raw.stop
            CallState.ERROR_NO_ADDRESSES -> R.raw.stop
            CallState.ERROR_NO_NETWORK -> R.raw.stop
        }

        mediaPlayer = MediaPlayer.create(service, toneResId)
        mediaPlayer?.isLooping = state == CallState.RINGING
        mediaPlayer?.setVolume(0.5f,0.5f)
        mediaPlayer?.start()
    }

    private fun stopTone() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    abstract fun reportStateChange(state: CallState)
    abstract fun handleAnswer(remoteDesc: String)

    protected fun cleanupRTCPeerConnection() {
        execute {
            Log.d(this, "cleanup() executor start")
            try {
                Log.d(this, "cleanup() close socket")
                commSocket?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            Log.d(this, "cleanup() executor end")
            stopTone()
        }

        // wait for tasks to finish
        executor.shutdown()
        executor.awaitTermination(4L, TimeUnit.SECONDS)
    }

    fun setCallContext(activity: RTCCall.CallContext?) {
        this.callActivity = activity
    }

    protected fun createOutgoingCall(contact: Contact, offer: String) {
        Log.d(this, "createOutgoingCall()")
        Thread {
            try {
                createOutgoingCallInternal(contact, offer)
            } catch (e: Exception) {
                e.printStackTrace()
                reportStateChange(CallState.ERROR_COMMUNICATION)
            }
        }.start()

        // Register the BroadcastReceiver to listen for screen on/off events
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        service.registerReceiver(screenStateReceiver, filter)
    }

    private fun createOutgoingCallInternal(contact: Contact, offer: String) {
        Log.d(this, "createOutgoingCallInternal()")

        val socket = createCommSocket(contact) ?: return

        callActivity?.onRemoteAddressChange(socket.remoteSocketAddress as InetSocketAddress, true)
        commSocket = socket

        reportStateChange(CallState.CONNECTING)

        val dispatcher = ActionMessageDispatcher(contact, this, socket)

        callStatusHandler = CallStatusHandler(service, dispatcher)
        MainScope().launch {
            callStatusHandler!!.startCallStatusListening()
        }

        dispatcher.call(offer)

        dispatcher.receiveOfferResponse()

        dispatcher.storeLastAddress()

        dispatcher.confirmConnected()

        Log.d(this, "createOutgoingCallInternal() close socket")
        dispatcher.stop()
        closeSocket(socket)

        // detect broken initial connection
        if (isCallInit(state) && socket.isClosed) {
            Log.e(this, "createOutgoingCallInternal() call (state=$state) is not connected and socket is closed")
            reportStateChange(CallState.ERROR_COMMUNICATION)
        }

        Log.d(this, "createOutgoingCallInternal() finished")
    }

    // Continue listening for socket message.
    // Must run on separate thread!
    fun continueOnIncomingSocket() {
        Log.d(this, "continueOnIncomingSocket()")
        Utils.checkIsNotOnMainThread()

        val socket = commSocket ?: throw IllegalStateException("commSocket not expected to be null")

        val dispatcher = ActionMessageDispatcher(contact, this, socket)

        if (!socket.isClosed) {
            Log.d(this, "continueOnIncomingSocket() expected dismissed/keep_alive")

            callStatusHandler = CallStatusHandler(service, dispatcher)
            MainScope().launch {
                callStatusHandler!!.startCallStatusListening()
            }

            dispatcher.receiveIncoming()

            Log.d(this, "continueOnIncomingSocket() wait for writeExecutor")
        }
        dispatcher.stop()

        // detect broken initial connection
        if (isCallInit(state) && socket.isClosed) {
            Log.e(this, "continueOnIncomingSocket() call (state=$state) is not connected and socket is closed")
            reportStateChange(CallState.ERROR_COMMUNICATION)
        }
        Log.d(this, "continueOnIncomingSocket() finished")
    }

    fun declineOwnCall(){
        // decline own call. call session has been started yet.
        if(!CallActivity.isCallInProgress) {
            Log.d(this, "decline() send broadcast to receiver")
            PendingIntent.getBroadcast(
                this.service,
                0,
                Intent().apply {
                    setAction(CallService.DECLINE_CALL_ACTION)
                },
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    PendingIntent.FLAG_IMMUTABLE
                else
                    0
            ).send()
        }
    }

    protected fun execute(r: Runnable) {
        try {
            executor.execute(r)
        } catch (e: RejectedExecutionException) {
            e.printStackTrace()
            // can happen when the executor has shut down
            Log.w(this, "execute() catched RejectedExecutionException")
        } catch (e: Exception) {
            e.printStackTrace()
            Log.w(this, "execute() catched $e")
            reportStateChange(CallState.ERROR_COMMUNICATION)
        }
    }

    // send over initial socket when the call is not yet established
    private fun declineInternal() {
        Log.d(this, "declineInternal()")
        // send decline over initial socket
        val socket = commSocket
        if (socket != null && !socket.isClosed) {
            val pw = PacketWriter(socket)
            val settings = DatabaseCache.database.settings
            val ownPublicKey = settings.publicKey
            val ownSecretKey = settings.secretKey

            val encrypted = Crypto.encryptMessage(
                "{\"action\":\"dismissed\"}",
                contact.publicKey,
                ownPublicKey,
                ownSecretKey
            )

            if (encrypted == null) {
                reportStateChange(CallState.ERROR_COMMUNICATION)
            } else {
                try {
                    Log.d(this, "declineInternal() write dismissed message to socket")
                    pw.writeMessage(encrypted)
                } catch (e: IOException) {
                    e.printStackTrace()
                }
                reportStateChange(CallState.DISMISSED)
            }
        } else {
            reportStateChange(CallState.DISMISSED)
        }
    }

    fun decline() {
        Log.d(this, "decline()")
        Utils.checkIsOnMainThread()

        execute {
            Log.d(this, "decline() executor start")
            declineInternal()
            Log.d(this, "decline() executor end")
        }
    }

    private fun createCommSocket(contact: Contact): Socket? {
        Log.d(this, "createCommSocket()")

        Utils.checkIsNotOnMainThread()

        val settings = DatabaseCache.database.settings
        val useNeighborTable = settings.useNeighborTable
        val connectTimeout = settings.connectTimeout
        val connectRetries = settings.connectRetries

        var unknownHostException = false
        var connectException = false
        var socketTimeoutException = false
        var exception = false

        val allGeneratedAddresses = NetworkUtils.getAllSocketAddresses(contact, useNeighborTable)
        Log.d(this, "createCommSocket() contact.addresses: ${contact.addresses}, allGeneratedAddresses: $allGeneratedAddresses")

        for (iteration in 0..max(0, min(connectRetries, 4))) {
            Log.d(this, "createCommSocket() loop number $iteration")

            for (address in allGeneratedAddresses) {
                callActivity?.onRemoteAddressChange(address, false)
                Log.d(this, "try address: $address")

                val socket = Socket()

                try {
                    socket.connect(address, connectTimeout)
                    reportStateChange(CallState.CONNECTING)
                    return socket
                } catch (e: SocketTimeoutException) {
                    // no connection
                    Log.d(this, "createCommSocket() socket has thrown SocketTimeoutException")
                    socketTimeoutException = true
                } catch (e: ConnectException) {
                    // device is online, but does not listen on the given port
                    Log.d(this, "createCommSocket() socket has thrown ConnectException")
                    connectException = true
                } catch (e: UnknownHostException) {
                    // hostname did not resolve
                    Log.d(this, "createCommSocket() socket has thrown UnknownHostException")
                    unknownHostException = true
                } catch (e: Exception) {
                    Log.d(this, "createCommSocket() socket has thrown Exception")
                    exception = true
                }

                closeSocket(socket)
            }
        }

        if (connectException) {
            reportStateChange(CallState.ERROR_CONNECT_PORT)
        } else if (unknownHostException) {
            reportStateChange(CallState.ERROR_UNKNOWN_HOST)
        } else if (exception) {
            reportStateChange(CallState.ERROR_COMMUNICATION)
        } else if (socketTimeoutException) {
            reportStateChange(CallState.ERROR_NO_CONNECTION)
        } else if (contact.addresses.isEmpty()) {
            reportStateChange(CallState.ERROR_NO_ADDRESSES)
        } else {
            // No addresses were generated.
            // This happens if MAC addresses were
            // used and no network is available.
            reportStateChange(CallState.ERROR_NO_NETWORK)
        }

        return null
    }

    fun createMessageSocket(contact: Contact): Socket? {
        Log.d(this, "createCommSocket()")

        Utils.checkIsNotOnMainThread()

        val settings = DatabaseCache.database.settings
        val useNeighborTable = settings.useNeighborTable
        val connectTimeout = settings.connectTimeout
        val connectRetries = settings.connectRetries

        var unknownHostException = false
        var connectException = false
        var socketTimeoutException = false
        var exception = false

        val allGeneratedAddresses = NetworkUtils.getAllSocketAddresses(contact, useNeighborTable)
        Log.d(this, "createCommSocket() contact.addresses: ${contact.addresses}, allGeneratedAddresses: $allGeneratedAddresses")

        for (iteration in 0..max(0, min(connectRetries, 4))) {
            Log.d(this, "createCommSocket() loop number $iteration")

            for (address in allGeneratedAddresses) {
                callActivity?.onRemoteAddressChange(address, false)
                Log.d(this, "try address: $address")
                val socket = Socket()
                try {
                    socket.connect(address, connectTimeout)
                    return socket
                } catch (e: SocketTimeoutException) {
                    // no connection
                    Log.d(this, "createCommSocket() socket has thrown SocketTimeoutException")
                    socketTimeoutException = true
                } catch (e: ConnectException) {
                    // device is online, but does not listen on the given port
                    Log.d(this, "createCommSocket() socket has thrown ConnectException")
                    connectException = true
                } catch (e: UnknownHostException) {
                    // hostname did not resolve
                    Log.d(this, "createCommSocket() socket has thrown UnknownHostException")
                    unknownHostException = true
                } catch (e: Exception) {
                    Log.d(this, "createCommSocket() socket has thrown Exception")
                    exception = true
                }

                closeSocket(socket)
            }
        }

        if (connectException) {
            reportStateChange(CallState.ERROR_CONNECT_PORT)
        } else if (unknownHostException) {
            reportStateChange(CallState.ERROR_UNKNOWN_HOST)
        } else if (exception) {
            reportStateChange(CallState.ERROR_COMMUNICATION)
        } else if (socketTimeoutException) {
            reportStateChange(CallState.ERROR_NO_CONNECTION)
        } else if (contact.addresses.isEmpty()) {
            reportStateChange(CallState.ERROR_NO_ADDRESSES)
        } else {
            // No addresses were generated.
            // This happens if MAC addresses were
            // used and no network is available.
            reportStateChange(CallState.ERROR_NO_NETWORK)
        }

        return null
    }

    enum class CallState {
        WAITING,
        CONNECTING,
        RINGING,
        CONNECTED,
        ON_HOLD,
        RESUME,
        DISMISSED,
        ENDED,
        ERROR_AUTHENTICATION,
        ERROR_DECRYPTION,
        ERROR_CONNECT_PORT,
        ERROR_UNKNOWN_HOST,
        ERROR_COMMUNICATION,
        ERROR_NO_CONNECTION,
        ERROR_NO_ADDRESSES,
        ERROR_NO_NETWORK
    }

    // Not an error but also not established (CONNECTED)
    private fun isCallInit(state: CallState): Boolean {
        return when (state) {
            CallState.WAITING,
            CallState.CONNECTING,
            CallState.RINGING -> true
            else -> false
        }
    }

    companion object {
        const val SOCKET_TIMEOUT_MS = 25000L

        // used to pass incoming RTCCall to CallActiviy
        var incomingRTCCall: RTCCall? = null

        fun closeSocket(socket: Socket?) {
            try {
                socket?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun createIncomingCall(service: MainService, socket: Socket) {
            Thread {
                try {
                    createIncomingCallInternal(service, socket)
                } catch (e: Exception) {
                    e.printStackTrace()
                    //decline()
                }
            }.start()
        }

        private fun createIncomingCallInternal(service: MainService, socket: Socket) {
            Log.d(this, "createIncomingCallInternal()")

            val otherPublicKey = ByteArray(Sodium.crypto_sign_publickeybytes())
            val settings = DatabaseCache.database.settings
            val blockUnknown = settings.blockUnknown
            val ownSecretKey = settings.secretKey
            val ownPublicKey = settings.publicKey

            val decline = {
                Log.d(this, "createIncomingCallInternal() declining...")

                try {
                    val encrypted = Crypto.encryptMessage(
                        "{\"action\":\"dismissed\"}",
                        otherPublicKey,
                        ownPublicKey,
                        ownSecretKey
                    )

                    if (encrypted != null) {
                        val pw = PacketWriter(socket)
                        pw.writeMessage(encrypted)
                    }

                    socket.close()
                } catch (e: Exception) {
                    closeSocket(socket)
                }
            }

            val remoteAddress = socket.remoteSocketAddress as InetSocketAddress
            val pw = PacketWriter(socket)
            val pr = PacketReader(socket)

            Log.d(this, "createIncomingCallInternal() incoming peerConnection from $remoteAddress")

            val request = pr.readMessage()
            if (request == null) {
                Log.d(this, "createIncomingCallInternal() connection closed")
                socket.close()
                return
            }

            //Log.d(this, "request: ${request.toHex()}")

            val decrypted = Crypto.decryptMessage(request, otherPublicKey, ownPublicKey, ownSecretKey)
            if (decrypted == null) {
                Log.d(this, "createIncomingCallInternal() decryption failed")
                // cause: the caller might use the wrong key
                socket.close()
                return
            }

            Log.d(this, "createIncomingCallInternal() request: $decrypted")

            var contact = DatabaseCache.database.contacts.getContactByPublicKey(otherPublicKey)
            if (contact == null && blockUnknown) {
                Log.d(this, "createIncomingCallInternal() block unknown contact => decline")
                decline()
                return
            }

            if (contact != null && contact.blocked) {
                Log.d(this, "createIncomingCallInternal() blocked contact => decline")
                decline()
                return
            }

            if (contact == null) {
                // unknown caller
                contact = Contact("", otherPublicKey.clone(), listOf(remoteAddress.address.hostAddress!!))
            }

            // suspicious change of identity in during peerConnection...
            if (!contact.publicKey.contentEquals(otherPublicKey)) {
                Log.d(this, "createIncomingCallInternal() suspicious change of key")
                decline()
                return
            }

            // remember latest working address and set state
            contact.lastWorkingAddress = InetSocketAddress(remoteAddress.address, MainService.serverPort)

            val obj = JSONObject(decrypted)
            val action = obj.optString("action", "")
            Log.d(this, "createIncomingCallInternal() action: $action")
            when (action) {
                "call" -> {
                    contact.state = Contact.State.CONTACT_ONLINE
                    //MainService.refreshContacts(service)

                    if (CallActivity.isCallInProgress) {
                        Log.d(this, "createIncomingCallInternal() call in progress => decline")
                        decline() // TODO: send busy
                        return
                    }

                    Log.d(this, "createIncomingCallInternal() got WebRTC offer")

                    // someone calls us
                    val offer = obj.optString("offer")
                    if (offer.isEmpty()) {
                        Log.d(this, "createIncomingCallInternal() missing offer")
                        decline()
                        return
                    }

                    // respond that we accept the call (our phone is ringing)
                    val encrypted = Crypto.encryptMessage(
                        "{\"action\":\"ringing\"}",
                        contact.publicKey,
                        ownPublicKey,
                        ownSecretKey
                    )

                    if (encrypted == null) {
                        Log.d(this, "createIncomingCallInternal() encryption failed")
                        decline()
                        return
                    }

                    pw.writeMessage(encrypted)

                    incomingRTCCall?.cleanup() // just in case
                    incomingRTCCall = RTCCall(service, contact, socket, offer)
                    try {
                        // CallActivity accepts calls by default
                        // CallActivity is being opened from a foreground notification below
                        if (DatabaseCache.database.settings.autoAcceptCalls) {
                                Log.d(
                                    this,
                                    "createIncomingCallInternal() start incoming call from Service"
                                )
                                val intent = Intent(service, CallActivity::class.java)
                                intent.action = "ACTION_INCOMING_CALL"
                                intent.putExtra("EXTRA_CONTACT", contact)
                                intent.flags =
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                service.startActivity(intent)
                        } else {
                            val intent = Intent(service, CallService::class.java)
                                .putExtra(CallService.SERVICE_CONTACT_KEY,
                                    contact)
                            service.startService(intent)
                        }
                    } catch (e: Exception) {
                        incomingRTCCall?.cleanup()
                        incomingRTCCall = null
                        e.printStackTrace()
                    }
                }
                "ping" -> {
                    Log.d(this, "createIncomingCallInternal() ping...")
                    // someone wants to know if we are online
                    contact.state = Contact.State.CONTACT_ONLINE
                    //MainService.refreshContacts(service)

                    val encrypted = Crypto.encryptMessage(
                        "{\"action\":\"pong\"}",
                        contact.publicKey,
                        ownPublicKey,
                        ownSecretKey
                    )

                    if (encrypted == null) {
                        Log.d(this, "createIncomingCallInternal() encryption failed")
                        decline()
                        return
                    }

                    pw.writeMessage(encrypted)
                }
                "status_change" -> {
                    val status = obj.getString("status")
                    if (status == "offline") {
                        contact.state = Contact.State.CONTACT_OFFLINE
                        //MainService.refreshContacts(service)
                    } else {
                        Log.d(this, "createIncomingCallInternal() received unknown status_change: $status")
                    }
                }
                "on_hold" -> {
                    if (!CallActivity.isCallInProgress) {
                        Log.d(this, "call not started: invalid status => decline")
                        decline()
                        return
                    }
                    Log.d(this, "createOutgoingCallInternal() on_hold")
                    incomingRTCCall?.reportStateChange(CallState.ON_HOLD)
                }
                "resume" -> {
                    if (!CallActivity.isCallInProgress) {
                        Log.d(this, "call not started: invalid status => decline")
                        decline()
                        return
                    }
                    Log.d(this, "createOutgoingCallInternal() resume")
                    incomingRTCCall?.reportStateChange(CallState.RESUME)
                }
            }
        }
    }
}
