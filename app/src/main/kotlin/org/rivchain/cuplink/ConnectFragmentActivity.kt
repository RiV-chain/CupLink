package org.rivchain.cuplink

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.rivchain.cuplink.listener.OnSwipeTouchListener
import org.rivchain.cuplink.model.Contact
import org.rivchain.cuplink.util.RlpUtils
import org.rivchain.cuplink.util.Utils

open class ConnectFragmentActivity : AddContactActivity(), BarcodeCallback {
    private lateinit var publicKey: ByteArray
    private lateinit var barcodeView: DecoratedBarcodeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)

        if(intent == null || intent.extras == null || intent.extras?.get("EXTRA_CONTACT_PUBLICKEY") == null){
            Toast.makeText(this, R.string.contact_deeplink_invalid, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Inflate the layout for this Activity
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

        publicKey = intent.extras?.get("EXTRA_CONTACT_PUBLICKEY") as ByteArray

        title = getString(R.string.title_scan_qr_code)

        barcodeView = findViewById(R.id.barcodeScannerView)
        barcodeView.setStatusText(null)

        findViewById<View>(R.id.fabShare).setOnClickListener {
            try {
                val contact = getContactOrOwn(publicKey)!!
                lifecycleScope.launch {
                    val data = withContext(Dispatchers.Default) {
                        RlpUtils.generateLink(contact)
                    }
                    val i = Intent(Intent.ACTION_SEND).apply {
                        putExtra(Intent.EXTRA_TEXT, data)
                        type = "text/plain"
                    }
                    startActivity(i)
                }
                finish()
            } catch (e: Exception) {
                // ignore
            }
        }

        try {
            val contact = getContactOrOwn(publicKey)!!
            CoroutineScope(Dispatchers.Main).launch {
                // Directly call the suspending function
                generateDeepLinkQR(contact)
            }
        } catch (e: NullPointerException) {
            e.printStackTrace()
            Toast.makeText(this, "NPE", Toast.LENGTH_LONG).show()
        } catch (e: Exception){
            e.printStackTrace()
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            finish()
        }

        // manual input button
        findViewById<View>(R.id.manualInput).setOnClickListener { startManualInput() }

        if (!Utils.hasPermission(this, Manifest.permission.CAMERA)) {
            enabledCameraForResult.launch(Manifest.permission.CAMERA)
        }
        if (Utils.hasPermission(this, Manifest.permission.CAMERA)) {
            initCamera()
        }
    }

    override fun startManualInput() {
        pause()
        val et = findViewById<TextInputLayout>(R.id.EditLayout)
        if (et.visibility == View.VISIBLE) {
            findViewById<View>(R.id.manualInput).isSelected = false
            et.visibility = View.INVISIBLE
            // do add a contact
            val contact = findViewById<TextInputEditText>(R.id.editTextInput)
            try {
                val data = contact.text.toString()
                if(data.isEmpty()){
                    et.visibility = View.INVISIBLE
                    return
                }
                addContact(data)
            } catch (e: JSONException) {
                e.printStackTrace()
                contact.error = getString(R.string.invalid_qr_code_data)
                Toast.makeText(this, R.string.invalid_qr_code_data, Toast.LENGTH_SHORT).show()
                et.visibility = View.VISIBLE
            }
        } else {
            et.visibility = View.VISIBLE
            // Change the fabManualInput icon
            // Wait for an input data
            findViewById<View>(R.id.manualInput).isSelected = true
        }
    }

    private val enabledCameraForResult = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            isGranted ->
        if (isGranted) {
            initCamera()
        } else {
            Toast.makeText(this, R.string.missing_camera_permission, Toast.LENGTH_LONG).show()
            // no finish() in case no camera access wanted but contact data pasted
        }
    }

    override fun barcodeResult(result: BarcodeResult) {
        // no more scan until result is processed
        try {
            super.addContact(result.text)
        } catch (e: JSONException) {
            e.printStackTrace()
            Toast.makeText(this, R.string.invalid_qr, Toast.LENGTH_LONG).show()
        }
    }

    override fun possibleResultPoints(resultPoints: List<ResultPoint>) {
        // ignore
    }

    private fun initCamera() {
        val formats = listOf(BarcodeFormat.QR_CODE)
        barcodeView.barcodeView?.decoderFactory = DefaultDecoderFactory(formats)
        barcodeView.decodeContinuous(this)
        barcodeView.resume()
    }

    override fun pause(){
        barcodeView.pause()
    }

    override fun resume(){
        barcodeView.resume()
    }

    private suspend fun generateDeepLinkQR(contact: Contact) = withContext(Dispatchers.IO) {
        // Validate contact
        val validationError = validateContact(contact)
        if (validationError != null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ConnectFragmentActivity, validationError, Toast.LENGTH_SHORT).show()
            }
            return@withContext
        }

        try {
            // Generate the QR code
            val data = RlpUtils.generateLink(contact) ?: throw IllegalArgumentException(getString(R.string.contact_is_invalid))
            val multiFormatWriter = MultiFormatWriter()
            val bitMatrix = multiFormatWriter.encode(data, BarcodeFormat.QR_CODE, 1080, 1080)
            val barcodeEncoder = BarcodeEncoder()
            val bitmap = barcodeEncoder.createBitmap(bitMatrix)

            // Update UI with the generated QR code
            withContext(Dispatchers.Main) {
                val qrCode = findViewById<ImageView>(R.id.QRView)
                qrCode.setImageBitmap(bitmap)
                val listener = ConnectOnSwipeTouchListener(this@ConnectFragmentActivity)
                qrCode.setOnTouchListener(listener)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ConnectFragmentActivity, e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun validateContact(contact: Contact): String? {
        return when {
            contact.addresses.isEmpty() -> getString(R.string.contact_has_no_address_warning)
            contact.name.isEmpty() -> getString(R.string.contact_name_invalid)
            contact.publicKey.isEmpty() -> getString(R.string.contact_deeplink_invalid)
            else -> null
        }
    }

    class ConnectOnSwipeTouchListener(private val activity: ConnectFragmentActivity) : OnSwipeTouchListener(activity){
        override fun onSwipeTop() {

        }

        override fun onSwipeRight() {
            activity.finish()
        }

        override fun onSwipeLeft() {

        }

        override fun onSwipeBottom() {

        }
    }

    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        val et = findViewById<TextInputLayout>(R.id.EditLayout)
        if (et.visibility == View.INVISIBLE) {
            // If already on the first page, exit the activity
            super.onBackPressedDispatcher.onBackPressed()
            finish()
        } else {
            et.visibility = View.INVISIBLE
            resume()
        }
    }
}
