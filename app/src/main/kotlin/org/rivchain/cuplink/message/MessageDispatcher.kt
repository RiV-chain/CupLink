package org.rivchain.cuplink.message

import org.json.JSONObject
import org.rivchain.cuplink.Crypto
import org.rivchain.cuplink.call.PacketWriter
import org.rivchain.cuplink.util.Log
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MessageDispatcher(
    private val socket: Socket,
    private val contactPublicKey: ByteArray,
    private val ownPublicKey: ByteArray,
    private val ownSecretKey: ByteArray,
    private val socketTimeoutMs: Long
) {

    @Volatile
    private var lastKeepAlive: Long = System.currentTimeMillis()

    private val messageExecutor = Executors.newSingleThreadExecutor()
    private val socketLock = ReentrantLock()

    fun startKeepAlive() {
        messageExecutor.execute {
            Log.d(this,  "startKeepAlive() - Begin sending keep_alive messages")
            while (isSocketOpen()) {
                try {
                    val keepAliveMessage = JSONObject().apply {
                        put("action", "keep_alive")
                    }
                    sendMessage(keepAliveMessage)

                    Thread.sleep(socketTimeoutMs / 2)

                    if ((System.currentTimeMillis() - lastKeepAlive) > socketTimeoutMs) {
                        Log.w(this, "startKeepAlive() - Keep-alive timeout exceeded, closing socket")
                        closeSocket()
                    }
                } catch (e: Exception) {
                    Log.w(this, "startKeepAlive() - Exception occurred: $e, closing socket")
                    closeSocket()
                    break
                }
            }
            Log.d(this,  "startKeepAlive() - Stopped sending keep_alive messages")
        }
    }

    fun updateLastKeepAlive() {
        lastKeepAlive = System.currentTimeMillis()
    }

    /**
     * Sends a generic message.
     * @param message The JSONObject to be sent.
     */
    fun sendMessage(message: JSONObject) {
        socketLock.withLock {
            if (!isSocketOpen()) {
                Log.w(this, "sendMessage() - Socket is closed, cannot send message")
                return
            }

            try {
                val encryptedMessage = Crypto.encryptMessage(
                    message.toString(),
                    contactPublicKey,
                    ownPublicKey,
                    ownSecretKey
                ) ?: throw IllegalStateException("Encryption failed")

                val packetWriter = PacketWriter(socket)
                packetWriter.writeMessage(encryptedMessage)
                Log.d(this,  "sendMessage() - Message sent: ${message.toString()}")
            } catch (e: Exception) {
                Log.e(this,  "sendMessage() - Error sending message: $e")
                closeSocket()
            }
        }
    }

    private fun closeSocket() {
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
}
