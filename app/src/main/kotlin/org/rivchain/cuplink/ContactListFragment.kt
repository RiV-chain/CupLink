package org.rivchain.cuplink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView

import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONException
import org.rivchain.cuplink.NotificationUtils.showToastMessage
import org.rivchain.cuplink.adapter.ContactListAdapter
import org.rivchain.cuplink.model.Contact
import org.rivchain.cuplink.util.Log
import org.rivchain.cuplink.util.RlpUtils

class ContactListFragment() : Fragment() {

    private lateinit var activity: BaseActivity
    private lateinit var contactListView: ListView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Log.d(this, "onCreateView")
        val view: View = inflater.inflate(R.layout.fragment_contact_list, container, false)
        activity = requireActivity() as BaseActivity

        contactListView = view.findViewById(R.id.contactList)
        contactListView.onItemClickListener = onContactClickListener
        contactListView.onItemLongClickListener = onContactLongClickListener

        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(refreshContactListReceiver, IntentFilter("refresh_contact_list"))

        refreshContactListBroadcast()

        return view
    }

    private val onContactClickListener =
        AdapterView.OnItemClickListener { adapterView, _, i, _ ->
            Log.d(this, "onItemClick")
            val activity = requireActivity()
            val contact = adapterView.adapter.getItem(i) as Contact
            if (contact.addresses.isEmpty()) {
                showToastMessage(activity, R.string.contact_has_no_address_warning)
            } else {
                Log.d(this, "start CallActivity")
                val intent = Intent(activity, CallActivity::class.java)
                intent.action = "ACTION_OUTGOING_CALL"
                intent.putExtra("EXTRA_CONTACT", contact)
                startActivity(intent)
            }
    }

    private val onContactLongClickListener =
        AdapterView.OnItemLongClickListener { adapterView, _, i, _ ->
            val contact = adapterView.adapter.getItem(i) as Contact
            val options = listOf(
                getString(R.string.contact_menu_details),
                getString(R.string.contact_menu_delete),
                getString(R.string.contact_menu_ping),
                getString(R.string.contact_menu_share),
                getString(R.string.contact_menu_qrcode)
            )

            val inflater = LayoutInflater.from(activity)
            val dialogView = inflater.inflate(R.layout.dialog_select_one_listview_item, null)
            val listViewContactOptions: ListView = dialogView.findViewById(R.id.listView)

            val adapter = ArrayAdapter(this.requireContext(), R.layout.spinner_item, options)
            listViewContactOptions.adapter = adapter

            val dialog = activity.createBlurredPPTCDialog(dialogView)
            dialog.setCancelable(true)

            listViewContactOptions.setOnItemClickListener { _, _, position, _ ->
                val selectedOption = options[position]
                val publicKey = contact.publicKey
                when (selectedOption) {
                    getString(R.string.contact_menu_details) -> {
                        val intent = Intent(activity, ContactDetailsActivity::class.java)
                        intent.putExtra("EXTRA_CONTACT_PUBLICKEY", contact.publicKey)
                        startActivity(intent)
                    }
                    getString(R.string.contact_menu_delete) -> showDeleteDialog(publicKey, contact.name)
                    getString(R.string.contact_menu_ping) -> pingContact(contact)
                    getString(R.string.contact_menu_share) -> {
                        Thread {
                            shareContact(contact)
                        }.start()
                    }
                    getString(R.string.contact_menu_qrcode) -> {
                        val intent = Intent(activity, QRShowActivity::class.java)
                        intent.putExtra("EXTRA_CONTACT_PUBLICKEY", contact.publicKey)
                        startActivity(intent)
                    }
                }
                dialog.dismiss()
            }

            dialog.show()
            true
        }

    private val refreshContactListReceiver = object : BroadcastReceiver() {
        //private var lastTimeRefreshed = 0L

        override fun onReceive(context: Context, intent: Intent) {
            Log.d(this@ContactListFragment, "trigger refreshContactList() from broadcast at ${this@ContactListFragment.lifecycle.currentState}")
            // prevent this method from being called too often
            //val now = System.currentTimeMillis()
            //if ((now - lastTimeRefreshed) > 1000) {
            //    lastTimeRefreshed = now
                refreshContactList()
            //}
        }
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(refreshContactListReceiver)
        super.onDestroy()
    }

    override fun onResume() {
        Log.d(this, "onResume()")
        super.onResume()

        if (DatabaseCache.database.settings.automaticStatusUpdates) {
            // ping all contacts
            activity.pingContacts(DatabaseCache.database.contacts.contactList)
        }
    }

    private fun showPingAllButton(): Boolean {
        return !DatabaseCache.database.settings.automaticStatusUpdates
    }

    private fun pingContact(contact: Contact) {
        activity.pingContacts(listOf(contact))
        val message = String.format(getString(R.string.ping_contact), contact.name)
        showToastMessage(requireContext(), message)
    }

    private fun pingAllContacts() {
        activity.pingContacts(DatabaseCache.database.contacts.contactList)
        val message = String.format(getString(R.string.ping_all_contacts))
        showToastMessage(requireContext(), message)
    }

    private fun showDeleteDialog(publicKey: ByteArray, name: String) {

        val view: View = LayoutInflater.from(activity).inflate(R.layout.dialog_yes_no, null)
        val dialog = activity.createBlurredPPTCDialog(view)
        dialog.setCancelable(false)
        val titleText = view.findViewById<TextView>(R.id.title)
        titleText.text = getString(R.string.dialog_title_delete_contact)
        val messageText = view.findViewById<TextView>(R.id.message)
        messageText.text = name
        val noButton = view.findViewById<Button>(R.id.no)
        val yesButton = view.findViewById<Button>(R.id.yes)
        yesButton.setOnClickListener {
            activity.deleteContact(publicKey)
            dialog.cancel()
        }
        noButton.setOnClickListener {
            dialog.cancel()
        }
        dialog.show()
    }

    private fun refreshContactList() {
        Log.d(this, "refreshContactList")
        val activity = requireActivity()
        val contacts = DatabaseCache.database.contacts.contactList.toMutableList()  // Ensure the list is mutable
        // Sort the contacts by name alphabetically in-place
        contacts.sortBy { it.name.lowercase() }

        activity.runOnUiThread {
            val adapter = ContactListAdapter(activity, R.layout.item_contact, contacts)
            contactListView.adapter = adapter
            adapter.startPingingContacts()  // Start pinging contacts after setting the adapter
        }
    }

    private fun refreshContactListBroadcast() {
        LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(Intent("refresh_contact_list"))
    }

    private fun shareContact(contact: Contact) {
        Log.d(this, "shareContact")
        try {
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_TEXT, RlpUtils.generateLink(contact))
            startActivity(intent)
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }
}
