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

import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.textfield.TextInputEditText
import org.rivchain.cuplink.NotificationUtils.showToastMessage
import org.rivchain.cuplink.adapter.EventListAdapter
import org.rivchain.cuplink.model.Event
import org.rivchain.cuplink.util.Log
import org.rivchain.cuplink.util.Utils

class EventListFragment() : Fragment() {

    private lateinit var activity: BaseActivity
    private lateinit var eventListAdapter: EventListAdapter
    private lateinit var eventListView: ListView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_event_list, container, false)
        eventListView = view.findViewById(R.id.eventList)
        activity = requireActivity() as BaseActivity

        eventListAdapter = EventListAdapter(activity, R.layout.item_event, emptyList(), emptyList())
        eventListView.adapter = eventListAdapter
        eventListView.onItemClickListener = onEventClickListener
        eventListView.onItemLongClickListener = onEventLongClickListener

        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(refreshEventListReceiver, IntentFilter("refresh_event_list"))

        refreshEventListBroadcast()

        return view
    }

    private val onEventClickListener = AdapterView.OnItemClickListener { _, _, i, _ ->
        Log.d(this, "onItemClick")

        val eventGroup = eventListAdapter.getItem(i)
        // get last event that has an address
        val latestEvent = eventGroup.lastOrNull { it.address != null } ?: eventGroup.last()

        val contact = DatabaseCache.database.contacts.getContactByPublicKey(latestEvent.publicKey)
            ?: latestEvent.createUnknownContact("")

        if (contact.addresses.isEmpty()) {
            showToastMessage(activity, R.string.contact_has_no_address_warning)
        } else {
            // start call
            Log.d(this, "start CallActivity")
            val intent = Intent(activity, CallActivity::class.java)
            intent.action = "ACTION_OUTGOING_CALL"
            intent.putExtra("EXTRA_CONTACT", contact)
            startActivity(intent)
        }
    }

    private val onEventLongClickListener = AdapterView.OnItemLongClickListener { _, _, i, _ ->
        Log.d(this, "onItemLongClick")

        val eventGroup = eventListAdapter.getItem(i)
        val latestEvent = eventGroup.last()
        val res = resources
        val add = res.getString(R.string.contact_menu_add)
        val delete = res.getString(R.string.contact_menu_delete)
        val block = res.getString(R.string.contact_menu_block)
        val unblock = res.getString(R.string.contact_menu_unblock)
        val contact = DatabaseCache.database.contacts.getContactByPublicKey(latestEvent.publicKey)

        // Create a list of options
        val options = mutableListOf<String>()

        // Allow to add unknown caller
        if (contact == null) {
            options.add(add)
        }

        if (contact != null) {
            if (contact.blocked) {
                options.add(unblock)
            } else {
                options.add(block)
            }
        }

        options.add(delete)

        // Inflate the dialog layout
        val inflater = LayoutInflater.from(activity)
        val dialogView = inflater.inflate(R.layout.dialog_select_one_listview_item, null)
        val listViewEventOptions: ListView = dialogView.findViewById(R.id.listView)

        // Create an ArrayAdapter
        val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, options)
        listViewEventOptions.adapter = adapter

        // Create and show the dialog
        val dialog = activity.createBlurredPPTCDialog(dialogView)
        dialog.setCancelable(true)

        listViewEventOptions.setOnItemClickListener { _, _, position, _ ->
            val selectedOption = options[position]
            when (selectedOption) {
                add -> {
                    showAddDialog(eventGroup)
                }
                block -> {
                    setBlocked(latestEvent, true)
                }
                unblock -> {
                    setBlocked(latestEvent, false)
                }
                delete -> {
                    deleteEventGroup(eventGroup)
                }
            }
            dialog.dismiss()
        }

        dialog.show()
        true
    }

    private val refreshEventListReceiver = object : BroadcastReceiver() {
        //private var lastTimeRefreshed = 0L

        override fun onReceive(context: Context, intent: Intent) {
            Log.d(this@EventListFragment, "trigger refreshEventList() from broadcast at ${this@EventListFragment.lifecycle.currentState}")
            // prevent this method from being called too often
            //val now = System.currentTimeMillis()
            //if ((now - lastTimeRefreshed) > 1000) {
            //    lastTimeRefreshed = now
                refreshEventList()
            //}
        }
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(refreshEventListReceiver)
        super.onDestroy()
    }

    private fun refreshEventList() {
        Log.d(this, "refreshEventList")

        val activity = requireActivity() as MainActivity
        val events = DatabaseCache.database.events.eventList
        val contacts = DatabaseCache.database.contacts.contactList

        activity.runOnUiThread {
            activity.updateEventTabTitle()

            eventListAdapter.update(events, contacts)
            eventListAdapter.notifyDataSetChanged()
            eventListView.adapter = eventListAdapter
        }
    }

    private fun refreshEventListBroadcast() {
        LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(Intent("refresh_event_list"))
    }

    // only available for known contacts
    private fun setBlocked(event: Event, blocked: Boolean) {

        val contact = DatabaseCache.database.contacts.getContactByPublicKey(event.publicKey)
        if (contact != null) {
            contact.blocked = blocked
            DatabaseCache.save()
            LocalBroadcastManager.getInstance(requireContext())
                .sendBroadcast(Intent("refresh_contact_list"))
            LocalBroadcastManager.getInstance(requireContext())
                .sendBroadcast(Intent("refresh_event_list"))
        } else {
            // ignore - not expected to happen
        }
    }

    override fun onResume() {
        Log.d(this, "onResume()")
        super.onResume()
        //service.updateNotification()
        NotificationUtils.refreshEvents(this.activity)
    }

    private fun deleteEventGroup(eventGroup: List<Event>) {
        Log.d(this, "removeEventGroup()")
        activity.deleteEvents(eventGroup.map { it.date })
    }

    // only available for unknown contacts
    private fun showAddDialog(eventGroup: List<Event>) {
        Log.d(this, "showAddDialog")
        val activity = requireActivity() as MainActivity
        // prefer latest event that has an address
        val latestEvent = eventGroup.lastOrNull { it.address != null } ?: eventGroup.last()
        val view: View = LayoutInflater.from(activity).inflate(R.layout.dialog_add_contact, null)
        val dialog = activity.createBlurredPPTCDialog(view)
        val nameEditText = view.findViewById<TextInputEditText>(R.id.NameEditText)
        val exitButton = view.findViewById<Button>(R.id.CancelButton)
        val okButton = view.findViewById<Button>(R.id.OkButton)
        okButton.setOnClickListener {
            val name = nameEditText.text.toString()
            if (!Utils.isValidName(name)) {
                showToastMessage(activity, R.string.contact_name_invalid)
                return@setOnClickListener
            }

            if (DatabaseCache.database.contacts.getContactByName(name) != null) {
                showToastMessage(activity, R.string.contact_name_exists)
                return@setOnClickListener
            }

            val contact = latestEvent.createUnknownContact(name)
            activity.addContact(contact)
            showToastMessage(activity, R.string.done)
            // close dialog
            dialog.dismiss()
        }
        exitButton.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
