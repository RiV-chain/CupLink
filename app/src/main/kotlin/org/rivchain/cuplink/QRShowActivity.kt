package org.rivchain.cuplink

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import org.rivchain.cuplink.NotificationUtils.showToastMessage
import org.rivchain.cuplink.model.Contact
import org.rivchain.cuplink.util.RlpUtils

class QRShowActivity : BaseActivity() {
    private lateinit var publicKey: ByteArray

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qrshow)

        if(intent == null || intent.extras == null || intent.extras?.get("EXTRA_CONTACT_PUBLICKEY") == null){
            showToastMessage(this, R.string.contact_public_key_invalid)
            finish()
            return
        }

        publicKey = intent.extras?.get("EXTRA_CONTACT_PUBLICKEY") as ByteArray

        title = getString(R.string.title_show_qr_code)

        findViewById<View>(R.id.fabPresenter).setOnClickListener {
            startActivity(Intent(this, QRScanActivity::class.java))
            finish()
        }

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
            showToastMessage(this,  e.message)
            finish()
        }
    }

    private fun generateQR(contact: Contact) {
        findViewById<TextView>(R.id.contact_name_tv)
            .text = contact.name

        val data = Contact.toJSON(contact, false).toString()
        if (contact.addresses.isEmpty()) {
            showToastMessage(this,  R.string.contact_has_no_address_warning)
        }
        if (contact.name.isEmpty()) {
            showToastMessage(this,  R.string.contact_name_invalid)
        }
        if (contact.publicKey.isEmpty()) {
            showToastMessage(this,  R.string.contact_public_key_invalid)
        }
        val multiFormatWriter = MultiFormatWriter()
        val bitMatrix = multiFormatWriter.encode(data, BarcodeFormat.QR_CODE, 1080, 1080)
        val barcodeEncoder = BarcodeEncoder()
        val bitmap = barcodeEncoder.createBitmap(bitMatrix)
        findViewById<ImageView>(R.id.QRView).setImageBitmap(bitmap)
    }

    private fun generateDeepLinkQR(contact: Contact) {
        findViewById<TextView>(R.id.contact_name_tv)
            .text = contact.name

        val data = RlpUtils.generateLink(contact)
        if(data == null){
            showToastMessage(this,  R.string.contact_is_invalid)
        }
        if (contact.addresses.isEmpty()) {
            showToastMessage(this,  R.string.contact_has_no_address_warning)
        }
        if (contact.name.isEmpty()) {
            showToastMessage(this,  R.string.contact_name_invalid)
        }
        if (contact.publicKey.isEmpty()) {
            showToastMessage(this,  R.string.contact_public_key_invalid)
        }
        val multiFormatWriter = MultiFormatWriter()
        val bitMatrix = multiFormatWriter.encode(data, BarcodeFormat.QR_CODE, 1080, 1080)
        val barcodeEncoder = BarcodeEncoder()
        val bitmap = barcodeEncoder.createBitmap(bitMatrix)
        runOnUiThread {
            findViewById<ImageView>(R.id.QRView).setImageBitmap(bitmap)
        }
    }
}
