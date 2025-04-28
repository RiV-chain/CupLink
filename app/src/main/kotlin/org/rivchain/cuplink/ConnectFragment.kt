package org.rivchain.cuplink

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.get
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
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
import org.rivchain.cuplink.model.Contact
import org.rivchain.cuplink.util.RlpUtils
import org.rivchain.cuplink.util.Utils
import androidx.core.view.isVisible
import androidx.core.view.isInvisible

class ConnectFragment : Fragment(), BarcodeCallback {

    private lateinit var activity: MainActivity
    private lateinit var publicKey: ByteArray
    private lateinit var barcodeView: DecoratedBarcodeView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view: View = inflater.inflate(R.layout.activity_connect, container, false)
        activity = requireActivity() as MainActivity

        if (arguments == null || arguments?.get("EXTRA_CONTACT_PUBLICKEY") == null) {
            Toast.makeText(requireContext(), R.string.contact_deeplink_invalid, Toast.LENGTH_LONG).show()
            activity.finish()
        }

        publicKey = arguments?.get("EXTRA_CONTACT_PUBLICKEY") as ByteArray

        // Set the title for the fragment (if needed)
        activity.title = getString(R.string.title_show_qr_code)

        // Inflate the layout for this Activity
        requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

        barcodeView = view.findViewById(R.id.barcodeScannerView)
        barcodeView.setStatusText(null)


        view.findViewById<View>(R.id.fabShare).setOnClickListener {
            try {
                val contact = activity.getContactOrOwn(publicKey)!!
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
            } catch (e: Exception) {
                // ignore
            }
        }

        try {
            val contact = activity.getContactOrOwn(publicKey)!!
            CoroutineScope(Dispatchers.Main).launch {
                // Directly call the suspending function
                generateDeepLinkQR(contact)
            }
        } catch (e: NullPointerException) {
            e.printStackTrace()
            Toast.makeText(this.activity, "NPE", Toast.LENGTH_LONG).show()
        } catch (e: Exception){
            e.printStackTrace()
            Toast.makeText(this.activity, e.message, Toast.LENGTH_LONG).show()
        }

        // manual input button
        view.findViewById<View>(R.id.manualInput).setOnClickListener { startManualInput() }

        if (!Utils.hasPermission(this.activity, android.Manifest.permission.CAMERA)) {
            enabledCameraForResult.launch(android.Manifest.permission.CAMERA)
        }
        if (Utils.hasPermission(this.activity, Manifest.permission.CAMERA)) {
            initCamera()
        }

        return view
    }

    private suspend fun generateDeepLinkQR(contact: Contact) = withContext(Dispatchers.IO) {
        // Validate contact
        val validationError = validateContact(contact)
        if (validationError != null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ConnectFragment.activity, validationError, Toast.LENGTH_SHORT).show()
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
                val qrCode = activity.findViewById<ImageView>(R.id.QRView)
                qrCode.setImageBitmap(bitmap)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ConnectFragment.activity, e.message, Toast.LENGTH_SHORT).show()
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

    private val enabledCameraForResult = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            isGranted ->
        if (isGranted) {
            initCamera()
        } else {
            Toast.makeText(this.activity, R.string.missing_camera_permission, Toast.LENGTH_LONG).show()
            // no finish() in case no camera access wanted but contact data pasted
        }
    }

    private fun initCamera() {
        val formats = listOf(BarcodeFormat.QR_CODE)
        barcodeView.barcodeView?.decoderFactory = DefaultDecoderFactory(formats)
        barcodeView.decodeContinuous(this)
        barcodeView.resume()
    }

    fun startManualInput() {
        activity.pause()
        val et = activity.findViewById<TextInputLayout>(R.id.EditLayout)
        if (et.isVisible) {
            activity.findViewById<View>(R.id.manualInput).isSelected = false
            et.visibility = View.INVISIBLE
            // do add a contact
            val contact = activity.findViewById<TextInputEditText>(R.id.editTextInput)
            try {
                val data = contact.text.toString()
                if(data.isEmpty()){
                    et.visibility = View.INVISIBLE
                    return
                }
                activity.addContact(data)
                contact.text = null
                activity.backToContractsPage()
            } catch (e: JSONException) {
                e.printStackTrace()
                contact.error = getString(R.string.invalid_qr_code_data)
                Toast.makeText(this.activity, R.string.invalid_qr_code_data, Toast.LENGTH_SHORT).show()
                et.visibility = View.VISIBLE
            }
        } else {
            et.visibility = View.VISIBLE
            // Change the fabManualInput icon
            // Wait for an input data
            activity.findViewById<View>(R.id.manualInput).isSelected = true
        }
    }

    override fun barcodeResult(result: BarcodeResult?) {
        // no more scan until result is processed
        try {
            activity.addContact(result!!.text)
            activity.backToContractsPage()
        } catch (e: JSONException) {
            e.printStackTrace()
            Toast.makeText(this.activity, R.string.invalid_qr, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        barcodeView.resume()  // <--- Resume camera when fragment resumes
    }

    override fun onPause() {
        super.onPause()
        barcodeView.pause()   // <--- Pause camera when fragment pauses
    }
}
