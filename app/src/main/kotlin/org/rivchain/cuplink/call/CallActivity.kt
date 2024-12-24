package org.rivchain.cuplink.call

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.AnimationDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.os.SystemClock
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.OnTouchListener
import android.view.View.VISIBLE
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.UiThread
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.h6ah4i.android.widget.verticalseekbar.VerticalSeekBar
import org.rivchain.cuplink.BaseActivity
import org.rivchain.cuplink.CallService
import org.rivchain.cuplink.CallStatusService
import org.rivchain.cuplink.DatabaseCache
import org.rivchain.cuplink.MainActivity
import org.rivchain.cuplink.MainService
import org.rivchain.cuplink.R
import org.rivchain.cuplink.call.RTCAudioManager.AudioDevice
import org.rivchain.cuplink.call.RTCPeerConnection.CallState
import org.rivchain.cuplink.model.Contact
import org.rivchain.cuplink.model.Event
import org.rivchain.cuplink.renderer.TextureViewRenderer
import org.rivchain.cuplink.renderer.VisualizerView
import org.rivchain.cuplink.rivmesh.AppStateReceiver
import org.rivchain.cuplink.rivmesh.STATE_CALLING
import org.rivchain.cuplink.rivmesh.STATE_CALL_ENDED
import org.rivchain.cuplink.util.Log
import org.rivchain.cuplink.util.ServiceUtil
import org.rivchain.cuplink.util.Utils
import org.rivchain.cuplink.util.ViewUtil
import org.webrtc.CameraEnumerationAndroid
import org.webrtc.EglBase
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.net.InetSocketAddress
import java.util.Date

class CallActivity : BaseActivity(), RTCCall.CallContext {

    private var service: MainService? = null
    private lateinit var connection: ServiceConnection
    private lateinit var currentCall: RTCCall
    private lateinit var eglBase: EglBase
    private lateinit var proximitySensor: RTCProximitySensor
    private lateinit var rtcAudioManager: RTCAudioManager

    private var proximityScreenLock: WakeLock? = null
    private var proximityCameraWasOn = false

    private var activityActive = true
    private var callEventType = Event.Type.UNKNOWN

    private val remoteProxyVideoSink = RTCCall.ProxyVideoSink()
    private val localProxyVideoSink = RTCCall.ProxyVideoSink()

    private lateinit var pipContainer: CardView
    private lateinit var pipRenderer: TextureViewRenderer
    private lateinit var fullscreenRenderer: SurfaceViewRenderer

    // call info texts
    private lateinit var callStatus: TextView
    private lateinit var callDuration: Chronometer
    private lateinit var callStats: TextView
    private lateinit var callAddress: TextView
    private lateinit var callName: TextView
    private lateinit var notificationText: TextView

    // control buttons
    private lateinit var acceptButton: ImageButton
    private lateinit var declineButton: ImageButton
    private lateinit var toggleCameraButton: ImageButton
    private lateinit var toggleMicButton: ImageButton
    private lateinit var toggleFrontCameraButton: ImageButton
    private lateinit var speakerphoneButton: ImageButton

    private lateinit var changePipButton: FrameLayout // show/hide Picture-in-Picture window
    private lateinit var redButton: ImageButton // show/hide Picture-in-Picture window
    private lateinit var changeUiButton: ImageButton // show/hide different control
    private lateinit var controlPanel: View
    private lateinit var capturePanel: View
    private lateinit var captureResolution: ImageView
    private lateinit var captureFramerate: Button
    private lateinit var resolutionSlider: VerticalSeekBar
    private lateinit var framerateSlider: VerticalSeekBar
    private lateinit var backgroundView: ImageView
    private lateinit var settingsView: ConstraintLayout
    private lateinit var captureQualityController: CaptureQualityController
    private lateinit var visualizerView: VisualizerView

    private var uiMode = 0

    // set by CallActivity
    private var swappedVideoFeeds = false // swapped fullscreen and pip video content
    private var showPipEnabled = true // enable PIP window
    private var callWasStarted = false

    // set by RTCall
    private var isLocalVideoAvailable = false // own camera is on/off
    private var isRemoteVideoAvailable = false // we receive a video feed

    private lateinit var contact: Contact
    private lateinit var microphoneUsageMonitor: MicrophoneUsageMonitor

