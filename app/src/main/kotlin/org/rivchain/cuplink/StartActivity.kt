package org.rivchain.cuplink

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.InputFilter
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.PreferenceManager
import com.google.android.material.textfield.TextInputEditText
import org.libsodium.jni.Sodium
import org.rivchain.cuplink.MainService.MainBinder
import org.rivchain.cuplink.NotificationUtils.showToastMessage
import org.rivchain.cuplink.model.AddressEntry
import org.rivchain.cuplink.rivmesh.AutoSelectPeerActivity
import org.rivchain.cuplink.rivmesh.AutoTestPublicPeerActivity
import org.rivchain.cuplink.rivmesh.SelectPeerActivity
import org.rivchain.cuplink.util.Log
import org.rivchain.cuplink.util.NetworkUtils
import org.rivchain.cuplink.util.PermissionManager.haveCameraPermission
import org.rivchain.cuplink.util.PermissionManager.haveMicrophonePermission
import org.rivchain.cuplink.util.PermissionManager.havePostNotificationPermission
import org.rivchain.cuplink.util.Utils
import java.util.regex.Matcher
import java.util.regex.Pattern

/*
 * Show splash screen, name setup dialog, database password dialog and
 * start background service before starting the MainActivity.
 */
class StartActivity// to avoid "class has no zero argument constructor" on some devices
    () : BaseActivity(), ServiceConnection {
    private var service: MainService? = null
    private var dialog : Dialog? = null
    private var startState = 0
    private var isStartOnBootup = false
    private var requestPermissionLauncher: ActivityResultLauncher<Array<String>>? = null
    private var requestPeersLauncher: ActivityResultLauncher<Intent>? = null
    private var requestListenLauncher: ActivityResultLauncher<Intent>? = null
    private val POLICY = "policy"
    private val PEERS = "peers"
    private val LISTEN = "listen"
    private var preferences: SharedPreferences? = null
    private var restartService = false
    private var isServiceBound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(this, "onCreate() CupLink version ${BuildConfig.VERSION_NAME}")
        Log.d(this, "Android SDK: ${Build.VERSION.SDK_INT}, "
                    + "Release: ${Build.VERSION.RELEASE}, "
                    + "Brand: ${Build.BRAND}, "
                    + "Device: ${Build.DEVICE}, "
                    + "Id: ${Build.ID}, "
                    + "Hardware: ${Build.HARDWARE}, "
                    + "Manufacturer: ${Build.MANUFACTURER}, "
                    + "Model: ${Build.MODEL}, "
                    + "Product: ${Build.PRODUCT}"
        )
        super.onCreate(savedInstanceState)
        // set by BootUpReceiver
        isStartOnBootup = intent.getBooleanExtra(BootUpReceiver.IS_START_ON_BOOTUP, false)
        setContentView(R.layout.activity_empty)
        preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)

        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result: Map<String, Boolean> ->
            continueInit()
        }
        requestPeersLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                preferences!!.edit().putString(PEERS, "done").apply()
                continueInit()
            }
        requestListenLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                preferences!!.edit().putString(LISTEN, "done").apply()
                continueInit()
            }
        continueInit()
    }

    private fun continueInit() {
        startState += 1
        when (startState) {
            1 -> {
                Log.d(this, "init $startState: show policy and start VPN")
                if(preferences?.getString(POLICY, null) == null) {
                    showPolicy("En-Us")
                } else {
                    Log.d(this, "Start VPN")
                    val vpnIntent = VpnService.prepare(this)
                    if (vpnIntent != null) {
                        startVpnActivity.launch(vpnIntent)
                    } else {
                        bindService(Intent(this, MainService::class.java), this, 0)
                        // start MainService and call back via onServiceConnected()
                        MainService.startPacketsStream(this)
                    }
                }
            }
            2 -> {
                Log.d(this, "init $startState: choose peers")
                if(preferences?.getString(PEERS, null) == null) {
                    val intent = Intent(this, AutoSelectPeerActivity::class.java)
                    intent.putStringArrayListExtra(
                        SelectPeerActivity.PEER_LIST,
                        org.rivchain.cuplink.rivmesh.util.Utils.serializePeerInfoSet2StringList(
                            setOf()
                        )
                    )
                    requestPeersLauncher!!.launch(intent)
                    restartService = true
                } else {
                    continueInit()
                }
            }
            3 -> {
                Log.d(this, "init $startState: check database")
                if (isDatabaseEncrypted()) {
                    // database is probably encrypted
                    showDatabasePasswordDialog()
                } else {
                    continueInit()
                }
            }
            4 -> {
                Log.d(this, "init $startState: check username")
                if (DatabaseCache.database.settings.username.isEmpty()) {
                    // set username
                    showMissingUsernameDialog()
                } else {
                    continueInit()
                }
            }
            5 -> {
                Log.d(this, "init $startState: check addresses")
                if (DatabaseCache.firstStart) {
                    showMissingAddressDialog()
                } else {
                    continueInit()
                }
            }
            6 -> {
                Log.d(this, "init $startState: check key pair")
                if (DatabaseCache.database.settings.publicKey.isEmpty()) {
                    // generate key pair
                    initKeyPair()
                }
                continueInit()
            }
            7 -> {
                Log.d(this, "init $startState: test port")
                if(preferences?.getString(LISTEN, null) == null) {
                    val intent = Intent(this, AutoTestPublicPeerActivity::class.java)
                    requestListenLauncher!!.launch(intent)
                } else {
                    continueInit()
                }
            }
            8 -> {
                Log.d(this, "init $startState: check all permissions")
                if (!havePostNotificationPermission(this) ||
                    !haveMicrophonePermission(this) ||
                    !haveCameraPermission(this)
                    ) {
                    requestPermissionLauncher!!.launch(
                        arrayOf(
                            Manifest.permission.POST_NOTIFICATIONS,
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.CAMERA)
                    )
                } else {
                    continueInit()
                }
            }
            9 -> {
                // All persistent settings must be set up prior this step!
                Log.d(this, "init $startState: restart main service if needed")
                if(restartService) {
                    restartService()
                } else {
                    continueInit()
                }
            }
            10 -> {
                Log.d(this, "init $startState: start MainActivity")
                val settings = DatabaseCache.database.settings
                // set in case we just updated the app
                BootUpReceiver.setEnabled(this, settings.startOnBootup)
                // set night mode
                setDefaultNightMode(settings.nightMode)
                if (!isStartOnBootup) {
                    startActivity(Intent(this, MainActivity::class.java))
                }
                finish()
            }
        }
    }

    override fun onServiceRestart() {
        continueInit()
    }

    private var startVpnActivity = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            bindService(Intent(this, MainService::class.java), this, 0)
            // start MainService and call back via onServiceConnected()
            MainService.startPacketsStream(this)
        }
    }

    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
        Log.d(this, "onServiceConnected")
        service = (iBinder as MainBinder).getService()
        isServiceBound = true

        if (startState == 1) {
            setContentView(R.layout.activity_splash)
            findViewById<TextView>(R.id.splashText).text = "CupLink v${BuildConfig.VERSION_NAME} © 2024 RiV Chain Ltd"
            if (DatabaseCache.firstStart) {
                // show delayed splash page
                continueInit()
            } else {
                // show contact list as fast as possible
                continueInit()
            }
        }
    }

    override fun onServiceDisconnected(componentName: ComponentName) {
        // nothing to do
        isServiceBound = false
    }

    override fun onDestroy() {
        dialog?.dismiss()
        if (isServiceBound) {
            unbindService(this)
            isServiceBound = false
        }
        super.onDestroy()
    }

    private fun initKeyPair() {
        // create secret/public key pair
        val publicKey = ByteArray(Sodium.crypto_sign_publickeybytes())
        val secretKey = ByteArray(Sodium.crypto_sign_secretkeybytes())
        Sodium.crypto_sign_keypair(publicKey, secretKey)
        val settings = DatabaseCache.database.settings
        settings.publicKey = publicKey
        settings.secretKey = secretKey
        DatabaseCache.save()
    }

    private fun getDefaultAddress(): AddressEntry? {
        val addresses = NetworkUtils.collectAddresses()

        // preferable, fc::/7
        val meshAddress = addresses.firstOrNull { it.address.startsWith("fc") }
        if (meshAddress != null) {
            return meshAddress
        }
        // since we can derive a fe80:: and other addresses from a MAC address
        val macAddress = addresses.firstOrNull { it.device.startsWith("wlan") && NetworkUtils.isMACAddress(it.address) }
        if (macAddress != null) {
            return macAddress
        }

        // non EUI-64 fe80:: address
        val fe80Address = addresses.firstOrNull { it.device.startsWith("wlan") && it.address.startsWith("fe80::") }
        if (fe80Address != null) {
            return fe80Address
        }

        return null
    }

    private fun showMissingAddressDialog() {
        val defaultAddress = getDefaultAddress()
        if (defaultAddress == null) {

            val view: View = LayoutInflater.from(this).inflate(R.layout.dialog_yes_no, null)
            val dialog = this.createBlurredPPTCDialog(view)
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            val titleText = view.findViewById<TextView>(R.id.title)
            titleText.text = getString(R.string.setup_address)
            val messageText = view.findViewById<TextView>(R.id.message)
            messageText.text = getString(R.string.setup_no_address_found)
            val noButton = view.findViewById<Button>(R.id.no)
            val yesButton = view.findViewById<Button>(R.id.yes)
            noButton.text = getString(R.string.button_skip)
            yesButton.text = getString(R.string.button_ok)
            yesButton.setOnClickListener {
                showMissingAddressDialog()
                dialog.cancel()
            }
            noButton.setOnClickListener {
                dialog.cancel()
                // continue with out address configuration
                continueInit()
            }

            this.dialog?.dismiss()
            this.dialog = dialog

            dialog.show()


        } else {
            DatabaseCache.database.settings.addresses = mutableListOf(defaultAddress.address)
            DatabaseCache.save()
            continueInit()
        }
    }

    // initial dialog to set the username
    private fun showMissingUsernameDialog() {
        // Inflate the custom layout
        val dialogView = layoutInflater.inflate(R.layout.dialog_username, null)

        // Get references to the UI components
        val etUsername: EditText = dialogView.findViewById(R.id.et_username)

        // Apply filters and other properties to EditText if needed
        etUsername.filters = arrayOf(getEditTextFilter())
        val username = generateRandomUserName()
        etUsername.hint = username
        // Build the dialog
        val dialog = createBlurredPPTCDialog(dialogView)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        val noButton = dialogView.findViewById<Button>(R.id.CancelButton)
        val yesButton = dialogView.findViewById<Button>(R.id.OkButton)
        yesButton.setOnClickListener {
            if (Utils.isValidName(username)) {
                DatabaseCache.database.settings.username = etUsername.text.toString()
                DatabaseCache.save()
                // close dialog
                dialog.dismiss()
                continueInit()
            } else {
                showToastMessage(this, R.string.invalid_name)
            }
        }
        noButton.setOnClickListener {
            if (Utils.isValidName(username)) {
                DatabaseCache.database.settings.username = username
                DatabaseCache.save()
                // close dialog
                dialog.dismiss()
                continueInit()
            } else {
                showToastMessage(this, R.string.invalid_name)
            }
        }

        dialog.setOnShowListener { dialog: DialogInterface ->

            etUsername.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {
                    // nothing to do
                }

                override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {
                    // nothing to do
                }

                override fun afterTextChanged(editable: Editable) {
                    val ok = Utils.isValidName(editable.toString())
                    yesButton.isClickable = ok
                    yesButton.alpha = if (ok) 1.0f else 0.5f
                }
            })
            yesButton.isClickable = false
            yesButton.alpha = 0.5f
        }

        this.dialog?.dismiss()
        this.dialog = dialog

        dialog.show()
    }

    private fun getEditTextFilter(): InputFilter {
        return object : InputFilter {
            override fun filter(
                source: CharSequence,
                start: Int,
                end: Int,
                dest: Spanned,
                dstart: Int,
                dend: Int
            ): CharSequence? {
                var keepOriginal = true
                val sb = StringBuilder(end - start)
                for (i in start until end) {
                    val c = source[i]
                    if (isCharAllowed(c)) // put your condition here
                        sb.append(c) else keepOriginal = false
                }
                return if (keepOriginal) null else {
                    if (source is Spanned) {
                        val sp = SpannableString(sb)
                        TextUtils.copySpansFrom(source as Spanned, start, sb.length, null, sp, 0)
                        sp
                    } else {
                        sb
                    }
                }
            }

            private fun isCharAllowed(c: Char): Boolean {
                val ps: Pattern = Pattern.compile("^[A-Za-z0-9-]{1,23}$")
                val ms: Matcher = ps.matcher(c.toString())
                return ms.matches()
            }
        }
    }

    // ask for database password
    private fun showDatabasePasswordDialog() {

        val view: View = LayoutInflater.from(this).inflate(R.layout.dialog_enter_database_password, null)
        val dialog = createBlurredPPTCDialog(view)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        val passwordEditText = view.findViewById<TextInputEditText>(R.id.change_password_edit_textview)
        val exitButton = view.findViewById<Button>(R.id.change_password_cancel_button)
        val okButton = view.findViewById<Button>(R.id.change_password_ok_button)
        okButton.setOnClickListener {
            val password = passwordEditText.text.toString()
            DatabaseCache.databasePassword = password
            try {
                DatabaseCache.load()
                //MainService first run wasn't success due to db encryption
                MainService.startPacketsStream(this)
                // close dialog
                dialog.dismiss()
                // continue
                continueInit()
            } catch (e: Database.WrongPasswordException) {
                showToastMessage(this, R.string.wrong_password)
            } catch (e: Exception) {
                showToastMessage(this, e.message)
            }
        }
        exitButton.setOnClickListener {
            // shutdown app
            dialog.dismiss()
            service!!.shutdown()
            finish()
        }

        this.dialog?.dismiss()
        this.dialog = dialog

        dialog.show()
    }

    private fun generateRandomUserName(): String {
        // Load the string arrays from resources
        val adjectives = this.resources.getStringArray(R.array.adjectives)
        val animals = this.resources.getStringArray(R.array.animals)

        // Pick a random adjective and a random animal
        val randomAdjective = adjectives.random()
        val randomAnimal = animals.random()

        // Return the concatenated result
        return "$randomAdjective$randomAnimal"
    }

    private var requestTcActivityLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(preferences?.getString(POLICY, null) == null){
            finish()
        } else {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                startVpnActivity.launch(vpnIntent)
            } else {
                bindService(Intent(this, MainService::class.java), this, 0)
                // start MainService and call back via onServiceConnected()
                MainService.startPacketsStream(this)
            }
        }
    }

    private fun showPolicy(language: String) {
        val intent = Intent(this, TcActivity::class.java)
        requestTcActivityLauncher.launch(intent)
    }
}