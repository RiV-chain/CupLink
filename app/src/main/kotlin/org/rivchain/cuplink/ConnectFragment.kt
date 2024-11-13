package org.rivchain.cuplink

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import org.rivchain.cuplink.NotificationUtils.showToastMessage

class ConnectFragment : Fragment() {

    private var requestQRCodeScanLauncher: ActivityResultLauncher<Intent>? = null
    private lateinit var activity: MainActivity
    private lateinit var publicKey: ByteArray
    private var done = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view: View = inflater.inflate(R.layout.fragment_connect, container, false)
        activity = requireActivity() as MainActivity

        if (arguments == null || arguments?.get("EXTRA_CONTACT_PUBLICKEY") == null) {
            showToastMessage(requireContext(), R.string.contact_public_key_invalid)
            activity.finish()
        }

        publicKey = arguments?.get("EXTRA_CONTACT_PUBLICKEY") as ByteArray

        // Set the title for the fragment (if needed)
        activity.title = getString(R.string.title_show_qr_code)

        requestQRCodeScanLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                done = true
                activity.onBackPressed()
            }

        return view
    }

    override fun onResume() {
        super.onResume()
        if (!done) {
            val intent = Intent(activity, ConnectFragmentActivity::class.java)
            intent.putExtra("EXTRA_CONTACT_PUBLICKEY", publicKey)
            requestQRCodeScanLauncher!!.launch(intent)
        }
        done = false
    }


}
