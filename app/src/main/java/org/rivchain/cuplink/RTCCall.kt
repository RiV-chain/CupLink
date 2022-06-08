package org.rivchain.cuplink

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatDelegate
import org.json.JSONException
import org.json.JSONObject
import org.rivchain.cuplink.MainService.MainBinder
import org.webrtc.*
import org.webrtc.PeerConnection.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


class RTCCall : DataChannel.Observer {
    private val StateChangeMessage = "StateChange"
    private val CameraDisabledMessage = "CameraDisabled"
    private val CameraEnabledMessage = "CameraEnabled"
    private val eglBaseContext = EglBase.create().eglBaseContext
    private val localRender = ProxyVideoSink()
    var state: CallState? = null
    var commSocket: Socket?
    private lateinit var factory: PeerConnectionFactory
    private var connection: PeerConnection? = null
    private var constraints: MediaConstraints? = null
    private var offer: String? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var capturer: CameraVideoCapturer? = null
    private var upStream: MediaStream? = null
    private lateinit var dataChannel: DataChannel
    var isSpeakerEnabled = false
    var isVideoEnabled = false
        set(enabled) {
            field = enabled
            try {
                if (enabled) {
                    capturer!!.startCapture(1024, 720, 30)
                    Handler(Looper.getMainLooper()).post {
                        localRenderer!!.visibility = View.VISIBLE
                        localRenderer!!.setZOrderOnTop(true)
                    }
                } else {
                    Handler(Looper.getMainLooper()).post { localRenderer!!.visibility = View.GONE }
                    capturer!!.stopCapture()
                }
                val `object` = JSONObject()
                `object`.put(
                    StateChangeMessage,
                    if (enabled) CameraEnabledMessage else CameraDisabledMessage
                )
                log("setVideoEnabled: $`object`")
                dataChannel.send(
                    DataChannel.Buffer(
                        ByteBuffer.wrap(
                            `object`.toString().toByteArray()
                        ), false
                    )
                )
            } catch (e: JSONException) {
                e.printStackTrace()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
    private var context: Context
    private var contact: Contact
    private var ownPublicKey: ByteArray
    private var ownSecretKey: ByteArray
    private var iceServers: MutableList<IceServer>
    private var listener: OnStateChangeListener?
    private var binder: MainBinder

    // called for incoming calls
    constructor(
        context: Context,
        binder: MainBinder,
        contact: Contact,
        commSocket: Socket?,
        offer: String?
    ) {
        this.context = context
        this.contact = contact
        this.commSocket = commSocket
        listener = null
        this.binder = binder
        ownPublicKey = binder.settings.publicKey!!
        ownSecretKey = binder.settings.secretKey!!
        this.offer = offer

        // usually empty
        iceServers = ArrayList()
        for (server in binder.settings.iceServers) {
            iceServers.add(IceServer.builder(server).createIceServer())
        }
        initRTC(context)
    }

    // called for outgoing calls
    private constructor(
        context: Context,
        binder: MainBinder,
        contact: Contact,
        listener: OnStateChangeListener
    ) {
        this.context = context
        this.contact = contact
        commSocket = null
        this.listener = listener
        this.binder = binder
        ownPublicKey = binder.settings.publicKey!!
        ownSecretKey = binder.settings.secretKey!!
        log("RTCCall created")

        // usually empty
        iceServers = ArrayList()
        for (server in binder.settings.iceServers) {
            iceServers.add(IceServer.builder(server).createIceServer())
        }
        initRTC(context)
        if (AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES) {
            context.setTheme(R.style.AppTheme_Dark)
        } else {
            context.setTheme(R.style.AppTheme_Light)
        }
        Thread(label@ Runnable {
            connection = factory!!.createPeerConnection(emptyList(), object : DefaultObserver() {
                override fun onIceGatheringChange(iceGatheringState: IceGatheringState) {
                    super.onIceGatheringChange(iceGatheringState)
                    if (iceGatheringState == IceGatheringState.COMPLETE) {
                        log("transferring offer...")
                        try {
                            commSocket = contact.createSocket()
                            if (commSocket == null) {
                                log("cannot establish connection")
                                reportStateChange(CallState.ERROR)
                                //RTCCall.this.binder.addCallEvent(contact, CallEvent.Type.OUTGOING_ERROR);
                                return
                            }
                            val remote_address =
                                commSocket!!.remoteSocketAddress as InetSocketAddress
                            val otherPublicKey = remote_address.address.address
                            log("outgoing call from remote address: $remote_address")

                            // remember latest working address
                            contact.setLastWorkingAddress(
                                InetSocketAddress(remote_address.address, MainService.serverPort)
                            )
                            log("connect..")
                            val pr = PacketReader(commSocket!!)
                            reportStateChange(CallState.CONNECTING)
                            run {
                                val obj = JSONObject()
                                obj.put("action", "call")
                                obj.put("offer", connection!!.localDescription.description)
                                val encrypted = Crypto.encryptMessage(
                                    obj.toString(),
                                    contact.publicKey,
                                    ownPublicKey,
                                    ownSecretKey
                                )
                                if (encrypted == null) {
                                    log("encrypted var is null")
                                    closeCommSocket()
                                    reportStateChange(CallState.ERROR)
                                    //RTCCall.this.binder.addCallEvent(contact, CallEvent.Type.OUTGOING_ERROR);
                                    return
                                }
                                val pw = PacketWriter(commSocket!!)
                                pw.writeMessage(encrypted)
                            }
                            run {
                                val response = pr.readMessage()
                                val decrypted = Crypto.decryptMessage(
                                    response,
                                    otherPublicKey,
                                    ownPublicKey,
                                    ownSecretKey
                                )
                                if (decrypted == null || !Arrays.equals(
                                        contact.publicKey,
                                        otherPublicKey
                                    )
                                ) {
                                    log("decrypted var is null or pubkey does not match")
                                    closeCommSocket()
                                    reportStateChange(CallState.ERROR)
                                    //RTCCall.this.binder.addCallEvent(contact, CallEvent.Type.OUTGOING_ERROR);
                                    return
                                }
                                val obj = JSONObject(decrypted)
                                if (obj.optString("action", "") != "ringing") {
                                    log("action not equals ringing")
                                    closeCommSocket()
                                    reportStateChange(CallState.ERROR)
                                    //RTCCall.this.binder.addCallEvent(contact, CallEvent.Type.OUTGOING_ERROR);
                                    return
                                }
                                log("ringing...")
                                reportStateChange(CallState.RINGING)
                            }
                            run {
                                val response = pr.readMessage()
                                val decrypted = Crypto.decryptMessage(
                                    response,
                                    otherPublicKey,
                                    ownPublicKey,
                                    ownSecretKey
                                )
                                if (decrypted == null || !Arrays.equals(
                                        contact.publicKey,
                                        otherPublicKey
                                    )
                                ) {
                                    log("decrypted (201) var is null or pubkey does not match")
                                    closeCommSocket()
                                    reportStateChange(CallState.ERROR)
                                    return
                                }
                                val obj = JSONObject(decrypted)
                                val action = obj.getString("action")
                                if (action == "connected") {
                                    reportStateChange(CallState.CONNECTED)
                                    handleAnswer(obj.getString("answer"))
                                    // contact accepted receiving call
                                    //RTCCall.this.binder.addCallEvent(contact, CallEvent.Type.OUTGOING_ACCEPTED);
                                } else if (action == "dismissed") {
                                    log("dismissed")
                                    closeCommSocket()
                                    reportStateChange(CallState.DISMISSED)
                                    // contact declined receiving call
                                    //RTCCall.this.binder.addCallEvent(contact, CallEvent.Type.OUTGOING_DECLINED);
                                } else {
                                    log("unknown action reply: $action")
                                    closeCommSocket()
                                    reportStateChange(CallState.ERROR)
                                    //RTCCall.this.binder.addCallEvent(contact, CallEvent.Type.OUTGOING_ERROR);
                                }
                            }
                        } catch (e: Exception) {
                            closeCommSocket()
                            e.printStackTrace()
                            reportStateChange(CallState.ERROR)
                            //RTCCall.this.binder.addCallEvent(contact, CallEvent.Type.OUTGOING_ERROR);
                        }
                    }
                }

                override fun onIceConnectionChange(iceConnectionState: IceConnectionState) {
                    log("onIceConnectionChange " + iceConnectionState.name)
                    super.onIceConnectionChange(iceConnectionState)
                    if (iceConnectionState == IceConnectionState.DISCONNECTED) {
                        reportStateChange(CallState.ENDED)
                    }
                }

                override fun onAddStream(mediaStream: MediaStream) {
                    super.onAddStream(mediaStream)
                    handleMediaStream(mediaStream)
                }

                override fun onDataChannel(dataChannel: DataChannel) {
                    super.onDataChannel(dataChannel)
                    this@RTCCall.dataChannel = dataChannel
                    dataChannel.registerObserver(this@RTCCall)
                }
            })
            val config = RTCConfiguration(iceServers)
            config.continualGatheringPolicy = ContinualGatheringPolicy.GATHER_ONCE
            connection!!.setConfiguration(config)
            connection!!.addStream(createStream())
            dataChannel = connection!!.createDataChannel("data", DataChannel.Init())
            dataChannel.registerObserver(this)
            connection!!.createOffer(object : DefaultSdpObserver() {
                override fun onCreateSuccess(sessionDescription: SessionDescription) {
                    super.onCreateSuccess(sessionDescription)
                    connection!!.setLocalDescription(DefaultSdpObserver(), sessionDescription)
                }
            }, constraints)
        }).start()
    }

    private fun closeCommSocket() {
        log("closeCommSocket")
        if (commSocket != null) {
            try {
                commSocket!!.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            commSocket = null
        }
    }

    private fun closePeerConnection() {
        log("closePeerConnection")
        if (connection != null) {
            try {
                connection!!.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            connection = null
        }
    }

    fun setRemoteRenderer(remoteRenderer: SurfaceViewRenderer?) {
        this.remoteRenderer = remoteRenderer
    }

    fun setLocalRenderer(localRenderer: SurfaceViewRenderer?) {
        this.localRenderer = localRenderer
        this.localRenderer?.setMirror(true);
    }

    fun switchFrontFacing() {
        if (capturer != null) {
            capturer!!.switchCamera(null)
        }
    }

    override fun onBufferedAmountChange(l: Long) {
        // nothing to do
    }

    override fun onStateChange() {
        // nothing to do
    }

    override fun onMessage(buffer: DataChannel.Buffer) {
        val data = ByteArray(buffer.data.remaining())
        buffer.data[data]
        val s = String(data)
        var `object`: JSONObject? = null
        try {
            log("onMessage: $s")
            `object` = JSONObject(s)
            if (`object`.has(StateChangeMessage)) {
                val state = `object`.getString(StateChangeMessage)
                when (state) {
                    CameraEnabledMessage, CameraDisabledMessage -> {
                        setRemoteVideoEnabled(state == CameraEnabledMessage)
                    }
                }
            }
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    private fun setRemoteVideoEnabled(enabled: Boolean) {
        Handler(Looper.getMainLooper()).post {
            if (enabled) {
                remoteRenderer!!.setBackgroundColor(Color.TRANSPARENT)
            } else {
                val typedValue = TypedValue()
                val theme = context.theme
                theme.resolveAttribute(R.attr.backgroundCardColor, typedValue, true)
                @ColorInt val color = typedValue.data
                remoteRenderer!!.setBackgroundColor(color)
            }
        }
    }

    fun releaseCamera() {
        if (capturer != null) {
            try {
                capturer!!.stopCapture()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
        if (remoteRenderer != null) {
            remoteRenderer!!.release()
        }
        if (localRenderer != null) {
            localRenderer!!.release()
        }
    }

    private fun handleMediaStream(stream: MediaStream) {
        log("handleMediaStream")
        if (remoteRenderer == null || stream.videoTracks.size == 0) {
            return
        }
        Handler(Looper.getMainLooper()).post {
            remoteRenderer!!.init(eglBaseContext, null)
            stream.videoTracks[0].addSink(remoteRenderer)
        }
    }

    private fun createStream(): MediaStream? {
        upStream = factory.createLocalMediaStream("stream1")
        upStream!!.addTrack(audioTrack)
        upStream!!.addTrack(videoTrack)
        return upStream
    }

    private fun createCapturer(): CameraVideoCapturer? {
        val enumerator: CameraEnumerator = Camera1Enumerator()
        for (name in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(name)) {
                return enumerator.createCapturer(name, null)
            }
        }
        return null
    }

    private val videoTrack: VideoTrack?
        private get() {
            capturer = createCapturer()
            if (capturer != null) {
                val surfaceTextureHelper =
                    SurfaceTextureHelper.create("CaptureThread", eglBaseContext)
                val videoSource = factory.createVideoSource(capturer!!.isScreencast())
                capturer!!.initialize(
                    surfaceTextureHelper,
                    this@RTCCall.context,
                    videoSource.getCapturerObserver()
                )
                localRender.setTarget(localRenderer)
                Handler(Looper.getMainLooper()).post {
                    localRenderer!!.init(
                        eglBaseContext,
                        null
                    )
                }
                val localVideoTrack = factory.createVideoTrack("video1", videoSource)
                localVideoTrack.addSink(localRenderer)
                localVideoTrack.setEnabled(true)
                return localVideoTrack
            }
            return null
        }
    private val audioTrack: AudioTrack
        private get() = factory.createAudioTrack(
            "audio1",
            factory.createAudioSource(MediaConstraints())
        )

    private fun initRTC(c: Context) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(c)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )
        val videoCodecHwAcceleration = true
        val encoderFactory: VideoEncoderFactory
        val decoderFactory: VideoDecoderFactory
        if (videoCodecHwAcceleration) {
            if(binder.settings.videoCodec=="H264") {
                encoderFactory = DefaultVideoEncoderFactory(
                    eglBaseContext, false /* enableIntelVp8Encoder */, true
                )
            } else {
                encoderFactory = DefaultVideoEncoderFactory(
                    eglBaseContext, true /* enableIntelVp8Encoder */, false
                )
            }
            decoderFactory = DefaultVideoDecoderFactory(eglBaseContext)
        } else {
            encoderFactory = SoftwareVideoEncoderFactory()
            decoderFactory = SoftwareVideoDecoderFactory()
        }

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        constraints = MediaConstraints()
        constraints!!.optional.add(MediaConstraints.KeyValuePair("offerToReceiveAudio", "true"))
        constraints!!.optional.add(MediaConstraints.KeyValuePair("offerToReceiveVideo", "false"))
        constraints!!.optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))

        //initVideoTrack();
    }

    private fun handleAnswer(remoteDesc: String) {
        connection!!.setRemoteDescription(object : DefaultSdpObserver() {
            override fun onSetSuccess() {
                super.onSetSuccess()
                log("onSetSuccess")
            }

            override fun onSetFailure(s: String) {
                super.onSetFailure(s)
                log("onSetFailure: $s")
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, remoteDesc))
    }

    private fun reportStateChange(state: CallState) {
        this.state = state
        if (listener != null) {
            listener!!.OnStateChange(state)
        }
    }

    fun accept(statsCollector: RTCStatsCollectorCallback?) {
        val scheduleTaskExecutor = Executors.newScheduledThreadPool(1)
        scheduleTaskExecutor.scheduleAtFixedRate(
            { connection!!.getStats(statsCollector) },
            1,
            5,
            TimeUnit.SECONDS
        )
    }

    fun accept(listener: OnStateChangeListener?) {
        this.listener = listener
        Thread {
            connection = factory.createPeerConnection(iceServers, object : DefaultObserver() {
                override fun onIceGatheringChange(iceGatheringState: IceGatheringState) {
                    super.onIceGatheringChange(iceGatheringState)
                    if (iceGatheringState == IceGatheringState.COMPLETE) {
                        log("onIceGatheringChange")
                        try {
                            val pw = PacketWriter(commSocket!!)
                            val obj = JSONObject()
                            obj.put("action", "connected")
                            obj.put("answer", connection!!.localDescription.description)
                            val encrypted = Crypto.encryptMessage(
                                obj.toString(),
                                contact.publicKey,
                                ownPublicKey,
                                ownSecretKey
                            )
                            if (encrypted != null) {
                                pw.writeMessage(encrypted)
                                reportStateChange(CallState.CONNECTED)
                            } else {
                                reportStateChange(CallState.ERROR)
                            }
                            //new Thread(new SpeakerRunnable(commSocket)).start();
                        } catch (e: Exception) {
                            e.printStackTrace()
                            reportStateChange(CallState.ERROR)
                        }
                    }
                }

                override fun onIceConnectionChange(iceConnectionState: IceConnectionState) {
                    log("onIceConnectionChange")
                    super.onIceConnectionChange(iceConnectionState)
                    if (iceConnectionState == IceConnectionState.DISCONNECTED) {
                        reportStateChange(CallState.ENDED)
                    }
                }

                override fun onAddStream(mediaStream: MediaStream) {
                    log("onAddStream")
                    super.onAddStream(mediaStream)
                    handleMediaStream(mediaStream)
                }

                override fun onDataChannel(dataChannel: DataChannel) {
                    super.onDataChannel(dataChannel)
                    this@RTCCall.dataChannel = dataChannel
                    dataChannel.registerObserver(this@RTCCall)
                }
            })
            connection!!.addStream(createStream())
            log("setting remote description")
            connection!!.setRemoteDescription(object : DefaultSdpObserver() {
                override fun onSetSuccess() {
                    super.onSetSuccess()
                    log("creating answer...")
                    connection!!.createAnswer(object : DefaultSdpObserver() {
                        override fun onCreateSuccess(sessionDescription: SessionDescription) {
                            log("onCreateSuccess")
                            super.onCreateSuccess(sessionDescription)
                            connection!!.setLocalDescription(
                                DefaultSdpObserver(),
                                sessionDescription
                            )
                        }

                        override fun onCreateFailure(s: String) {
                            super.onCreateFailure(s)
                            log("onCreateFailure: $s")
                        }
                    }, constraints)
                }
            }, SessionDescription(SessionDescription.Type.OFFER, offer))
        }.start()
    }

    fun decline() {
        Thread {
            try {
                log("declining...")
                if (commSocket != null) {
                    val pw = PacketWriter(commSocket!!)
                    val encrypted = Crypto.encryptMessage(
                        "{\"action\":\"dismissed\"}",
                        contact.publicKey,
                        ownPublicKey,
                        ownSecretKey
                    )
                    pw.writeMessage(encrypted)
                }
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                cleanup()
            }
        }.start()
    }

    fun cleanup() {
        closeCommSocket()
        if (upStream != null && state == CallState.CONNECTED) {
            /*for(AudioTrack track : this.upStream.audioTracks){
                track.setEnabled(false);
                track.dispose();
            }
            for(VideoTrack track : this.upStream.videoTracks) track.dispose();*/
            closePeerConnection()
            //factory.dispose();
        }
    }

    fun hangUp() {
        Thread {
            try {
                if (commSocket != null) {
                    val pw = PacketWriter(commSocket!!)
                    val encrypted = Crypto.encryptMessage(
                        "{\"action\":\"dismissed\"}",
                        contact.publicKey,
                        ownPublicKey,
                        ownSecretKey
                    )
                    pw.writeMessage(encrypted)
                }
                closeCommSocket()
                closePeerConnection()
                reportStateChange(CallState.ENDED)
            } catch (e: IOException) {
                e.printStackTrace()
                reportStateChange(CallState.ERROR)
            }
        }.start()
    }

    private fun log(s: String) {
        Log.d(this, s)
    }

    enum class CallState {
        CONNECTING, RINGING, CONNECTED, DISMISSED, ENDED, ERROR
    }

    fun interface OnStateChangeListener {
        fun OnStateChange(state: CallState?)
    }

    private class ProxyVideoSink : VideoSink {
        private var target: VideoSink? = null

        @Synchronized
        override fun onFrame(frame: VideoFrame) {
            if (target == null) {
                Logging.d(ContentValues.TAG, "Dropping frame in proxy because target is null.")
                return
            }
            target!!.onFrame(frame)
        }

        @Synchronized
        fun setTarget(target: VideoSink?) {
            this.target = target
        }
    }

    companion object {
        fun startCall(
            context: Context,
            binder: MainBinder,
            contact: Contact,
            listener: OnStateChangeListener
        ): RTCCall {
            return RTCCall(context, binder, contact, listener)
        }
    }
}