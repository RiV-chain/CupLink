package org.rivchain.cuplink

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.Html
import android.text.InputFilter
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import org.libsodium.jni.NaCl
import org.libsodium.jni.Sodium
import org.rivchain.cuplink.MainService.MainBinder
import org.rivchain.cuplink.rivmesh.ConfigurationProxy
import org.rivchain.cuplink.util.AddressUtils
import org.rivchain.cuplink.util.PermissionManager.haveCameraPermission
import org.rivchain.cuplink.util.PermissionManager.haveMicrophonePermission
import org.rivchain.cuplink.util.PermissionManager.havePostNotificationPermission
import org.rivchain.cuplink.util.Utils
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.regex.Matcher
import java.util.regex.Pattern


/*
 * Show splash screen, name setup dialog, database password dialog and
 * start background service before starting the MainActivity.
 */
class StartActivity// to avoid "class has no zero argument constructor" on some devices
    () : BaseActivity(), ServiceConnection {
    private var binder: MainBinder? = null
    private var dialog : Dialog? = null
    private var startState = 0
    private var isStartOnBootup = false
    private var requestPermissionLauncher: ActivityResultLauncher<String>? = null
    private val POLICY = "policy"
    private var preferences: SharedPreferences? = null

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

        // set by BootUpReceiver
        isStartOnBootup = intent.getBooleanExtra(BootUpReceiver.IS_START_ON_BOOTUP, false)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext);
        val type = Typeface.createFromAsset(assets, "rounds_black.otf")
        findViewById<TextView>(R.id.splashText).typeface = type

        requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean? ->
                continueInit()
            }

        Log.d(this, "Show privacy policy")
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

    private fun continueInit() {
        startState += 1
        when (startState) {
            1 -> {
                Log.d(this, "init 1: check addresses")
                if (binder!!.getService().firstStart) {
                    showMissingAddressDialog()
                } else {
                    continueInit()
                }
            }
            2 -> {
                Thread.sleep(1000)
                Log.d(this, "init 2: check database")
                if (!binder!!.isDatabaseLoaded()) {
                    // database is probably encrypted
                    showDatabasePasswordDialog()
                } else {
                    continueInit()
                }
            }
            3 -> {
                Log.d(this, "init 3: check username")
                if (binder!!.getSettings().username.isEmpty()) {
                    // set username
                    showMissingUsernameDialog()
                } else {
                    continueInit()
                }
            }
            4 -> {
                Log.d(this, "init 4: check key pair")
                if (binder!!.getSettings().publicKey.isEmpty()) {
                    // generate key pair
                    initKeyPair()
                }
                continueInit()
            }
            5 -> {
                Log.d(this, "init 5: check notification permissions")
                if (!havePostNotificationPermission(this)) {
                    requestPermissionLauncher!!.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    continueInit()
                }
            }
            6 -> {
                Log.d(this, "init 6: check microphone permissions")
                if (!haveMicrophonePermission(this)) {
                    requestPermissionLauncher!!.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    continueInit()
                }
            }
            7 -> {
                Log.d(this, "init 7: check camera permissions")
                if (!haveCameraPermission(this)) {
                    requestPermissionLauncher!!.launch(Manifest.permission.CAMERA)
                } else {
                    continueInit()
                }
            }
            8 -> {
                Log.d(this, "init 8: start MainActivity")
                val settings = binder!!.getSettings()
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

    private var startVpnActivity = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            bindService(Intent(this, MainService::class.java), this, 0)
            // start MainService and call back via onServiceConnected()
            MainService.startPacketsStream(this)
        }
    }

    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
        Log.d(this, "onServiceConnected")
        binder = iBinder as MainBinder

        if (startState == 0) {
            if (binder!!.getService().firstStart) {
                // show delayed splash page
                Handler(Looper.getMainLooper()).postDelayed({
                    continueInit()
                }, 1000)
            } else {
                // show contact list as fast as possible
                continueInit()
            }
        }
    }

    override fun onServiceDisconnected(componentName: ComponentName) {
        // nothing to do
    }

    override fun onDestroy() {
        dialog?.dismiss()
        unbindService(this)
        super.onDestroy()
    }

    private fun initKeyPair() {
        // create secret/public key pair
        val publicKey = ByteArray(Sodium.crypto_sign_publickeybytes())
        val secretKey = ByteArray(Sodium.crypto_sign_secretkeybytes())
        Sodium.crypto_sign_keypair(publicKey, secretKey)
        val settings = binder!!.getSettings()
        settings.publicKey = publicKey
        settings.secretKey = secretKey
        binder!!.saveDatabase()
    }

    private fun getDefaultAddress(): AddressEntry? {
        val addresses = AddressUtils.collectAddresses()

        // preferable, fc::/7
        val meshAddress = addresses.firstOrNull { it.address.startsWith("fc") }
        if (meshAddress != null) {
            return meshAddress
        }
        // since we can derive a fe80:: and other addresses from a MAC address
        val macAddress = addresses.firstOrNull { it.device.startsWith("wlan") && AddressUtils.isMACAddress(it.address) }
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
            val builder = AlertDialog.Builder(this)
            builder.setTitle(getString(R.string.setup_address))
            builder.setMessage(getString(R.string.setup_no_address_found))
            builder.setPositiveButton(R.string.button_ok) { dialog: DialogInterface, _: Int ->
                showMissingAddressDialog()
                dialog.cancel()
            }
            builder.setNegativeButton(R.string.button_skip) { dialog: DialogInterface, _: Int ->
                dialog.cancel()
                // continue with out address configuration
                continueInit()
            }
            val adialog = builder.create()
            adialog.setCancelable(false)
            adialog.setCanceledOnTouchOutside(false)

            this.dialog?.dismiss()
            this.dialog = adialog

            adialog.show()
        } else {
            binder!!.getSettings().addresses = mutableListOf(defaultAddress.address)
            binder!!.saveDatabase()
            continueInit()
        }
    }

    // initial dialog to set the username
    private fun showMissingUsernameDialog() {
        val tw = TextView(this)
        tw.setText(R.string.startup_prompt_name)
        //tw.setTextColor(Color.BLACK);
        tw.textSize = 20f
        tw.gravity = Gravity.CENTER_HORIZONTAL
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.addView(tw)
        val et = EditText(this)
        et.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        et.filters = arrayOf(getEditTextFilter())
        et.isSingleLine = true
        layout.addView(et)
        layout.setPadding(40, 80, 40, 40)

        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.startup_hello)
        builder.setView(layout)
        builder.setNegativeButton(R.string.button_skip) { dialog: DialogInterface?, _: Int ->
            val username = generateRandomUserName()
            if (Utils.isValidName(username)) {
                binder!!.getSettings().username = username
                binder!!.saveDatabase()
                // close dialog
                dialog?.dismiss()
                continueInit()
            } else {
                Toast.makeText(this, R.string.invalid_name, Toast.LENGTH_SHORT).show()
            }
        }
        builder.setPositiveButton(R.string.button_next) { dialog: DialogInterface?, _: Int ->
            val username = et.text.toString()
            if (Utils.isValidName(username)) {
                binder!!.getSettings().username = username
                binder!!.saveDatabase()
                // close dialog
                dialog?.dismiss()
                continueInit()
            } else {
                Toast.makeText(this, R.string.invalid_name, Toast.LENGTH_SHORT).show()
            }
        }

        val adialog = builder.create()
        adialog.setOnShowListener {
            adialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(resources.getColor(R.color.white))
            adialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(resources.getColor(R.color.white))
        }
        adialog.setCancelable(false)
        adialog.setCanceledOnTouchOutside(false)
        adialog.setOnShowListener { dialog: DialogInterface ->
            val okButton = (dialog as AlertDialog).getButton(
                AlertDialog.BUTTON_POSITIVE
            )
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    charSequence: CharSequence,
                    i: Int,
                    i1: Int,
                    i2: Int,
                ) {
                    // nothing to do
                }

                override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {
                    // nothing to do
                }

                override fun afterTextChanged(editable: Editable) {
                    val ok = Utils.isValidName(editable.toString())
                    okButton.isClickable = ok
                    okButton.alpha = if (ok) 1.0f else 0.5f
                }
            })
            okButton.isClickable = false
            okButton.alpha = 0.5f
        }

        this.dialog?.dismiss()
        this.dialog = adialog

        adialog.show()
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
        val ddialog = Dialog(this)
        ddialog.setContentView(R.layout.dialog_enter_database_password)
        ddialog.setCancelable(false)
        ddialog.setCanceledOnTouchOutside(false)

        val passwordEditText = ddialog.findViewById<EditText>(R.id.change_password_edit_textview)
        val exitButton = ddialog.findViewById<Button>(R.id.change_password_cancel_button)
        val okButton = ddialog.findViewById<Button>(R.id.change_password_ok_button)
        okButton.setOnClickListener {
            val password = passwordEditText.text.toString()
            binder!!.getService().databasePassword = password
            try {
                binder!!.getService().loadDatabase()
            } catch (e: Database.WrongPasswordException) {
                Toast.makeText(this, R.string.wrong_password, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
            }

            if (binder!!.isDatabaseLoaded()) {
                // close dialog
                ddialog.dismiss()
                continueInit()
            }
        }
        exitButton.setOnClickListener {
            // shutdown app
            ddialog.dismiss()
            binder!!.shutdown()
            finish()
        }

        this.dialog?.dismiss()
        this.dialog = ddialog

        ddialog.show()
    }

    companion object {
        // load libsodium for JNI access
        private var sodium = NaCl.sodium()
    }

    private fun generateRandomUserName(): String {
        val user = getString(R.string.startup_name_prefix)
        val id = UUID.randomUUID().toString().substring(0..6)
        return "$user-$id"
    }

    private fun showPolicy(language: String) {
        val view: View = LayoutInflater.from(this).inflate(R.layout.policy_layout, null)
        val msg = view.findViewById<View>(R.id.policy) as TextView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            msg.text = Html.fromHtml(readResourceFile(R.raw.pp_tc), Html.FROM_HTML_OPTION_USE_CSS_COLORS)
        } else {
            msg.text = Html.fromHtml(readResourceFile(R.raw.pp_tc))
        }
        val ab = AlertDialog.Builder(this, R.style.PPTCDialog)
        ab.setTitle("CupLink")
            .setIcon(R.mipmap.ic_launcher)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(
                "Accept"
            ) { _: DialogInterface?, _: Int ->
                preferences!!.edit().putString(POLICY, "accepted").apply()
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
        ab.show()
    }

    private fun readResourceFile(id: Int): String {
        val inputStream = getResources().openRawResource(id)
        val byteArrayOutputStream = ByteArrayOutputStream()
        var i: Int
        try {
            i = inputStream.read()
            while (i != -1) {
                byteArrayOutputStream.write(i)
                i = inputStream.read()
            }
            inputStream.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return byteArrayOutputStream.toString()
    }
}