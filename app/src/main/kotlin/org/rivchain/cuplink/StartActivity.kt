package org.rivchain.cuplink

import android.Manifest
import android.app.Dialog
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Typeface
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
import org.rivchain.cuplink.util.PermissionManager.haveCameraPermission
import org.rivchain.cuplink.util.PermissionManager.haveMicrophonePermission
import org.rivchain.cuplink.util.PermissionManager.havePostNotificationPermission
import java.util.UUID
import java.util.regex.Matcher
import java.util.regex.Pattern


/*
 * Show splash screen, name setup dialog, database password dialog and
 * start background service before starting the MainActivity.
 */
class StartActivity : BaseActivity(), ServiceConnection {
    private var binder: MainBinder? = null
    private var dialog : Dialog? = null
    private var startState = 0
    private var isStartOnBootup = false
    private var requestPermissionLauncher: ActivityResultLauncher<String>? = null
    private val POLICY = "policy"
    private var preferences: SharedPreferences? = null


    val pp =
        "<p class=\"western\" align=\"center\" style=\"margin-bottom: 0.14in; line-height: 115%; page-break-inside: avoid; page-break-after: avoid\">\n" +
                "<font size=\"4\" style=\"font-size: 14pt\"><b>Privacy\n" +
                "policy</b></font></p>\n" +
                "<p class=\"western\" style=\"margin-bottom: 0.14in; line-height: 115%\"><font size=\"2\" style=\"font-size: 10pt\"><b>1.\tIntroduction</b></font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">1.1\tWe are committed to\n" +
                "safeguarding the privacy of our EtherWallet application users; in\n" +
                "this policy we explain how we will treat your personal information.</font></p>\n" +
                "<p class=\"western\" style=\"margin-bottom: 0.14in; line-height: 115%\"><font size=\"2\" style=\"font-size: 10pt\"><b>2.\tCredit</b></font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">2.1\tThis document was created\n" +
                "using a template from SEQ Legal (<a href=\"http://www.seqlegal.com/\"><font color=\"#0000ff\"><u>http://www.seqlegal.com</u></font></a>).</font></p>\n" +
                "<p class=\"western\" style=\"margin-bottom: 0.14in; line-height: 115%\"><font size=\"2\" style=\"font-size: 10pt\"><b>3.\tCollecting\n" +
                "personal information</b></font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">3.1\tWe may collect, store and\n" +
                "use the following kinds of personal information: </font>\n" +
                "</p>\n" +
                "<p style=\"margin-left: 0.83in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">(a)\tInformation about your\n" +
                "mobile device including your IP address, hardware type, operating\n" +
                "system.</font></p>\n" +
                "<p style=\"margin-left: 0.83in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">(b)\tDebug information such as\n" +
                "stack traces.</font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">3.2\tWe never ask for personal\n" +
                "or private information like ID or credit card numbers.</font></p>\n" +
                "<p class=\"western\" style=\"margin-bottom: 0.14in; line-height: 115%\"><font size=\"2\" style=\"font-size: 10pt\"><b>4.\tUsing\n" +
                "personal information</b></font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">4.1\tPersonal information will\n" +
                "be used for the purposes specified in this policy or on the relevant\n" +
                "pages of the website.</font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">4.2\tWe may use your personal\n" +
                "information to: </font>\n" +
                "</p>\n" +
                "<p style=\"margin-left: 0.83in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">(a)\tAdminister our application\n" +
                "and business;</font></p>\n" +
                "<p style=\"margin-left: 0.83in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">(b)\tPersonalize our\n" +
                "application for you;</font></p>\n" +
                "<p style=\"margin-left: 0.83in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">(c)\tEnable your use of the\n" +
                "services available in our application;</font></p>\n" +
                "<p style=\"margin-left: 0.83in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">(d)\tSend statements, invoices\n" +
                "and payment reminders to you, and collect payments from you;</font></p>\n" +
                "<p style=\"margin-left: 0.83in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">(e)\tSend you non-marketing\n" +
                "commercial communications;</font></p>\n" +
                "<p style=\"margin-left: 0.83in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">(f)\tSend you email\n" +
                "notifications that you have specifically requested;</font></p>\n" +
                "<p style=\"margin-left: 0.83in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">(g)\tSend you our email\n" +
                "newsletter, if you have requested it (you can inform us at any time\n" +
                "if you no longer require the newsletter);</font></p>\n" +
                "<p style=\"margin-left: 0.83in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">(h)\tProvide third parties with\n" +
                "statistical information about our users (but those third parties will\n" +
                "not be able to identify any individual user from that information);</font></p>\n" +
                "<p style=\"margin-left: 0.83in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">(i)\tKeep our application\n" +
                "secure and prevent fraud</font></p>\n" +
                "<p style=\"margin-left: 0.83in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<br/>\n" +
                "<br/>\n" +
                "\n" +
                "</p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">4.3\tWe will not, without your\n" +
                "express consent, supply your personal information to any third party\n" +
                "for the purpose of their or any other third party's direct marketing.</font></p>\n" +
                "<p align=\"left\" style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">4.4\tAll our application\n" +
                "financial transactions are handled through Ethereum network service\n" +
                "provider, <a href=\"http://infura.io/\"><i>http://infura.io/</i></a>.\n" +
                "You can review the provider's privacy policy at\n" +
                "<a href=\"http://infura.io/privacy.html\"><i>http://infura.io/privacy.html</i></a>.\n" +
                "We do not share information with our payment services provider even\n" +
                "in case of refunding payments and dealing with complaints and queries\n" +
                "relating to such payments and refunds.</font></p>\n" +
                "<p class=\"western\" style=\"margin-bottom: 0.14in; line-height: 115%\"><font size=\"2\" style=\"font-size: 10pt\"><b>5.\tDisclosing\n" +
                "personal information</b></font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">5.1\tWe may disclose your\n" +
                "personal information: </font>\n" +
                "</p>\n" +
                "<p style=\"margin-left: 0.83in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">(a)\tto the extent that we are\n" +
                "required to do so by law;</font></p>\n" +
                "<p style=\"margin-left: 0.83in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">(b)\tin connection with any\n" +
                "ongoing or prospective legal proceedings;</font></p>\n" +
                "<p style=\"margin-left: 0.83in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">(c)\tin order to establish,\n" +
                "exercise or defend our legal rights (including providing information\n" +
                "to others for the purposes of fraud prevention and reducing credit\n" +
                "risk);</font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">5.2\tExcept as provided in this\n" +
                "policy, we will not provide your personal information to third\n" +
                "parties.</font></p>\n" +
                "<p class=\"western\" style=\"margin-bottom: 0.14in; line-height: 115%\"><font size=\"2\" style=\"font-size: 10pt\"><b>6.\tInternational\n" +
                "walletList transfers</b></font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">6.1\tInformation that we\n" +
                "collect may be stored and processed in and transferred between any of\n" +
                "the countries in which we operate in order to enable us to use the\n" +
                "information in accordance with this policy.</font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">6.2\tPersonal information that\n" +
                "you publish in our application may be available, via the Internet,\n" +
                "around the world. We cannot prevent the use or misuse of such\n" +
                "information by others.</font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">6.3\tYou expressly agree to the\n" +
                "transfers of personal information described in this Section 6.</font></p>\n" +
                "<p class=\"western\" style=\"margin-bottom: 0.14in; line-height: 115%\"><font size=\"2\" style=\"font-size: 10pt\"><b>7.\tRetaining\n" +
                "personal information</b></font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">7.1\tThis Section 7 sets out\n" +
                "our walletList retention policies and procedure, which are designed to help\n" +
                "ensure that we comply with our legal obligations in relation to the\n" +
                "retention and deletion of personal information.</font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">7.2\tPersonal information that\n" +
                "we process for any purpose or purposes shall not be kept for longer\n" +
                "than is necessary for that purpose or those purposes.</font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">7.3\tNotwithstanding the other\n" +
                "provisions of this Section 7, we will retain documents (including\n" +
                "electronic documents) containing personal walletList: </font>\n" +
                "</p>\n" +
                "<p style=\"margin-left: 0.83in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">(a)\tto the extent that we are\n" +
                "required to do so by law;</font></p>\n" +
                "<p style=\"margin-left: 0.83in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">(b)\tif we believe that the\n" +
                "documents may be relevant to any ongoing or prospective legal\n" +
                "proceedings; and</font></p>\n" +
                "<p style=\"margin-left: 0.83in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">(c)\tin order to establish,\n" +
                "exercise or defend our legal rights (including providing information\n" +
                "to others for the purposes of fraud prevention and reducing credit\n" +
                "risk).</font></p>\n" +
                "<p class=\"western\" style=\"margin-bottom: 0.14in; line-height: 115%\"><font size=\"2\" style=\"font-size: 10pt\"><b>8.\tSecurity\n" +
                "of personal information</b></font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">8.1\tWe will take reasonable\n" +
                "technical and organizational precautions to prevent the loss, misuse\n" +
                "or alteration of your personal information.</font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">8.2\tWe will store all the\n" +
                "personal information you provide on our secure (password- and\n" +
                "firewall-protected) servers.</font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">8.3\tAll electronic financial\n" +
                "transactions entered into through our website will be protected by\n" +
                "encryption technology.</font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">8.4\tYou acknowledge that the\n" +
                "transmission of information over the Internet is inherently insecure,\n" +
                "and we cannot guarantee the security of walletList sent over the Internet.</font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">8.5\tYou are responsible for\n" +
                "keeping the password you use for accessing our accounts confidential;\n" +
                "we will not ever ask you for your password.</font></p>\n" +
                "<p class=\"western\" style=\"margin-bottom: 0.14in; line-height: 115%\"><font size=\"2\" style=\"font-size: 10pt\"><b>9.\tAmendments</b></font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">9.1\tWe may update this policy\n" +
                "from time to time by publishing a new version on our website.</font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">9.2\tYou should check this page\n" +
                "occasionally to ensure you are happy with any changes to this policy.</font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">9.3\tWe may notify you of\n" +
                "changes to this policy by email or through the private messaging\n" +
                "system on our website.</font></p>\n" +
                "<p class=\"western\" style=\"margin-bottom: 0.14in; line-height: 115%\"><font size=\"2\" style=\"font-size: 10pt\"><b>10.\tYour\n" +
                "rights</b></font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">10.1\tYou may instruct us to\n" +
                "provide you with any personal information we hold about you;\n" +
                "provision of such information will be subject to: </font>\n" +
                "</p>\n" +
                "<p style=\"margin-left: 0.83in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">(a)\tthe payment of a fee\n" +
                "(currently fixed at GBP 50); and</font></p>\n" +
                "<p style=\"margin-left: 0.83in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">(b)\tthe supply of appropriate\n" +
                "evidence of your identity [(for this purpose, we will usually accept\n" +
                "a photocopy of your passport certified by a solicitor or bank plus an\n" +
                "original copy of a utility bill showing your current address)].</font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">10.2\tWe may withhold personal\n" +
                "information that you request to the extent permitted by law.</font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">10.3\tYou may instruct us at\n" +
                "any time not to process your personal information for marketing\n" +
                "purposes.</font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">10.4\tIn practice, you will\n" +
                "usually either expressly agree in advance to our use of your personal\n" +
                "information for marketing purposes, or we will provide you with an\n" +
                "opportunity to opt out of the use of your personal information for\n" +
                "marketing purposes.</font></p>\n" +
                "<p class=\"western\" style=\"margin-bottom: 0.14in; line-height: 115%\"><font size=\"2\" style=\"font-size: 10pt\"><b>11.\tThird\n" +
                "party websites</b></font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">11.1\tOur website includes\n" +
                "hyperlinks to, and details of, third party websites.</font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">11.2\tWe have no control over,\n" +
                "and are not responsible for, the privacy policies and practices of\n" +
                "third parties.</font></p>\n" +
                "<p class=\"western\" style=\"margin-bottom: 0.14in; line-height: 115%\"><font size=\"2\" style=\"font-size: 10pt\"><b>12.\tUpdating\n" +
                "information</b></font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">12.1\tPlease let us know if the\n" +
                "personal information that we hold about you needs to be corrected or\n" +
                "updated.</font></p>\n" +
                "<p class=\"western\" style=\"margin-bottom: 0.14in; line-height: 115%\"><font size=\"2\" style=\"font-size: 10pt\"><b>1</b></font><font size=\"2\" style=\"font-size: 10pt\"><b>3</b></font><font size=\"2\" style=\"font-size: 10pt\"><b>.\tOur\n" +
                "details</b></font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">13.1\tThis application and\n" +
                "website is owned and operated by <i>Vadym Vikulin</i>.</font></p>\n" +
                "<p style=\"margin-left: 0.42in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">13.2\tYou can contact me:</font></p>\n" +
                "<p style=\"margin-left: 0.83in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">(a)\tBy email:\n" +
                "vadym.vikulin@gmail.com</font></p>\n" +
                "<p style=\"margin-left: 0.83in; text-indent: -0.42in; margin-bottom: 0.14in; line-height: 115%\">\n" +
                "<font size=\"2\" style=\"font-size: 10pt\">(b)\tBy telephone:\n" +
                "+380980987585</font></p>"

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
        isStartOnBootup = intent.getBooleanExtra(BootUpReceiver.IS_START_ON_BOOTUP, true)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext);
        val type = Typeface.createFromAsset(assets, "rounds_black.otf")
        findViewById<TextView>(R.id.splashText).typeface = type

        // start MainService and call back via onServiceConnected()
        MainService.start(this)

        bindService(Intent(this, MainService::class.java), this, 0)

        requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean? ->
                continueInit()
            }
    }

    private fun continueInit() {
        startState += 1
        when (startState) {
            1 -> {
                Log.d(this, "init 1: load database")
                // open without password
                try {
                    binder!!.getService().loadDatabase()
                } catch (e: Database.WrongPasswordException) {
                    // ignore and continue with initialization,
                    // the password dialog comes on the next startState
                } catch (e: Exception) {
                    Log.e(this, "${e.message}")
                    Toast.makeText(this, "${e.message}", Toast.LENGTH_SHORT).show()
                    finish()
                    return
                }
                continueInit()
            }
            2 -> {
                if(preferences?.getString(POLICY, null) == null) {
                    showPolicy("En-Us");
                } else {
                    continueInit()
                }
            }
            3 -> {
                Log.d(this, "init 2: check database")
                if (!binder!!.isDatabaseLoaded()) {
                    // database is probably encrypted
                    showDatabasePasswordDialog()
                } else {
                    continueInit()
                }
            }
            4 -> {
                Log.d(this, "init 3: check username")
                if (binder!!.getSettings().username.isEmpty()) {
                    // set username
                    showMissingUsernameDialog()
                } else {
                    continueInit()
                }
            }
            5 -> {
                Log.d(this, "init 4: check key pair")
                if (binder!!.getSettings().publicKey.isEmpty()) {
                    // generate key pair
                    initKeyPair()
                }
                continueInit()
            }
            6 -> {
                Log.d(this, "init 5: check addresses")
                if (binder!!.getService().firstStart) {
                    showMissingAddressDialog()
                } else {
                    continueInit()
                }
            }
            7 -> {
                if (!havePostNotificationPermission(this)) {
                    requestPermissionLauncher!!.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    continueInit()
                }
            }
            8 -> {
                if (!haveMicrophonePermission(this)) {
                    requestPermissionLauncher!!.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    continueInit()
                }
            }
            9 -> {
                if (!haveCameraPermission(this)) {
                    requestPermissionLauncher!!.launch(Manifest.permission.CAMERA)
                } else {
                    continueInit()
                }
            }
            10 -> {
                Log.d(this, "init 6: start MainActivity")
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

        // preferable, since we can derive a fe80:: and other addresses from a MAC address
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
        msg.text = Html.fromHtml(pp)
        val ab = AlertDialog.Builder(this)
        ab.setTitle("CupLink")
            .setIcon(R.mipmap.ic_launcher)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(
                "Accept"
            ) { _: DialogInterface?, _: Int ->
                preferences!!.edit().putString(POLICY, "accepted").apply()
                continueInit()
            }
        ab.show()
    }
}