    private val statsCollector = object : RTCStatsCollectorCallback {
        var statsReportUtil = StatsReportUtil()

        override fun onStatsDelivered(rtcStatsReport: RTCStatsReport) {
            val stats = statsReportUtil.getStatsReport(rtcStatsReport)
            /*
            Fix stats here
             */
            //runOnUiThread {
            //    callStats.text = stats
            //}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(this, "onCreate()")

        // keep screen on during the call
        window.addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_call)

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

        // keep screen on during the call
        //window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        callStatus = findViewById(R.id.callStatus)
        callDuration = findViewById(R.id.callDuration)
        callStats = findViewById(R.id.callStats)
        //callAddress = findViewById(R.id.callAddress)
        callName = findViewById(R.id.callName)
        notificationText = findViewById(R.id.notificationText)
        pipContainer = findViewById(R.id.pip_video_view_container)
        pipRenderer = findViewById(R.id.pip_video_view)
        fullscreenRenderer = findViewById(R.id.fullscreen_video_view)
        toggleCameraButton = findViewById(R.id.toggleCameraButton)
        toggleMicButton = findViewById(R.id.toggleMicButton)
        acceptButton = findViewById(R.id.acceptButton)
        declineButton = findViewById(R.id.declineButton)
        toggleFrontCameraButton = findViewById(R.id.frontFacingSwitch)
        speakerphoneButton = findViewById(R.id.speakerphoneButton)
        changePipButton = findViewById(R.id.change_pip_window)
        redButton = findViewById(R.id.redButton)
        changeUiButton = findViewById(R.id.change_ui)
        controlPanel = findViewById(R.id.controlPanel)
        capturePanel = findViewById(R.id.capturePanel)
        captureResolution = findViewById(R.id.captureResolution)
        captureFramerate = findViewById(R.id.captureFramerate)
        resolutionSlider = findViewById(R.id.captureResolutionSlider)
        framerateSlider = findViewById(R.id.captureFramerateSlider)

        // Background
        backgroundView = findViewById(R.id.background_view)
        settingsView = findViewById(R.id.call_status_view)

        visualizerView = findViewById(R.id.visualizerView)

        // make both invisible
        showPipView(false)
        showFullscreenView(false)

        acceptButton.visibility = GONE
        declineButton.visibility = GONE
        toggleMicButton.visibility = GONE
        toggleCameraButton.visibility = GONE
        toggleFrontCameraButton.visibility = GONE

        if(intent == null || intent.extras == null || intent.extras?.get("EXTRA_CONTACT") == null){
            finish()
            return
        }

        contact = intent.extras?.get("EXTRA_CONTACT") as Contact

        eglBase = EglBase.create()
        proximitySensor = RTCProximitySensor(applicationContext)
        rtcAudioManager = RTCAudioManager(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val audioSessionsCounter = rtcAudioManager.getAudioSessionsCounter(this)
            if (audioSessionsCounter > 0){
                // notify user about MultipleMicrophoneUsage
                notificationText.visibility = VISIBLE
            } else {
                notificationText.visibility = INVISIBLE
            }
        }

        pipRenderer.init(eglBase)
        pipRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_BALANCED)
        pipRenderer.setMirror(true)

