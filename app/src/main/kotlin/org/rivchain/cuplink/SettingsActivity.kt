package org.rivchain.cuplink

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import org.json.JSONArray
import org.rivchain.cuplink.DatabaseCache.Companion.database
import org.rivchain.cuplink.NotificationUtils.showToastMessage
import org.rivchain.cuplink.renderer.DescriptiveTextView
import org.rivchain.cuplink.rivmesh.ConfigurePublicPeerActivity
import org.rivchain.cuplink.rivmesh.PeerListActivity
import org.rivchain.cuplink.rivmesh.SelectPeerActivity.Companion.PEER_LIST
import org.rivchain.cuplink.rivmesh.models.PeerInfo
import org.rivchain.cuplink.rivmesh.util.Utils.serializePeerInfoSet2StringList
import org.rivchain.cuplink.util.Log
import org.rivchain.cuplink.util.ServiceUtil
import org.rivchain.cuplink.util.Utils
import java.lang.Integer.parseInt

class SettingsActivity : BaseActivity() {

    private var requestListenLauncher: ActivityResultLauncher<Intent>? = null
    private var requestPeersLauncher: ActivityResultLauncher<Intent>? = null

    private var currentPeers = setOf<PeerInfo>()

    companion object {
        // not stored in the database
        private var settingsMode = "basic"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setTitle(R.string.title_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.apply {
            setNavigationOnClickListener {
                finish()
            }
        }

        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(false)
        }

        requestPeersLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
                if (result.resultCode == RESULT_OK) {
                    //nothing todo
                }
            }
        requestListenLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
                findViewById<SwitchMaterial>(R.id.publicPeerLayoutSwitch).isChecked = result.resultCode == RESULT_OK
                val publicPeerUrl = findViewById<DescriptiveTextView>(R.id.publicPeerLayout)
                publicPeerUrl.subtitleTextView.text = jsonArrayToString(database.mesh.getListen())
                // refresh settings
                DatabaseCache.save()
                restartService()
            }
        initViews()
    }

    private fun showClearEventsDialog() {
        Log.d(this, "showClearEventsDialog()")

        val view: View = LayoutInflater.from(this).inflate(R.layout.dialog_yes_no, null)
        val dialog = this.createBlurredPPTCDialog(view)
        dialog.setCancelable(false)
        val titleText = view.findViewById<TextView>(R.id.title)
        titleText.text = getString(R.string.clear_history)
        val messageText = view.findViewById<TextView>(R.id.message)
        messageText.text = getString(R.string.remove_all_events)
        val noButton = view.findViewById<Button>(R.id.no)
        val yesButton = view.findViewById<Button>(R.id.yes)
        yesButton.setOnClickListener {
            this.clearEvents()
            DatabaseCache.save()
            showToastMessage(this@SettingsActivity,  R.string.done)
            dialog.cancel()
        }
        noButton.setOnClickListener {
            dialog.cancel()
        }
        dialog.show()
    }

    private fun initViews() {

        val settings = database.settings

        findViewById<View>(R.id.about)
            .setOnClickListener {
                startActivity(Intent(this, AboutActivity::class.java))
            }

        findViewById<View>(R.id.backup)
            .setOnClickListener {
                startActivity(Intent(this, BackupActivity::class.java))
            }
        findViewById<View>(R.id.clearHistory)
            .setOnClickListener {
                showClearEventsDialog()
        }

        findViewById<View>(R.id.exit)
            .setOnClickListener {
                MainService.stopPacketsStream(this)
                finishAffinity()
            }

        findViewById<DescriptiveTextView>(R.id.settingsNickname)
            .subtitleTextView.text = settings.username.ifEmpty { getString(R.string.no_value) }
        findViewById<View>(R.id.settingsNickname)
            .setOnClickListener { showChangeUsernameDialog() }

        findViewById<DescriptiveTextView>(R.id.addressLayout)
            .subtitleTextView.text = if (settings.addresses.isEmpty()) getString(R.string.no_value) else settings.addresses.joinToString()
        findViewById<View>(R.id.addressLayout)
            .setOnClickListener {
                startActivity(Intent(this@SettingsActivity, AddressManagementActivity::class.java))
            }

        val peersNumber = findViewById<TextView>(R.id.configuredPeers)
        peersNumber.text = DatabaseCache.database.mesh.getPeers().length().toString()
        peersNumber.setOnClickListener {
            val intent = Intent(this@SettingsActivity, PeerListActivity::class.java)
            intent.putStringArrayListExtra(PEER_LIST, serializePeerInfoSet2StringList(currentPeers))
            requestPeersLauncher!!.launch(intent)
        }

        val databasePassword = DatabaseCache.databasePassword
        findViewById<TextView>(R.id.databasePasswordTv)
            .text = if (databasePassword.isEmpty()) getString(R.string.no_value) else "*".repeat(databasePassword.length)
        findViewById<View>(R.id.databasePasswordLayout)
            .setOnClickListener { showDatabasePasswordDialog() }

        findViewById<DescriptiveTextView>(R.id.publicKeyLayout)
            .subtitleTextView.text = Utils.byteArrayToHexString(settings.publicKey)
        findViewById<View>(R.id.publicKeyLayout)
            .setOnClickListener {
                showToastMessage(this@SettingsActivity, R.string.setting_read_only)
            }

        val publicPeerUrl = findViewById<DescriptiveTextView>(R.id.publicPeerLayout)
        val url = jsonArrayToString(database.mesh.getListen())
        if(url.isNotEmpty()) {
            publicPeerUrl.subtitleTextView.text = url
        }

        findViewById<SwitchMaterial>(R.id.blockUnknownSwitch).apply {
            isChecked = settings.blockUnknown
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                settings.blockUnknown = isChecked
                DatabaseCache.save()
            }
        }

        setupRadioDialog(settings.nightMode,
            R.id.settingsNightModes,
            R.id.spinnerNightModes,
            R.array.nightModeLabels,
            R.array.nightModeValues,
            object : SpinnerItemSelected {
                override fun call(newValue: String?) {
                    if (newValue != null) {
                        settings.nightMode = newValue
                        DatabaseCache.save()
                        setDefaultNightMode(newValue)
                        applyNightMode()
                        startActivity(Intent(this@SettingsActivity, SettingsActivity::class.java))
                        finish()
                    }
                }
            })

        setupRadioDialog(settings.speakerphoneMode,
            R.id.textSpeakerphoneModes,
            R.id.spinnerSpeakerphoneModes,
            R.array.speakerphoneModeLabels,
            R.array.speakerphoneModeValues,
            object : SpinnerItemSelected {
                override fun call(newValue: String?) {
                    if (newValue != null) {
                        settings.speakerphoneMode = newValue
                        DatabaseCache.save()
                    }
                }
            })

        findViewById<TextView>(R.id.connectRetriesTv)
            .text = "${settings.connectRetries}"
        findViewById<View>(R.id.connectRetriesLayout)
            .setOnClickListener { showChangeConnectRetriesDialog() }

        findViewById<TextView>(R.id.connectTimeoutTv)
            .text = "${settings.connectTimeout}"
        findViewById<View>(R.id.connectTimeoutLayout)
            .setOnClickListener { showChangeConnectTimeoutDialog() }

        findViewById<SwitchMaterial>(R.id.exclusiveCallModeSwitch).apply {
            isChecked = settings.promptOutgoingCalls
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                settings.promptOutgoingCalls = isChecked
                DatabaseCache.save()
            }
        }

        /*
        findViewById<SwitchMaterial>(R.id.disableCallHistorySwitch).apply {
            isChecked = settings.disableCallHistory
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                settings.disableCallHistory = isChecked
                if (isChecked) {
                    binder.clearEvents()
                }
                Load.saveDatabase()
            }
        }*/

        findViewById<SwitchMaterial>(R.id.startOnBootupSwitch).apply {
            isChecked = settings.startOnBootup
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                settings.startOnBootup = isChecked
                BootUpReceiver.setEnabled(this@SettingsActivity, isChecked) // apply setting
                DatabaseCache.save()
            }
        }

        findViewById<SwitchMaterial>(R.id.searchMulticastPeersSwitch).apply {
            isChecked = database.mesh.multicastListen
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                database.mesh.multicastListen = isChecked
                DatabaseCache.save()
                restartService()
            }
        }

        findViewById<SwitchMaterial>(R.id.discoverableOverMulticastSwitch).apply {
            isChecked = database.mesh.multicastBeacon
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                database.mesh.multicastBeacon = isChecked
                DatabaseCache.save()
                restartService()
            }
        }

        findViewById<SwitchMaterial>(R.id.pushToTalkSwitch).apply {
            isChecked = settings.pushToTalk
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                settings.pushToTalk = isChecked
                DatabaseCache.save()
            }
        }

        findViewById<SwitchMaterial>(R.id.disableProximitySensorSwitch).apply {
            isChecked = settings.disableProximitySensor
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                settings.disableProximitySensor = isChecked
                DatabaseCache.save()
            }
        }

        setupRadioDialog(settings.videoDegradationMode,
            R.id.videoDegradationModeText,
            R.id.spinnerVideoDegradationModes,
            R.array.videoDegradationModeLabels,
            R.array.videoDegradationModeValues,
            object : SpinnerItemSelected {
                override fun call(newValue: String?) {
                    if (newValue != null) {
                        settings.videoDegradationMode = newValue
                        applyVideoDegradationMode(newValue)
                    }
                }
            })

        setupRadioDialog(settings.cameraResolution,
            R.id.cameraResolutionText,
            R.id.spinnerCameraResolution,
            R.array.cameraResolutionLabels,
            R.array.cameraResolutionValues,
            object : SpinnerItemSelected {
                override fun call(newValue: String?) {
                    if (newValue != null) {
                        settings.cameraResolution = newValue
                    }
                }
            })

        setupRadioDialog(settings.cameraFramerate,
            R.id.cameraFramerateText,
            R.id.spinnerCameraFramerate,
            R.array.cameraFramerateLabels,
            R.array.cameraFramerateValues,
            object : SpinnerItemSelected {
                override fun call(newValue: String?) {
                    if (newValue != null) {
                        settings.cameraFramerate = newValue
                    }
                }
            })

        setupRadioDialog(settingsMode,
            R.id.settingsModes,
            R.id.spinnerSettingsModes,
            R.array.settingsModeLabels,
            R.array.settingsModeValues,
            object : SpinnerItemSelected {
                override fun call(newValue: String?) {
                    if (newValue != null) {
                        settingsMode = newValue
                        applySettingsMode(newValue)
                    }
                }
            })

        findViewById<SwitchMaterial>(R.id.useNeighborTableSwitch).apply {
            isChecked = settings.useNeighborTable
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                settings.useNeighborTable = isChecked
                DatabaseCache.save()
            }
        }

        findViewById<SwitchMaterial>(R.id.videoHardwareAccelerationSwitch).apply {
            isChecked = settings.videoHardwareAcceleration
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                settings.videoHardwareAcceleration = isChecked
                DatabaseCache.save()
            }
        }

        findViewById<SwitchMaterial>(R.id.disableAudioProcessingSwitch).apply {
            isChecked = settings.disableAudioProcessing
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                settings.disableAudioProcessing = isChecked
                DatabaseCache.save()
            }
        }

        findViewById<SwitchMaterial>(R.id.showUsernameAsLogoSwitch).apply {
            isChecked = settings.showUsernameAsLogo
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                settings.showUsernameAsLogo = isChecked
                DatabaseCache.save()
            }
        }

        findViewById<SwitchMaterial>(R.id.publicPeerLayoutSwitch).apply {
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                if (isChecked) {
                    val intent =
                        Intent(this@SettingsActivity, ConfigurePublicPeerActivity::class.java)
                    requestListenLauncher!!.launch(intent)
                } else {
                    database.mesh.setListen(setOf())
                    val disabledColor = Color.parseColor("#d3d3d3")
                    publicPeerUrl.subtitleTextView.setTextColor(disabledColor)
                    DatabaseCache.save()
                    restartService()
                }
            }
        }

        findViewById<SwitchMaterial>(R.id.enableMicrophoneByDefaultSwitch).apply {
            isChecked = settings.enableMicrophoneByDefault
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                settings.enableMicrophoneByDefault = isChecked
                DatabaseCache.save()
            }
        }

        findViewById<SwitchMaterial>(R.id.enableCameraByDefaultSwitch).apply {
            isChecked = settings.enableCameraByDefault
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                settings.enableCameraByDefault = isChecked
                DatabaseCache.save()
            }
        }

        findViewById<SwitchMaterial>(R.id.selectFrontCameraByDefaultSwitch).apply {
            isChecked = settings.selectFrontCameraByDefault
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                settings.selectFrontCameraByDefault = isChecked
                DatabaseCache.save()
            }
        }

        findViewById<SwitchMaterial>(R.id.disableCpuOveruseDetectionSwitch).apply {
            isChecked = settings.disableCpuOveruseDetection
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                settings.disableCpuOveruseDetection = isChecked
                DatabaseCache.save()
            }
        }

        findViewById<SwitchMaterial>(R.id.autoAcceptCallsSwitch).apply {
            isChecked = settings.autoAcceptCalls
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                if(Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this@SettingsActivity) && isChecked) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    requestDrawOverlaysPermissionLauncher.launch(intent)
                } else {
                    settings.autoAcceptCalls = isChecked
                    DatabaseCache.save()
                }
            }
        }

        findViewById<SwitchMaterial>(R.id.automaticStatusUpdatesSwitch).apply {
            isChecked = settings.automaticStatusUpdates
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                settings.automaticStatusUpdates = isChecked
                DatabaseCache.save()
            }
        }

        findViewById<SwitchMaterial>(R.id.cameraOnWhenScreenLockedSwitch).apply {
            isChecked = settings.cameraOnWhenScreenLocked
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                settings.cameraOnWhenScreenLocked = isChecked
                DatabaseCache.save()
            }
        }

        findViewById<SwitchMaterial>(R.id.ignoreBatteryOptimizationsSwitch).apply {
            isChecked = settings.ignoreBatteryOptimizations
            setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                settings.ignoreBatteryOptimizations = isChecked
                DatabaseCache.save()
            }
        }

        val menuPassword = settings.menuPassword
        findViewById<TextView>(R.id.menuPasswordTv)
            .text = if (menuPassword.isEmpty()) getString(R.string.no_value) else "*".repeat(menuPassword.length)
        findViewById<View>(R.id.menuPasswordLayout)
            .setOnClickListener { showMenuPasswordDialog() }

        applySettingsMode(settingsMode)
        applyVideoDegradationMode(settings.videoDegradationMode)

        findViewById<TextView>(R.id.splashText).text = "CupLink v${BuildConfig.VERSION_NAME}"

    }

    fun jsonArrayToString(listen: JSONArray): String {
        val stringBuilder = StringBuilder()

        for (i in 0 until listen.length()) {
            if (i > 0) {
                stringBuilder.append(", ")
            }
            stringBuilder.append(listen.get(i))
        }

        return stringBuilder.toString()
    }

    private var requestDrawOverlaysPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= 23) {
                if (!Settings.canDrawOverlays(this)) {
                    showToastMessage(this, R.string.overlay_permission_missing)
                    showToastMessage(this, R.string.overlay_permission_missing)
                }
            }
        } else {
            DatabaseCache.database.settings.autoAcceptCalls = true
            DatabaseCache.save()
        }
    }

    private fun showChangeUsernameDialog() {
        Log.d(this, "showChangeUsernameDialog()")
        val settings = DatabaseCache.database.settings
        val view: View = LayoutInflater.from(this).inflate(R.layout.dialog_change_name, null)
        val nameEditText = view.findViewById<TextInputEditText>(R.id.NameEditText)
        nameEditText.setText(settings.username, TextView.BufferType.EDITABLE)
        val cancelButton = view.findViewById<Button>(R.id.CancelButton)
        val okButton = view.findViewById<Button>(R.id.OkButton)
        val dialog: AlertDialog = createBlurredPPTCDialog(view)
        okButton.setOnClickListener {
            val newUsername = nameEditText.text.toString().trim { it <= ' ' }
            if (Utils.isValidName(newUsername)) {
                settings.username = newUsername
                DatabaseCache.save()
                initViews()
                dialog.cancel()
            } else {
                nameEditText.error = getString(R.string.invalid_name)
                showToastMessage(this, R.string.invalid_name)
            }
        }
        cancelButton.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showChangeConnectRetriesDialog() {
        val settings = DatabaseCache.database.settings
        val view: View = LayoutInflater.from(this).inflate(R.layout.dialog_change_connect_retries, null)
        val dialog = createBlurredPPTCDialog(view)
        val connectRetriesEditText = view.findViewById<TextView>(R.id.ConnectRetriesEditText)
        val cancelButton = view.findViewById<Button>(R.id.CancelButton)
        val okButton = view.findViewById<Button>(R.id.OkButton)
        connectRetriesEditText.text = "${settings.connectRetries}"
        okButton.setOnClickListener {
            val minValue = 0
            val maxValue = 4
            var connectRetries = -1
            val text = connectRetriesEditText.text.toString()
            try {
                connectRetries = parseInt(text)
            } catch (e: Exception) {
                // ignore
            }

            if (connectRetries in minValue..maxValue) {
                settings.connectRetries = connectRetries
                DatabaseCache.save()
                initViews()
                showToastMessage(this, R.string.done)
            } else {
                val message = String.format(getString(R.string.invalid_number), minValue, maxValue)
                showToastMessage(this, message)
            }

            dialog.cancel()
        }
        cancelButton.setOnClickListener { dialog.cancel() }
        dialog.show()
    }

    private fun showChangeConnectTimeoutDialog() {
        val settings = DatabaseCache.database.settings
        val view: View = LayoutInflater.from(this).inflate(R.layout.dialog_change_connect_timeout, null)
        val dialog = createBlurredPPTCDialog(view)
        val connectTimeoutEditText = view.findViewById<TextView>(R.id.ConnectTimeoutEditText)
        val cancelButton = view.findViewById<Button>(R.id.CancelButton)
        val okButton = view.findViewById<Button>(R.id.OkButton)
        connectTimeoutEditText.text = "${settings.connectTimeout}"
        okButton.setOnClickListener {
            val minValue = 20
            val maxValue = 4000
            var connectTimeout = -1
            val text = connectTimeoutEditText.text.toString()
            try {
                connectTimeout = parseInt(text)
            } catch (e: Exception) {
                // ignore
            }

            if (connectTimeout in minValue..maxValue) {
                settings.connectTimeout = connectTimeout
                DatabaseCache.save()
                initViews()
                showToastMessage(this, R.string.done)
            } else {
                val message = String.format(getString(R.string.invalid_number), minValue, maxValue)
                showToastMessage(this, message)
            }

            dialog.cancel()
        }
        cancelButton.setOnClickListener { dialog.cancel() }
        dialog.show()
    }

    private fun showDatabasePasswordDialog() {
        val view: View = LayoutInflater.from(this).inflate(R.layout.dialog_change_database_password, null)
        val dialog = createBlurredPPTCDialog(view)
        val passwordEditText = view.findViewById<TextInputEditText>(R.id.DatabasePasswordEditText)
        val cancelButton = view.findViewById<Button>(R.id.CancelButton)
        val okButton = view.findViewById<Button>(R.id.OkButton)

        passwordEditText.setText(DatabaseCache.databasePassword)
        okButton.setOnClickListener {
            val newPassword = passwordEditText.text.toString()
            DatabaseCache.databasePassword = newPassword
            DatabaseCache.save()
            showToastMessage(this, R.string.done)
            initViews()
            dialog.cancel()
        }
        cancelButton.setOnClickListener { dialog.cancel() }
        dialog.show()
    }

    private fun showMenuPasswordDialog() {
        val menuPassword = DatabaseCache.database.settings.menuPassword
        val view: View = LayoutInflater.from(this).inflate(R.layout.dialog_change_menu_password, null)
        val dialog = createBlurredPPTCDialog(view)
        val passwordEditText = view.findViewById<TextInputEditText>(R.id.MenuPasswordEditText)
        val cancelButton = view.findViewById<Button>(R.id.CancelButton)
        val okButton = view.findViewById<Button>(R.id.OkButton)
        passwordEditText.setText(menuPassword)
        okButton.setOnClickListener {
            val newPassword = passwordEditText.text.toString()
            DatabaseCache.database.settings.menuPassword = newPassword
            DatabaseCache.save()
            showToastMessage(this, R.string.done)
            initViews()
            dialog.cancel()
        }
        cancelButton.setOnClickListener { dialog.cancel() }
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        initViews()
    }

    // grey out resolution/framerate spinners that are not considered by certain
    // degradation modes. We still allow those two values to be changed though.
    private fun applyVideoDegradationMode(degradation: String) {
        val videoDegradationModeText = findViewById<DescriptiveTextView>(R.id.videoDegradationModeText)
        val cameraResolutionText = findViewById<DescriptiveTextView>(R.id.cameraResolutionText)
        val cameraFramerateText = findViewById<DescriptiveTextView>(R.id.cameraFramerateText)
        val enabledColor = videoDegradationModeText.titleTextView.currentTextColor
        val disabledColor = Color.parseColor("#d3d3d3")

        when (degradation) {
            "Balanced" -> {
                cameraResolutionText.titleTextView.setTextColor(disabledColor)
                cameraFramerateText.titleTextView.setTextColor(disabledColor)
            }
            "Maintain resolution" -> {
                cameraResolutionText.titleTextView.setTextColor(enabledColor)
                cameraFramerateText.titleTextView.setTextColor(disabledColor)
            }
            "Maintain framerate" -> {
                cameraResolutionText.titleTextView.setTextColor(disabledColor)
                cameraFramerateText.titleTextView.setTextColor(enabledColor)
            }
            "Disabled" -> {
                cameraResolutionText.titleTextView.setTextColor(enabledColor)
                cameraFramerateText.titleTextView.setTextColor(enabledColor)
            }
            else -> {
                Log.w(this, "applyVideoDegradationMode() unhandled degradation=$degradation")
            }
        }
    }

    private fun applySettingsMode(settingsMode: String) {
        val basicSettingsLayout = findViewById<View>(R.id.basicSettingsLayout)
        val advancedSettingsLayout = findViewById<View>(R.id.advancedSettingsLayout)
        val expertSettingsLayout = findViewById<View>(R.id.expertSettingsLayout)

        when (settingsMode) {
            "Basic" -> {
                basicSettingsLayout.visibility = View.VISIBLE
                advancedSettingsLayout.visibility = View.GONE
                expertSettingsLayout.visibility = View.GONE
            }
            "Advanced" -> {
                basicSettingsLayout.visibility = View.VISIBLE
                advancedSettingsLayout.visibility = View.VISIBLE
                expertSettingsLayout.visibility = View.GONE
            }
            "Expert" -> {
                basicSettingsLayout.visibility = View.VISIBLE
                advancedSettingsLayout.visibility = View.VISIBLE
                expertSettingsLayout.visibility = View.VISIBLE
            }
            else -> Log.e(this, "Invalid settings mode: $settingsMode")
        }
    }

    private fun getIgnoreBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pMgr = ServiceUtil.getPowerManager(this)
            return pMgr.isIgnoringBatteryOptimizations(this.packageName)
        }
        return false
    }

    private interface SpinnerItemSelected {
        fun call(newValue: String?)
    }

    private fun setupRadioDialog(
        currentValue: String,
        titleTextViewId: Int,
        inputTextViewId: Int,
        arrayId: Int,
        arrayValuesId: Int,
        callback: SpinnerItemSelected
    ) {
        val arrayValues = resources.getStringArray(arrayValuesId)
        val arrayLabels = resources.getStringArray(arrayId)

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_select_one_radio, null)
        val radioGroup = dialogView.findViewById<RadioGroup>(R.id.radioGroupNightModes)
        val titleTextView = dialogView.findViewById<TextView>(R.id.selectDialogTitle)
        val textViewId = findViewById<View>(titleTextViewId)
        if(textViewId is TextView){
            titleTextView.text = textViewId.text
        }
        if(textViewId is DescriptiveTextView){
            titleTextView.text = textViewId.titleTextView.text
        }
        val autoCompleteTextView = findViewById<MaterialTextView>(inputTextViewId)
        autoCompleteTextView.text = currentValue

        arrayLabels.forEachIndexed { index, label ->
            val radioButton = RadioButton(this).apply {
                text = label
                id = index
                layoutParams = RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = resources.getDimensionPixelSize(R.dimen.radio_button_margin_bottom)
                }

                if (arrayValues[index] == currentValue) {
                    isChecked = true
                    setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.light_light_grey))
                } else {
                    setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.light_grey))
                }
            }
            radioGroup.addView(radioButton)
        }

        val dialog = createBlurredPPTCDialog(dialogView)

        radioGroup.setOnCheckedChangeListener { group, checkedId ->
            if (checkedId >= 0 && checkedId < arrayValues.size) {
                val selectedValue = arrayValues[checkedId]
                callback.call(selectedValue)
                autoCompleteTextView.text = selectedValue
                dialog.dismiss()
            }
        }

        dialog.setOnDismissListener {
            val selectedId = radioGroup.checkedRadioButtonId
            if (selectedId >= 0 && selectedId < arrayValues.size) {
                val selectedValue = arrayValues[selectedId]
                callback.call(selectedValue)
                autoCompleteTextView.text = selectedValue
            }
        }

        textViewId.setOnClickListener {
            dialog.show()
        }
        autoCompleteTextView.setOnClickListener {
            dialog.show()
        }
    }
}
