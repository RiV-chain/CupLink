package org.rivchain.cuplink

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView

import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import org.libsodium.jni.Sodium
import org.rivchain.cuplink.NotificationUtils.showToastMessage
import org.rivchain.cuplink.model.AddressEntry
import org.rivchain.cuplink.model.Contact
import org.rivchain.cuplink.util.Log
import org.rivchain.cuplink.util.NetworkUtils
import org.rivchain.cuplink.util.NetworkUtils.AddressType
import org.rivchain.cuplink.util.Utils
import java.util.Locale

class ContactDetailsActivity : BaseActivity() {
    private lateinit var publicKey: ByteArray

    private lateinit var contactNameEdit: TextView
    private lateinit var contactPublicKeyEdit: TextView
    private lateinit var contactBlockedSwitch : SwitchMaterial
    private lateinit var addressEditText: TextInputEditText

    private lateinit var addressListView: ListView
    private lateinit var addressListViewAdapter: AddressListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_details)
        setTitle(R.string.title_contact_details)

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


        addressListView = findViewById(R.id.AddressListView)
        addressEditText = findViewById(R.id.AddressEditText)

        contactNameEdit = findViewById(R.id.contactNameTv)
        contactPublicKeyEdit = findViewById(R.id.contactPublicKeyTv)
        contactBlockedSwitch = findViewById(R.id.contactBlockedSwitch)

        addressListViewAdapter = AddressListAdapter(this)
        addressListView.adapter = addressListViewAdapter

        try {
            val publicKey = intent.extras!!["EXTRA_CONTACT_PUBLICKEY"] as ByteArray
            val contact = DatabaseCache.database.contacts.getContactByPublicKey(publicKey)!!
            updateContact(contact)
        } catch (e: Exception) {
            e.printStackTrace()
            showToastMessage(this,  e.message)
            finish()
        }
    }

    private fun updateContact(newContact: Contact) {
        Log.d(this, "updateContact")

        publicKey = newContact.publicKey

        contactNameEdit.text = newContact.name
        findViewById<View>(R.id.contactNameLayout).setOnClickListener { showChangeNameDialog() }

        contactPublicKeyEdit.text = Utils.byteArrayToHexString(newContact.publicKey)
        findViewById<View>(R.id.contactPublicKeyLayout).setOnClickListener { showChangePublicKeyDialog() }

        contactBlockedSwitch.isChecked = newContact.blocked

        addressListView.setOnItemClickListener { _, _, i, _ ->
            val address = addressListViewAdapter.getAddressAt(i)
            if (address != null) {
                addressEditText.setText(address)
            }
        }

        addressListViewAdapter.setAddresses(newContact.addresses)

        addressEditText.setText("")

        findViewById<Button>(R.id.AddAddressButton).setOnClickListener {
            var address = addressEditText.text!!.toString()
            address = if (NetworkUtils.isIPAddress(address) || NetworkUtils.isDomain(address)) {
                address.lowercase(Locale.ROOT)
            } else if (NetworkUtils.isMACAddress(address)) {
                address.uppercase(Locale.ROOT)
            } else {
                showToastMessage(this,  R.string.error_address_invalid)
                return@setOnClickListener
            }

            // multicast addresses are not supported yet
            if (NetworkUtils.getAddressType(address) in listOf(AddressType.MULTICAST_MAC, AddressType.MULTICAST_IP)) {
                showToastMessage(this,  R.string.error_address_multicast_not_supported)
                return@setOnClickListener
            }

            if (address in addressListViewAdapter.getAddresses()) {
                showToastMessage(this,  R.string.error_address_exists)
                return@setOnClickListener
            }

            addressListViewAdapter.addAddress(address)
        }

        findViewById<Button>(R.id.SaveButton).setOnClickListener {
            val contact = getOriginalContact()
            if (contact != null) {
                contact.name = contactNameEdit.text.toString()
                contact.blocked = contactBlockedSwitch.isChecked
                contact.addresses = addressListViewAdapter.getAddresses()
                contact.publicKey = publicKey

                DatabaseCache.save()
                showToastMessage(this,  R.string.done)
            } else {
                showToastMessage(this,  R.string.error)
            }
        }

        findViewById<Button>(R.id.ResetButton).setOnClickListener {
            val contact = getOriginalContact()
            if (contact != null) {
                updateContact(contact)
            } else {
                showToastMessage(this,  R.string.error)
            }
        }
    }

    private fun getOriginalContact(): Contact? {
        return if (publicKey.size == Sodium.crypto_sign_publickeybytes()) {
            DatabaseCache.database.contacts.getContactByPublicKey(publicKey)
        } else {
            null
        }
    }

    private fun showChangePublicKeyDialog() {
        Log.d(this, "showChangePublicKeyDialog()")
        val view: View = LayoutInflater.from(this).inflate(R.layout.dialog_contact_change_public_key, null)
        val dialog = createBlurredPPTCDialog(view)
        val publicKeyInput = view.findViewById<TextInputEditText>(R.id.PublicKeyEditText)
        val cancelButton = view.findViewById<Button>(R.id.CancelButton)
        val okButton = view.findViewById<Button>(R.id.OkButton)

        publicKeyInput.setText(contactPublicKeyEdit.text, TextView.BufferType.EDITABLE)

        okButton.setOnClickListener {
            val newPublicKey = Utils.hexStringToByteArray(
                publicKeyInput.text.toString()
            )

            if (newPublicKey == null || (newPublicKey.size != Sodium.crypto_sign_publickeybytes())) {
                showToastMessage(this,  R.string.contact_public_key_invalid)
            } else if (DatabaseCache.database.contacts.getContactByPublicKey(newPublicKey) != null) {
                showToastMessage(this,  R.string.contact_public_key_already_exists)
            } else {
                contactPublicKeyEdit.text = Utils.byteArrayToHexString(newPublicKey)
                dialog.dismiss()
            }
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showChangeNameDialog() {
        Log.d(this, "showChangeNameDialog()")
        val dialogView: View = LayoutInflater.from(this).inflate(R.layout.dialog_contact_change_name, null)
        val dialog = createBlurredPPTCDialog(dialogView)
        val nameEditText = dialogView.findViewById<TextInputEditText>(R.id.NameEditText)
        val cancelButton = dialogView.findViewById<Button>(R.id.CancelButton)
        val okButton = dialogView.findViewById<Button>(R.id.OkButton)

        nameEditText.setText(contactNameEdit.text, TextView.BufferType.EDITABLE)

        okButton.setOnClickListener {
            val newName = nameEditText.text.toString().trim { it <= ' ' }
            val existingContact = DatabaseCache.database.contacts.getContactByName(newName)

            if (!Utils.isValidName(newName)) {
                showToastMessage(this,  R.string.contact_name_invalid)
            } else if ((existingContact != null) && !existingContact.publicKey.contentEquals(publicKey)) {
                showToastMessage(this,  R.string.contact_name_exists)
            } else {
                contactNameEdit.text = newName
                dialog.dismiss()
            }
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    inner class AddressListAdapter(private val context: Activity): BaseAdapter() {
        private val addresses = mutableListOf<AddressEntry>()

        override fun isEmpty(): Boolean {
            return addresses.isEmpty()
        }

        override fun getCount(): Int {
            return if (isEmpty) {
                1
            } else addresses.size
        }

        override fun getItem(position: Int): AddressEntry? {
            return if (isEmpty) {
                null
            } else addresses[position]
        }

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = convertView ?: context.layoutInflater.inflate(
                    R.layout.item_address, parent, false)
            val label = item.findViewById<TextView>(R.id.label)
            val icon = item.findViewById<ImageView>(R.id.icon)
            if (isEmpty) {
                // no addresses
                label.text = getString(R.string.empty_list_item)
                label.textAlignment = View.TEXT_ALIGNMENT_CENTER
                icon.isVisible = false
            } else {
                val ae = addresses[position]

                icon.isVisible = true
                icon.setOnClickListener {
                    addresses.remove(ae)
                    notifyDataSetChanged()
                }

                val info = ArrayList<String>()

                // add device name in brackets
                if (ae.device.isNotEmpty() && !ae.address.endsWith("%${ae.device}")) {
                    info.add(ae.device)
                }

                when (NetworkUtils.getAddressType(ae.address)) {
                    AddressType.GLOBAL_MAC -> info.add("<hardware>")
                    AddressType.MULTICAST_MAC,
                    AddressType.MULTICAST_IP -> info.add("<multicast>")
                    else -> {}
                }

                label.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                if (info.isNotEmpty()) {
                    label.text = "${ae.address} (${info.joinToString()})"
                } else {
                    label.text = "${ae.address}"
                }
            }

            return item
        }

        fun getAddressAt(pos: Int): String? {
            if (pos > -1 && pos < addresses.count()) {
                return addresses[pos].address
            } else {
                return null
            }
        }

        fun addAddress(address: String)  {
            val ae = AddressEntry(address, "")
            if (ae !in addresses) {
                this.addresses.add(ae)
                notifyDataSetChanged()
            }
        }

        fun getAddresses(): List<String> {
            return addresses.map { it.address }
        }

        fun setAddresses(addresses: List<String>) {
            this.addresses.clear()
            addresses.map { this.addresses.add(AddressEntry(it, "")) }
            notifyDataSetChanged()
        }
    }
}