        fullscreenRenderer.init(eglBase.eglBaseContext, null)
        fullscreenRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)

        fullscreenRenderer.setEnableHardwareScaler(false)

        captureQualityController = CaptureQualityController(this)

        // this activity auto accepts calls always
        //initRinging()

        if (contact.name.isEmpty()) {
            callName.text = resources.getString(R.string.unknown_caller)
        } else {
            callName.text = contact.name
        }

        Log.d(this, "intent: ${intent.action}, state: ${this.lifecycle.currentState}")

        when (val action = intent.action) {
            "ACTION_OUTGOING_CALL" -> initOutgoingCall()
            "ACTION_INCOMING_CALL" -> initIncomingCall()
            "ANSWER_INCOMING_CALL" -> initIncomingCall()
            "DECLINE_INCOMING_CALL" -> {
                Log.d(this, "action: $action")
                finish()
            }
            else -> {
                Log.e(this, "invalid action: $action, this should never happen")
                finish()
            }
        }
        adjustLayout(getResources().configuration)

        val intent = Intent(AppStateReceiver.APP_STATE_INTENT)
        val state = STATE_CALLING
        intent.putExtra("state", state)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        PendingIntent.getBroadcast(
            this,
            System.currentTimeMillis().toInt(),
            Intent().apply {
                action = CallService.START_CALL_ACTION
                putExtra("EXTRA_CONTACT", contact)
            },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        ).send()
        // Request microphone permission if not granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MICROPHONE_PERMISSION_REQUEST_CODE
            )
        } else {
            startCallStatusService()
        }

        // Request call READ_PHONE_STATE permission if not granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_PHONE_STATE),
                READ_PHONE_STATE_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MICROPHONE_PERMISSION_REQUEST_CODE) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                startCallStatusService()
            } else {
                // Handle permission denied case
            }
        }
    }


    private fun startCallStatusService() {
        val intent = Intent(this, CallStatusService::class.java)
            .putExtra(
                CallService.SERVICE_CONTACT_KEY,
                contact)
        startService(intent)
    }

    private val MICROPHONE_PERMISSION_REQUEST_CODE = 101

    private val READ_PHONE_STATE_PERMISSION_REQUEST_CODE = 102

    override fun onConfigurationChanged(newConfig: Configuration)
    {
        Log.d("tag", "config changed")
        super.onConfigurationChanged(newConfig);
        adjustLayout(newConfig)
    }

    @UiThread
    private fun adjustLayout(newConfig: Configuration) {

        val callStatusLayout = findViewById<LinearLayout>(R.id.callStatusLayout)
        if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            Log.d("tag", "Portrait");
            // portrait
            (pipContainer.layoutParams as ConstraintLayout.LayoutParams).dimensionRatio = "H,3:4"
            (pipContainer.layoutParams as ConstraintLayout.LayoutParams).matchConstraintPercentWidth = 0.35f

            callStatusLayout.orientation = LinearLayout.VERTICAL
        } else if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Log.d("tag", "Landscape");
            // landscape
            (pipContainer.layoutParams as ConstraintLayout.LayoutParams).dimensionRatio = "W,3:4"
            (pipContainer.layoutParams as ConstraintLayout.LayoutParams).matchConstraintPercentHeight = 0.6f

            callStatusLayout.orientation = LinearLayout.HORIZONTAL
        } else {
            Log.w("tag", "other: " + newConfig.orientation)
        }
        pipContainer.translationX = 0f
        pipContainer.translationY = 0f
    }

    private fun getFullscreenWindowFlags(): Int {
        var flags = (
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        or WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES
                )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            flags = flags or WindowManager.LayoutParams.FLAG_FULLSCREEN
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            @Suppress("DEPRECATION")
            flags = flags or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            @Suppress("DEPRECATION")
            flags = flags or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        }
        return flags
    }

    private fun hideSystemBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, findViewById(R.id.callLayout)).let {
                controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    override fun getContext(): Context {
        return this.applicationContext
    }

    override fun onStateChange(state: CallState) {
        runOnUiThread {
            Log.d(this, "onStateChange() state=$state")
            val isIncoming = (intent.action == "ACTION_INCOMING_CALL")

            val handleError = { messageId: Int ->
                callStatus.text = getString(messageId)
                callEventType = if (isIncoming) {
                    Event.Type.INCOMING_ERROR
                } else {
                    Event.Type.OUTGOING_ERROR
                }
                finishDelayed()
            }

            val handleExit = { messageId: Int ->
                callStatus.text = getString(messageId)
                callEventType = if (callWasStarted) {
                    if (isIncoming) {
                        Event.Type.INCOMING_ACCEPTED
                    } else {
                        Event.Type.OUTGOING_ACCEPTED
                    }
                } else {
                    if (isIncoming) {
                        Event.Type.INCOMING_MISSED
                    } else {
                        Event.Type.OUTGOING_MISSED
                    }
                }
                finishDelayed()
            }

            val setContactState = { state: Contact.State ->
                val b = service
                if (b != null) {
                    val storedContact = DatabaseCache.database.contacts.getContactByPublicKey(contact.publicKey)
                    if (storedContact != null) {
                        storedContact.state = state
                    } else {
                        contact.state = state
                    }
                } else {
                    Log.w(this, "setContactState() binder is null")
                }
            }
            if(!isIncoming || state != CallState.RINGING) {
                currentCall.playTone(state)
            }
            when (state) {
                CallState.WAITING -> {
                    callDuration.visibility = GONE
                    callStatus.visibility = VISIBLE
                    callStatus.text = getString(R.string.call_waiting)
                }
                CallState.CONNECTING -> {
                    callDuration.visibility = GONE
                    callStatus.visibility = VISIBLE
                    callStatus.text = getString(R.string.call_connecting)
                }
                CallState.RINGING -> {
                    callDuration.visibility = GONE
                    callStatus.visibility = VISIBLE
                    callStatus.text = getString(R.string.call_ringing)
                    setContactState(Contact.State.CONTACT_ONLINE)
                }
                CallState.CONNECTED -> {
                    // call started
                    acceptButton.visibility = GONE
                    declineButton.visibility = VISIBLE
                    //callStatus.text = getString(R.string.call_connected)
                    callStatus.visibility = GONE
                    callDuration.visibility = VISIBLE
                    callDuration.setBase(SystemClock.elapsedRealtime());
                    callDuration.start()
                    callWasStarted = true
                    updateCameraButtons()
                    setContactState(Contact.State.CONTACT_ONLINE)
                    changeUiButton.visibility = VISIBLE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        microphoneUsageMonitor = MicrophoneUsageMonitor(this, rtcAudioManager)
                        microphoneUsageMonitor.startMonitoring { isMultipleRecording ->
                            if (isMultipleRecording) {
                                // Handle the case where multiple apps are using the microphone
                                notificationText.visibility = VISIBLE
                            } else {
                                // Only your app is using the microphone
                                notificationText.visibility = INVISIBLE
                            }
                        }
                    }
                }
                CallState.RE_CONNECTING -> {
                    callStatus.visibility = VISIBLE
                    callDuration.visibility = GONE
                    callStatus.text = getString(R.string.call_reconnecting)
                }
                CallState.ON_HOLD -> {
                    callStatus.visibility = VISIBLE
                    callStatus.text = "On hold"
                }
                CallState.RESUME -> {
                    callStatus.visibility = GONE
                }
                CallState.DISMISSED -> {
                    // call did not start
                    handleExit(R.string.call_decline)
                    setContactState(Contact.State.CONTACT_ONLINE)
                }
                CallState.BUSY -> {
                    handleExit(R.string.busy)
                    setContactState(Contact.State.CONTACT_ONLINE)
                }
                CallState.ENDED -> {
                    // normal call end
                    handleExit(R.string.call_ended)
                    setContactState(Contact.State.CONTACT_ONLINE)
                }
                CallState.ERROR_NO_CONNECTION -> {
                    handleError(R.string.call_connection_failed)
                    setContactState(Contact.State.CONTACT_OFFLINE)
                }
                CallState.ERROR_AUTHENTICATION -> {
                    handleError(R.string.call_authentication_failed)
                    setContactState(Contact.State.AUTHENTICATION_FAILED)
                }
                CallState.ERROR_DECRYPTION -> {
                    handleError(R.string.call_error)
                }
                CallState.ERROR_CONNECT_PORT -> {
                    handleError(R.string.call_error_not_listening)
                    setContactState(Contact.State.APP_NOT_RUNNING)
                }
                CallState.ERROR_NO_ADDRESSES -> {
                    handleError(R.string.call_error_no_address)
                    setContactState(Contact.State.CONTACT_OFFLINE)
                }
                CallState.ERROR_UNKNOWN_HOST -> {
                    handleError(R.string.call_error_unresolved_hostname)
                    setContactState(Contact.State.CONTACT_OFFLINE)
                }
                CallState.ERROR_COMMUNICATION -> {
                    handleError(R.string.call_error)
                    setContactState(Contact.State.COMMUNICATION_FAILED)
                }
                CallState.ERROR_NO_NETWORK -> {
                    handleError(R.string.call_no_network)
                    setContactState(Contact.State.CONTACT_OFFLINE)
                }
            }
        }
    }

    private fun updateVideoDisplay() {
        val frontCameraEnabled = currentCall.getFrontCameraEnabled()

        Log.d(this, "updateVideoDisplay() swappedVideoFeeds=$swappedVideoFeeds, frontCameraEnabled=$frontCameraEnabled")

        if (swappedVideoFeeds) {
            localProxyVideoSink.setTarget(fullscreenRenderer)
            remoteProxyVideoSink.setTarget(pipRenderer)

            fullscreenRenderer.setMirror(frontCameraEnabled)
            pipRenderer.setMirror(false)

            showPipView(isRemoteVideoAvailable && showPipEnabled)
            hideCallBackground(isLocalVideoAvailable)
            showFullscreenView(isLocalVideoAvailable)

            // video available for pip
            setPipButtonEnabled(isRemoteVideoAvailable)
        } else {
            // default (local video in pip, remote video in fullscreen)
            localProxyVideoSink.setTarget(pipRenderer)
            remoteProxyVideoSink.setTarget(fullscreenRenderer)

            pipRenderer.setMirror(frontCameraEnabled)
            fullscreenRenderer.setMirror(false)

            showPipView(isLocalVideoAvailable && showPipEnabled)
            hideCallBackground(isRemoteVideoAvailable)
            showFullscreenView(isRemoteVideoAvailable)

            // video available for pip
            setPipButtonEnabled(isLocalVideoAvailable)
        }
        setVideoPreferencesButtonsEnabled(isLocalVideoAvailable)
    }

    private fun updateCameraButtons() {
        val cameraEnabled = currentCall.getCameraEnabled()

        Log.d(this, "updateCameraButtons() cameraEnabled=$cameraEnabled")

        if (cameraEnabled) {
            toggleFrontCameraButton.visibility = VISIBLE
            toggleCameraButton.setImageResource(R.drawable.selector_camera_on)
        } else {
            toggleFrontCameraButton.visibility = GONE
            toggleCameraButton.setImageResource(R.drawable.selector_camera_off)
        }
    }

    private fun updateControlDisplay() {
        Log.d(this, "updateControlDisplay() uiMode=$uiMode")

        val updateDebug = { enable: Boolean ->
            if (enable) {
                if (callWasStarted) {
                    currentCall.setStatsCollector(statsCollector)
                }
                callStats.visibility = VISIBLE
            } else {
                if (callWasStarted) {
                    currentCall.setStatsCollector(null)
                }
                callStats.visibility = GONE
            }
        }

        when (uiMode % 2) {
            0 -> {
                // default
                updateDebug(false)
                controlPanel.visibility = VISIBLE
                callName.visibility = VISIBLE
                if (callWasStarted) {
                    callStatus.visibility = GONE
                    callDuration.visibility = VISIBLE
                } else {
                    callStatus.visibility = VISIBLE
                    callDuration.visibility = GONE
                }
                setVideoPreferencesButtonsEnabled(isLocalVideoAvailable)
            }
            1 -> {
                // all off
                updateDebug(false)
                controlPanel.visibility = INVISIBLE
                callName.visibility = INVISIBLE
                callStatus.visibility = GONE
                callDuration.visibility = GONE
                setVideoPreferencesButtonsEnabled(false)
            }
        }
    }

    private fun setVideoPreferencesButtonsEnabled(enable: Boolean) {
        Log.d(this, "setVideoPreferencesButtonsEnabled() enable=$enable")
        if (enable) {
            captureResolution.visibility = VISIBLE
            captureFramerate.visibility = VISIBLE
            if (captureResolution.tag == "on") {
                resolutionSlider.visibility = VISIBLE
            } else {
                resolutionSlider.visibility = INVISIBLE
            }
            if (captureFramerate.tag == "on") {
                framerateSlider.visibility = VISIBLE
            } else {
                framerateSlider.visibility = INVISIBLE
            }
        } else {
            captureResolution.visibility = INVISIBLE
            captureFramerate.visibility = INVISIBLE
            resolutionSlider.visibility = INVISIBLE
            framerateSlider.visibility = INVISIBLE
        }
    }

    private fun setPipButtonEnabled(enable: Boolean) {
        Log.d(this, "setPipButtonEnabled() enable=$enable")
        if (enable) {
            changePipButton.visibility = VISIBLE
        } else {
            changePipButton.visibility = INVISIBLE
        }
    }

    private fun showPipView(enable: Boolean) {
        Log.d(this, "showPipView() enable=$enable")
        changePipButton.bringToFront()
        if (enable) {
            pipRenderer.visibility = VISIBLE
            changePipButton.setBackgroundColor(ContextCompat.getColor(this, R.color.colorTransparent))
            setBlinkFrequency(redButton, R.drawable.ic_camera_blinking_normal)
        } else {
            pipRenderer.visibility = GONE
            changePipButton.setBackgroundResource(R.drawable.red_button_background)
            setBlinkFrequency(redButton, R.drawable.ic_camera_blinking_short)
        }
    }

    private fun setBlinkFrequency(imageButton: ImageButton, animationResId: Int) {
        val drawable = ContextCompat.getDrawable(imageButton.context, animationResId)
        if (drawable is AnimationDrawable) {
            imageButton.setImageDrawable(drawable)
            drawable.start()
        }
    }

    private fun hideCallBackground(enable: Boolean) {
        Log.d(this, "hideCallBackground() enable=$enable")
        if (enable) {
            backgroundView.visibility = INVISIBLE
        } else {
            backgroundView.visibility = VISIBLE
        }
    }

    private fun showFullscreenView(enable: Boolean) {
        Log.d(this, "showFullscreenView() enable=$enable")
        if (enable) {
            fullscreenRenderer.visibility = VISIBLE
        } else {
            fullscreenRenderer.visibility = INVISIBLE
        }
    }

    private fun updateMicrophoneIcon() {
        Log.d(this, "updateMicrophoneIcon()")

        val enabled = currentCall.getMicrophoneEnabled() && rtcAudioManager.getMicrophoneEnabled()

        if (enabled) {
            toggleMicButton.setImageResource(R.drawable.selector_mic_on)
        } else {
            toggleMicButton.setImageResource(R.drawable.selector_mic_off)
        }

        // set background
        val settings = DatabaseCache.database.settings
        if (settings.pushToTalk) {
            val backgroundId = when (enabled) {
                true -> R.drawable.ic_button_background_enabled_border
                false -> R.drawable.ic_button_background_disabled_border
            }
            toggleMicButton.background = ResourcesCompat.getDrawable(resources, backgroundId, null)
        }
    }

    override fun onDataChannelReady() {
        Log.d(this, "onDataChannelReady()")
        runOnUiThread {
            updateCameraButtons()
            updateControlDisplay()

            val settings = DatabaseCache.database.settings
            if (settings.enableMicrophoneByDefault != currentCall.getMicrophoneEnabled()) {
                if (!settings.pushToTalk) {
                    Log.d(this, "onDataChannelReady() toggle microphone")
                    toggleMicButton.performClick()
                }
            }

            if (settings.enableCameraByDefault != currentCall.getCameraEnabled()) {
                Log.d(this, "onDataChannelReady() toggle camera")
                toggleCameraButton.performClick()
            }

            if (settings.selectFrontCameraByDefault != currentCall.getFrontCameraEnabled()) {
                Log.d(this, "onDataChannelReady() toggle front camera")
                toggleFrontCameraButton.performClick()
            }
        }
    }

    override fun onLocalVideoEnabled(enabled: Boolean) {
        Log.d(this, "onLocalVideoEnabled() enabled=$enabled")
        runOnUiThread {
            isLocalVideoAvailable = enabled
            updateVideoDisplay()
            updateCameraButtons()
            updateControlDisplay()
        }
    }

    override fun onRemoteVideoEnabled(enabled: Boolean) {
        Log.d(this, "onRemoteVideoEnabled() enabled=$enabled")
        runOnUiThread {
            isRemoteVideoAvailable = enabled
            updateVideoDisplay()
            updateControlDisplay()
        }
    }

    override fun onMicrophoneEnabled(enabled: Boolean) {
        Log.d(this, "onMicrophoneEnabled() enabled=$enabled")
        runOnUiThread {
            updateMicrophoneIcon()
        }
    }

    // set debug output
    override fun onRemoteAddressChange(address: InetSocketAddress, isConnected: Boolean) {
        runOnUiThread {
            val addressString = address.toString().replace("/", "")
            val formatString = if (isConnected) {
                getString(R.string.connected_to_address)
            } else {
                getString(R.string.connecting_to_address)
            }
            //callAddress.text = String.format(formatString, addressString)
        }
    }

    override fun showTextMessage(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun visualizeAudio(magnitudeData: ByteArray?) {
        visualizerView.post {
            visualizerView.updateFFT(magnitudeData)
        }
    }

    private fun initOutgoingCall() {
        connection = object : ServiceConnection {
            override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
                Log.d(this@CallActivity, "onServiceConnected")
                service = (iBinder as MainService.MainBinder).getService()
                currentCall = RTCCall(service!!, contact)
                isCallInProgress = true
                currentCall.setCallContext(this@CallActivity)

                captureQualityController.initFromSettings(DatabaseCache.database.settings)

                updateControlDisplay()
                updateVideoDisplay()
                continueCallSetup()

                if (!DatabaseCache.database.settings.promptOutgoingCalls) {
                    // start outgoing call immediately
                    acceptButton.performClick()
                }
            }

            override fun onServiceDisconnected(componentName: ComponentName) {
                // nothing to do
            }
        }

        bindService(Intent(this, MainService::class.java), connection, 0)

        val declineListener = View.OnClickListener {
            Log.d(this, "decline call...")
            if (!this::currentCall.isInitialized) {
                Log.d(this, "currentCall not set")
                return@OnClickListener
            }
            if (callWasStarted) {
                currentCall.hangup()
            } else {
                currentCall.decline()
            }
        }

        val startCallListener = View.OnClickListener {
            Log.d(this, "start call...")
            if (!this::currentCall.isInitialized) {
                Log.d(this, "currentCall not set")
                return@OnClickListener
            }

            currentCall.setRemoteRenderer(remoteProxyVideoSink)
            currentCall.setLocalRenderer(localProxyVideoSink)
            currentCall.setEglBase(eglBase)
            currentCall.setCallContext(this@CallActivity)

            currentCall.initVideo()
            currentCall.initOutgoing()

            acceptButton.visibility = GONE
            declineButton.visibility = VISIBLE

            initCall()
        }

        acceptButton.visibility = VISIBLE
        declineButton.visibility = VISIBLE

        acceptButton.setOnClickListener(startCallListener)
        declineButton.setOnClickListener(declineListener)
    }

    private fun initIncomingCall() {
        initServiceConnection()

        // decline before call starts
        val declineListener = View.OnClickListener {
            Log.d(this, "decline call...")
            declineCall()
        }

        // accept call
        val acceptListener = View.OnClickListener {
            Log.d(this, "accept call...")
            acceptCall()
        }

        acceptButton.setOnClickListener(acceptListener)
        declineButton.setOnClickListener(declineListener)

        acceptButton.visibility = VISIBLE
        declineButton.visibility = VISIBLE

        bindService(Intent(this, MainService::class.java), connection, 0)
    }

    private fun initServiceConnection(){
        connection = object : ServiceConnection {
            override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
                Log.d(this@CallActivity, "onServiceConnected()")
                service = (iBinder as MainService.MainBinder).getService()
                currentCall = RTCPeerConnection.incomingRTCCall ?: run {
                    // This happens when the call is missed while in background.
                    // and then the CallActivity is started from Recent Apps.
                    Log.d(this, "initIncomingCall() no call active => start MainActivity")
                    val intent = Intent(getContext(), MainActivity::class.java)
                    startActivity(intent)
                    finish()
                    return
                }
                isCallInProgress = true
                currentCall.setRemoteRenderer(remoteProxyVideoSink)
                currentCall.setLocalRenderer(localProxyVideoSink)
                currentCall.setCallContext(this@CallActivity)
                currentCall.setEglBase(eglBase)

                captureQualityController.initFromSettings(DatabaseCache.database.settings)

                Thread {
                    currentCall.continueOnIncomingSocket()
                }.start()

                updateControlDisplay()
                updateVideoDisplay()
                continueCallSetup()

                acceptButton.performClick()
            }

            override fun onServiceDisconnected(componentName: ComponentName) {
                // nothing to do
            }
        }
    }

    private fun declineCall(){
        if (callWasStarted) {
            currentCall.hangup()
        } else {
            currentCall.decline()
        }
    }

    private fun acceptCall(){
        if (!this::currentCall.isInitialized) {
            Log.d(this, "currentCall not set")
            return
        }
        acceptButton.visibility = GONE
        declineButton.visibility = VISIBLE
        currentCall.initVideo()
        currentCall.initIncoming()
        initCall()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun continueCallSetup() {
        Log.d(this, "continueCallSetup()"
            + " lifecycle.currentState: ${lifecycle.currentState}"
            + ", init.action: ${intent.action}"
            + ", lifecycle.currentState: ${this.lifecycle.currentState}"
            + ", audio permissions: ${Utils.hasPermission(this, Manifest.permission.RECORD_AUDIO)}"
            + ", video permissions: ${Utils.hasPermission(this, Manifest.permission.CAMERA)}"
        )

        val settings = DatabaseCache.database.settings

        // swap pip and fullscreen content
        pipContainer.setOnClickListener {
            Log.d(this, "pipRenderer.setOnClickListener()")
            showPipEnabled = true
            swappedVideoFeeds = !swappedVideoFeeds
            updateVideoDisplay()
        }

        pipContainer.setOnTouchListener(object : OnTouchListener {
            var dX = 0f
            var dY = 0f
            var oX = 0f
            var oY = 0f
            var newX = 0f
            var newY = 0f
            private val gestureDetector =
                GestureDetector(this@CallActivity, object : SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        pipContainer.translationX = 0f
                        pipContainer.translationY = 0f
                        pipContainer.performClick()
                        return true
                    }
                })

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                if (gestureDetector.onTouchEvent(event)) {
                    return true
                }
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dX = v.x - event.rawX
                        oX = v.x
                        dY = v.y - event.rawY
                        oY = v.y
                    }

                    MotionEvent.ACTION_MOVE -> {
                        newX = event.rawX + dX
                        newY = event.rawY + dY
                        if (newX < 0) {
                            newX = 0f
                        } else if (newX > settingsView.width - pipContainer.width) {
                            newX =
                                (settingsView.width - pipContainer.width).toFloat()
                        }
                        if (newY < 0) {
                            newY = 0f
                        } else if (newY > settingsView.height - pipContainer.height) {
                            newY =
                                (settingsView.height - pipContainer.height).toFloat()
                        }
                        pipContainer.bringToFront()
                        v.animate()
                            .x(newX)
                            .y(newY)
                            .setDuration(0)
                            .start()
                    }

                    MotionEvent.ACTION_UP -> {
                        newX = event.rawX + dX
                        newY = event.rawY + dY
                    }

                    else -> return false
                }
                return true
            }
        })

        // swap pip and fullscreen content
        fullscreenRenderer.setOnClickListener {
            Log.d(this, "fullscreenRenderer.setOnClickListener()")
            swappedVideoFeeds = !swappedVideoFeeds
            showPipEnabled = true
            updateVideoDisplay()
        }

        changePipButton.setOnClickListener {
            Log.d(this, "changePipButton.setOnClickListener()")
            showPipEnabled = !showPipEnabled
            updateVideoDisplay()
        }

        redButton.setOnClickListener {
            Log.d(this, "changePipButton.setOnClickListener()")
            showPipEnabled = !showPipEnabled
            updateVideoDisplay()
        }

        changeUiButton.setOnClickListener {
            uiMode = (uiMode + 1) % 2
            updateControlDisplay()
        }

        toggleCameraButton.setOnClickListener { switchCameraEnabled() }
        speakerphoneButton.setOnClickListener { changeSpeakerphoneMode() }

        if (!settings.pushToTalk) {
            // default behavior
            toggleMicButton.setOnClickListener { switchMicrophoneEnabled() }
        } else {
            toggleMicButton.setOnTouchListener { view: View, event: MotionEvent ->
                Log.d(this, "setOnTouchListener() action=${event.action}")
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // enable microphone
                        if (!currentCall.getMicrophoneEnabled()) {
                            switchMicrophoneEnabled()
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        // disable microphone
                        if (currentCall.getMicrophoneEnabled()) {
                            switchMicrophoneEnabled()
                        }
                    }
                }

                updateMicrophoneIcon()

                view.onTouchEvent(event)
            }
        }

        updateMicrophoneIcon()

        toggleFrontCameraButton.setOnClickListener {
            Log.d(this, "frontFacingSwitch() swappedVideoFeeds=$swappedVideoFeeds, frontCameraEnabled=${currentCall.getFrontCameraEnabled()}}")
            currentCall.switchCamera(
                !currentCall.getFrontCameraEnabled()
            )
        }
    }

    private fun initCall() {
        val settings = DatabaseCache.database.settings

        Log.d(this, "initCall() settings"
            + " microphone ${currentCall.getMicrophoneEnabled()} => ${settings.enableMicrophoneByDefault}"
            + ", camera ${currentCall.getCameraEnabled()} => ${settings.enableCameraByDefault}"
            + ", front camera ${currentCall.getFrontCameraEnabled()} => ${settings.selectFrontCameraByDefault}")

        rtcAudioManager.setEventListener(object : RTCAudioManager.AudioManagerEvents {
            private fun getAudioDeviceName(device: RTCAudioManager.AudioDevice): String {
                return when (device) {
                    RTCAudioManager.AudioDevice.SPEAKER_PHONE -> getString(R.string.audio_device_speakerphone)
                    RTCAudioManager.AudioDevice.WIRED_HEADSET -> getString(R.string.audio_device_wired_headset)
                    RTCAudioManager.AudioDevice.EARPIECE -> getString(R.string.audio_device_earpiece)
                    RTCAudioManager.AudioDevice.BLUETOOTH -> getString(R.string.audio_device_bluetooth)
                }
            }

            override fun onBluetoothConnectPermissionRequired() {
                Log.d(this, "onBluetoothConnectPermissionRequired()")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    allowBluetoothConnectForResult.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    allowBluetoothConnectForResult.launch(Manifest.permission.BLUETOOTH)
                }
            }

            override fun onAudioDeviceChanged(
                oldDevice: RTCAudioManager.AudioDevice,
                newDevice: RTCAudioManager.AudioDevice,
            ) {
                val nameOld = getAudioDeviceName(oldDevice)
                val nameNew = getAudioDeviceName(newDevice)
                if (rtcAudioManager.getSpeakerphoneMode() == RTCAudioManager.SpeakerphoneMode.AUTO) {
                    showTextMessage(
                        String.format(getString(R.string.audio_device_auto), nameNew)
                    )
                } else {
                    showTextMessage(
                        String.format(getString(R.string.audio_device_fixed), nameNew)
                    )
                }
                updateSpeakerphoneIcon()
            }
        })

        val speakerphoneMode = when (val mode = settings.speakerphoneMode) {
            "auto" -> RTCAudioManager.SpeakerphoneMode.AUTO
            //"on" -> RTCAudioManager.SpeakerphoneMode.ON
            "off" -> RTCAudioManager.SpeakerphoneMode.OFF
            else -> {
                Log.w(this, "Invalid speakerphone mode: $mode")
                RTCAudioManager.SpeakerphoneMode.AUTO
            }
        }

        // set initial speakerphone mode
        rtcAudioManager.setSpeakerphoneMode(speakerphoneMode)
        updateSpeakerphoneIcon()
        rtcAudioManager.start()
        setProximitySensorEnabled(!settings.disableProximitySensor)

        toggleMicButton.visibility = VISIBLE
        toggleCameraButton.visibility = VISIBLE
        toggleFrontCameraButton.visibility = GONE
    }

    private fun setProximitySensorEnabled(enabled: Boolean) {
        if (enabled) {
            proximitySensor.addListener(rtcAudioManager::onProximitySensorChangedState)
            proximitySensor.addListener(::onProximitySensorToggleScreen)
            proximitySensor.addListener(::onProximitySensorToggleCamera)
            proximitySensor.start()
        } else {
            proximitySensor.stop()
        }
    }

    // apply settings to camera
    override fun onCameraChanged() {
        val format = captureQualityController.getSelectedFormat()
        val framerate = captureQualityController.getSelectedFramerate()
        val degradation = captureQualityController.getSelectedDegradation()

        currentCall.changeCaptureFormat(degradation, format.width, format.height, framerate)
    }

    // called when the camera is enabled/changed
    override fun onCameraChange(name: String, isFrontFacing: Boolean, formats: List<CameraEnumerationAndroid.CaptureFormat>) {
        runOnUiThread {
            updateVideoDisplay()
            captureQualityController.onCameraChange(name, isFrontFacing, formats)
        }
    }

    fun getCurrentCall(): RTCCall {
        return currentCall
    }

    private fun updateSpeakerphoneIcon() {
        Log.d(this, "updateSpeakerphoneIcon()")

        val mode = rtcAudioManager.getSpeakerphoneMode()
        val device = rtcAudioManager.getAudioDevice()
        // get matching button icon
        val icon = if (device == AudioDevice.BLUETOOTH){
            R.drawable.ic_audio_device_bluetooth
        } else {
            when (mode) {
                RTCAudioManager.SpeakerphoneMode.AUTO -> R.drawable.selector_audio_device_automatic // preferred device
                //RTCAudioManager.SpeakerphoneMode.ON -> R.drawable.ic_audio_device_speakerphone // enforced setting
                RTCAudioManager.SpeakerphoneMode.OFF -> R.drawable.selector_audio_device_phone // enforced setting
            }
        }

        Log.d(this, "updateSpeakerphoneIcon() mode=$mode, device=$device")
        speakerphoneButton.setImageResource(icon)
    }

    private fun changeSpeakerphoneMode() {
        val oldMode = rtcAudioManager.getSpeakerphoneMode()

        // switch to the next speakerphone mode
        val newMode = when (oldMode) {
            RTCAudioManager.SpeakerphoneMode.AUTO -> RTCAudioManager.SpeakerphoneMode.OFF
            //RTCAudioManager.SpeakerphoneMode.OFF -> RTCAudioManager.SpeakerphoneMode.ON
            RTCAudioManager.SpeakerphoneMode.OFF -> RTCAudioManager.SpeakerphoneMode.AUTO
        }

        Log.d(this, "changeSpeakerphoneMode() $oldMode => $newMode")
        rtcAudioManager.setSpeakerphoneMode(newMode)
        updateSpeakerphoneIcon()
    }

    private val allowBluetoothConnectForResult = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        isGranted -> if (isGranted) {
            rtcAudioManager.startBluetooth()
        } else {
            // do not turn on microphone
            showTextMessage(getString(R.string.missing_bluetooth_permission))
        }
        Log.d(this, "allowBluetoothConnectForResult() isGranted=$isGranted")
    }

    private val enabledMicrophoneForResult = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        isGranted -> if (isGranted) {
            switchMicrophoneEnabled()
        } else {
            // do not turn on microphone
            showTextMessage(getString(R.string.missing_microphone_permission))
        }
    }

    private fun switchMicrophoneEnabled() {
        Log.d(this, "switchMicrophoneEnabled()")

        // check permission
        if (!Utils.hasPermission(this, Manifest.permission.RECORD_AUDIO)) {
            enabledMicrophoneForResult.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (!currentCall.getMicrophoneEnabled()) {
            // turn microphone on
            currentCall.setMicrophoneEnabled(true)
        } else {
            // turn microphone off
            currentCall.setMicrophoneEnabled(false)
        }
    }

    private val enabledCameraForResult = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        isGranted -> if (isGranted) {
            switchCameraEnabled()
        } else {
            // do not turn on camera
            showTextMessage(getString(R.string.missing_camera_permission))
        }
    }

    private fun switchCameraEnabled() {
        Log.d(this, "switchCameraEnabled()")

        if (!currentCall.getCameraEnabled()) {
            // check and request permission
            if (!Utils.hasPermission(this, Manifest.permission.CAMERA)) {
                enabledCameraForResult.launch(Manifest.permission.CAMERA)
                return
            }
            // turn camera on
            currentCall.setCameraEnabled(true)
        } else {
            // turn camera off
            currentCall.setCameraEnabled(false)
        }
    }

    override fun onDestroy() {
        Log.d(this, "onDestroy()")
        try {
            isCallInProgress = false
            if(this::currentCall.isInitialized ){
                currentCall.callStatusHandler?.stopCallStatusListening()
                currentCall.cleanup()
                currentCall.releaseCamera()
            }
            if(this::microphoneUsageMonitor.isInitialized){
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    microphoneUsageMonitor.stopMonitoring()
                }
            }
            stopService(Intent(this, CallStatusService::class.java))
            proximitySensor.stop()
            if (callEventType != Event.Type.UNKNOWN) {
                val event = Event(contact.publicKey, contact.lastWorkingAddress, callEventType, Date())
                addEvent(event)
            }
            unbindService(connection)
            proximityScreenLock?.release()
            rtcAudioManager.stop()
            remoteProxyVideoSink.setTarget(null)
            localProxyVideoSink.setTarget(null)
            pipRenderer.release()
            fullscreenRenderer.release()
            eglBase.release()
            callDuration.stop()
        } catch (e: Exception) {
            Log.e(this, "onDestroy() e=$e")
        } finally {
            RTCPeerConnection.incomingRTCCall = null // free for the garbage collector
        }
        val intent = Intent(AppStateReceiver.APP_STATE_INTENT)
        //FIX me. Perhaps the state could be ENABLED
        val state = STATE_CALL_ENDED
        intent.putExtra("state", state)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        super.onDestroy()
    }

    private fun finishDelayed() {
        if (activityActive) {
            activityActive = false
            Handler(mainLooper).postDelayed({ finish() }, 500)
        }
    }

    // turn off/on the camera while the proximity sensor is triggered
    private fun onProximitySensorToggleCamera(isNear: Boolean) {
        Log.d(this, "onProximitySensorToggleCamera() isNear=$isNear")

        if (isNear) {
            if (currentCall.getCameraEnabled() && !currentCall.getFrontCameraEnabled()) {
                currentCall.setCameraEnabled(false)
                proximityCameraWasOn = true
            }
        } else {
            if (proximityCameraWasOn) {
                currentCall.setCameraEnabled(true)
                proximityCameraWasOn = false
            }
        }
    }

    // turn off/on the screen while the proximity sensor is triggered
    private fun onProximitySensorToggleScreen(isProximityNear: Boolean) {
        Log.d(this, "onProximitySensorToggleScreen() isProximityNear=$isProximityNear")

        val powerManager = ServiceUtil.getPowerManager(this)

        proximityScreenLock?.release()

        if (isProximityNear) {
            // turn screen off
            proximityScreenLock = powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "cuplink:tag"
            )
            proximityScreenLock?.acquire(10*60*1000L) // 10 minutes
        } else {
            // turn screen on
            proximityScreenLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "cuplink:tag"
            )
            proximityScreenLock?.acquire(10*60*1000L) // 10 minutes
        }
    }

    companion object {

        @Volatile
        var isCallInProgress: Boolean = false

        fun clearTop(context: Context): Intent {
            val intent = Intent(context, CallActivity::class.java)

            intent.setFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )

            return intent
        }
    }

    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(false)
        }
    }

    override fun onUserLeaveHint() {
        Log.d(this, "onUserLeaveHint")

        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(false)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun enterPictureInPictureMode(launchedByUser: Boolean) {

        if (!ViewUtil.supportsPictureInPicture(this)) {
            return
        }

        val appOpsManager = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        if (appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(),
                packageName
            ) != AppOpsManager.MODE_ALLOWED
        ) {
            return
        }
        uiMode = 1
        updateControlDisplay()
        try {
            val params = ViewUtil.setPictureInPicture(this)
            params?.let { enterPictureInPictureMode(it) }
        } catch (e: IllegalArgumentException) {
            Log.e(this,"Unable to enter PIP mode: ${e.message}" )
            uiMode = 0
            updateControlDisplay()
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)

        if (isInPictureInPictureMode) {
            // Hide the full-screen UI (controls, etc.) while in
            // picture-in-picture mode.
            uiMode = 1
            changeUiButton.visibility = INVISIBLE
            toggleFrontCameraButton.visibility = INVISIBLE
            setPipButtonEnabled(false)
            showPipView(false)
            currentCall.onPiP()
        } else {
            // Restore the full-screen UI.
            uiMode = 0
            changeUiButton.visibility = VISIBLE
            toggleFrontCameraButton.visibility = VISIBLE
            currentCall.onResumeCallActivity()
            Log.d(this,"Unhide navigation")
        }
        updateVideoDisplay()
        updateControlDisplay()
    }
}
