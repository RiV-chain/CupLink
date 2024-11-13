package org.rivchain.cuplink

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.widget.ImageView

import androidx.activity.result.contract.ActivityResultContracts
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
import org.json.JSONException
import org.rivchain.cuplink.NotificationUtils.showToastMessage
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
            showToastMessage(this, R.string.contact_public_key_invalid)
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
                Thread {
                    val data = RlpUtils.generateLink(contact)
                    val i = Intent(Intent.ACTION_SEND)
                    i.putExtra(Intent.EXTRA_TEXT, data)
                    i.type = "text/plain"
                    startActivity(i)
                }.start()
                finish()
            } catch (e: Exception) {
                // ignore
            }
        }

        try {
            val contact = getContactOrOwn(publicKey)!!
            Thread {
                generateDeepLinkQR(contact)
            }.start()
        } catch (e: NullPointerException) {
            e.printStackTrace()
            showToastMessage(this, "NPE")
        } catch (e: Exception){
            e.printStackTrace()
            showToastMessage(this, e.message)
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
                showToastMessage(this, R.string.invalid_qr_code_data)
                et.visibility = View.VISIBLE
            }
        } else {
            et.visibility = View.VISIBLE
            // Change the fabManualInput icon
            // Wait for an input data
        }
    }

    private val enabledCameraForResult = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            isGranted ->
        if (isGranted) {
            initCamera()
        } else {
            showToastMessage(this, R.string.missing_camera_permission)
            // no finish() in case no camera access wanted but contact data pasted
        }
    }

    override fun barcodeResult(result: BarcodeResult) {
        // no more scan until result is processed
        try {
            super.addContact(result.text)
        } catch (e: JSONException) {
            e.printStackTrace()
            showToastMessage(this, R.string.invalid_qr)
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

    private fun generateDeepLinkQR(contact: Contact) {

        val data = RlpUtils.generateLink(contact)
        if(data == null){
            showToastMessage(this, R.string.contact_is_invalid)
        }
        if (contact.addresses.isEmpty()) {
            showToastMessage(this, R.string.contact_has_no_address_warning)
        }
        if (contact.name.isEmpty()) {
            showToastMessage(this, R.string.contact_name_invalid)
        }
        if (contact.publicKey.isEmpty()) {
            showToastMessage(this, R.string.contact_public_key_invalid)
        }
        val multiFormatWriter = MultiFormatWriter()
        val bitMatrix = multiFormatWriter.encode(data, BarcodeFormat.QR_CODE, 1080, 1080)
        val barcodeEncoder = BarcodeEncoder()
        val bitmap = barcodeEncoder.createBitmap(bitMatrix)
        val qrCode = findViewById<ImageView>(R.id.QRView)
        runOnUiThread {
            qrCode.setImageBitmap(bitmap)
            val listener = ConnectOnSwipeTouchListener(this@ConnectFragmentActivity)
            qrCode.setOnTouchListener(listener)
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
