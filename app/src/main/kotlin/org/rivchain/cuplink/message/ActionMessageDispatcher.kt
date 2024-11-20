package org.rivchain.cuplink.message

import org.json.JSONObject
import org.libsodium.jni.Sodium
import org.rivchain.cuplink.Crypto
import org.rivchain.cuplink.DatabaseCache
import org.rivchain.cuplink.MainService
import org.rivchain.cuplink.call.PacketReader
import org.rivchain.cuplink.call.PacketWriter
import org.rivchain.cuplink.call.RTCPeerConnection
import org.rivchain.cuplink.call.RTCPeerConnection.CallState
import org.rivchain.cuplink.call.RTCPeerConnection.Companion.SOCKET_TIMEOUT_MS
import org.rivchain.cuplink.model.Contact
import org.rivchain.cuplink.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ActionMessageDispatcher(
    private val contact: Contact,
    private val peerConnection: RTCPeerConnection,
    private var socket: Socket,
) {

    private val pr: PacketReader = PacketReader(socket)
    private val otherPublicKey: ByteArray = ByteArray(Sodium.crypto_sign_publickeybytes())
    private val ownPublicKey: ByteArray = DatabaseCache.database.settings.publicKey
    private val ownSecretKey: ByteArray = DatabaseCache.database.settings.secretKey

    private val messageExecutor = Executors.newSingleThreadExecutor()
    private val socketLock = ReentrantLock()

    /**
     * Sends a generic message.
     * @param message The JSONObject to be sent.
     */
    fun sendMessage(message: JSONObject) {
        socketLock.withLock {
            if (!isSocketOpen()) {
                socket = peerConnection.createMessageSocket(contact)!!
            }

            try {
                val encryptedMessage = Crypto.encryptMessage(
                    message.toString(),
                    contact.publicKey,
                    ownPublicKey,
                    ownSecretKey
                ) ?: throw IllegalStateException("Encryption failed")

                val packetWriter = PacketWriter(socket)
                packetWriter.writeMessage(encryptedMessage)
                Log.d(this,  "sendMessage() - Message sent: ${message}")
            } catch (e: Exception) {
                Log.e(this,  "sendMessage() - Error sending message: $e")
                closeSocket()
            }
        }
    }

    fun closeSocket() {
        socketLock.withLock {
            try {
                if (!socket.isClosed) {
                    socket.close()
                    Log.d(this,  "closeSocket() - Socket closed successfully")
                } else {
                    // do nothing
                }
            } catch (e: Exception) {
                Log.e(this,  "closeSocket() - Error closing socket: $e")
            }
        }
    }

    private fun isSocketOpen(): Boolean {
        socketLock.withLock {
            return !socket.isClosed && socket.isConnected
        }
    }

    fun stop() {
        messageExecutor.shutdownNow()
        messageExecutor.awaitTermination(100, TimeUnit.MILLISECONDS)
        closeSocket()
    }

    fun call(offer: String){
        Log.d(this, "createOutgoingCallInternal() outgoing call: send call")
        val obj = JSONObject()
        obj.put("action", "call")
        obj.put("offer", offer) // WebRTC offer!
        sendMessage(obj)
    }

    fun answerCall(description: String){
        val remoteAddress = socket.remoteSocketAddress as InetSocketAddress
        val pw = PacketWriter(socket)
        val obj = JSONObject()
        obj.put("action", "connected")
        obj.put("answer", description)
        val encrypted = Crypto.encryptMessage(
            obj.toString(),
            contact.publicKey,
            ownPublicKey,
            ownSecretKey
        )
        if (encrypted != null) {
            Log.d(this, "onIceGatheringChange() send connected")
            pw.writeMessage(encrypted)
            peerConnection.callActivity?.onRemoteAddressChange(remoteAddress, true)
            // connected state will be reported by WebRTC onIceConnectionChange()
            //reportStateChange(CallState.CONNECTED)
        } else {
            Log.d(this, "onIceGatheringChange() encryption failed")
            peerConnection.reportStateChange(CallState.ERROR_COMMUNICATION)
        }
        sendMessage(obj)
    }

    fun receiveOfferResponse() {

        Log.d(this, "createOutgoingCallInternal() outgoing call: expect ringing")
        val response = pr.readMessage()
        if (response == null) {
            peerConnection.reportStateChange(CallState.ERROR_COMMUNICATION)
            return
        }

        val decrypted = Crypto.decryptMessage(
            response,
            otherPublicKey,
            ownPublicKey,
            ownSecretKey
        )

        if (decrypted == null) {
            peerConnection.reportStateChange(CallState.ERROR_DECRYPTION)
            return
        }

        if (!contact.publicKey.contentEquals(otherPublicKey)) {
            peerConnection.reportStateChange(CallState.ERROR_AUTHENTICATION)
            return
        }

        val obj = JSONObject(decrypted)
        val action = obj.optString("action")
        if (action == "ringing") {
            Log.d(this, "createOutgoingCallInternal() got ringing")
            peerConnection.reportStateChange(CallState.RINGING)
        } else if (action == "dismissed") {
            Log.d(this, "createOutgoingCallInternal() got dismissed")
            peerConnection.reportStateChange(CallState.DISMISSED)
            return
        } else {
            Log.d(this, "createOutgoingCallInternal() unexpected action: $action")
            peerConnection.reportStateChange(CallState.ERROR_COMMUNICATION)
            return
        }
    }

    fun storeLastAddress(){

        val remoteAddress = socket.remoteSocketAddress as InetSocketAddress

        Log.d(this, "createOutgoingCallInternal() outgoing call from remote address: $remoteAddress")
        // remember latest working address and set state
        val workingAddress = InetSocketAddress(remoteAddress.address, MainService.serverPort)
        val storedContact = DatabaseCache.database.contacts.getContactByPublicKey(contact.publicKey)
        if (storedContact != null) {
            storedContact.lastWorkingAddress = workingAddress
        } else {
            contact.lastWorkingAddress = workingAddress
        }

    }

    fun confirmConnected() {
        while (!socket.isClosed) {
            Log.d(this, "confirmConnected() expect connected/dismissed")
            val response = pr.readMessage()
            if (response == null) {
                Thread.sleep(SOCKET_TIMEOUT_MS / 10)
                Log.d(this, "confirmConnected() response is null")
                continue
            }

            val decrypted = Crypto.decryptMessage(
                response,
                otherPublicKey,
                ownPublicKey,
                ownSecretKey
            )

            if (decrypted == null) {
                peerConnection.reportStateChange(CallState.ERROR_DECRYPTION)
                return
            }

            if (!contact.publicKey.contentEquals(otherPublicKey)) {
                peerConnection.reportStateChange(CallState.ERROR_AUTHENTICATION)
                return
            }

            val obj = JSONObject(decrypted)
            val action = obj.getString("action")
            if (action == "connected") {
                Log.d(this, "confirmConnected() connected")
                peerConnection.reportStateChange(CallState.CONNECTED)
                val answer = obj.optString("answer")
                if (answer.isNotEmpty()) {
                    peerConnection.handleAnswer(answer)
                    continue
                } else {
                    peerConnection.reportStateChange(CallState.ERROR_COMMUNICATION)
                    break
                }
            } else if (action == "dismissed") {
                Log.d(this, "confirmConnected() dismissed")
                peerConnection.reportStateChange(CallState.DISMISSED)
                break
            } else if (action == "busy") {
                Log.d(this, "confirmConnected() busy")
                peerConnection.reportStateChange(CallState.BUSY)
                break
            } else {
                Log.w(this, "confirmConnected() unknown action reply $action")
                //peerConnection.reportStateChange(CallState.ERROR_COMMUNICATION)
                //break
            }
        }
        val isClosed = socket.isClosed
        Log.d(this, "Socket closed:$isClosed")
    }

    fun receiveIncoming(){
        while (!socket.isClosed) {
            val response = pr.readMessage()
            if (response == null) {
                Thread.sleep(SOCKET_TIMEOUT_MS / 10)
                Log.d(this, "receiveIncoming() response is null")
                continue
            }

            val decrypted = Crypto.decryptMessage(
                response,
                otherPublicKey,
                ownPublicKey,
                ownSecretKey
            )

            if (decrypted == null) {
                peerConnection.reportStateChange(CallState.ERROR_DECRYPTION)
                break
            }

            val obj = JSONObject(decrypted)
            val action = obj.optString("action")
            if (action == "dismissed") {
                Log.d(this, "receiveIncoming() received dismissed")
                peerConnection.reportStateChange(CallState.DISMISSED)
                peerConnection.declineOwnCall()
                break
            } else if (action == "busy") {
                Log.d(this, "receiveIncoming() busy")
                peerConnection.reportStateChange(CallState.BUSY)
                peerConnection.declineOwnCall()
                break
            } else {
                Log.w(this, "receiveIncoming() received unknown action reply: $action")
                //peerConnection.reportStateChange(CallState.ERROR_COMMUNICATION)
                //break
            }
        }
    }
}
