package org.rivchain.cuplink

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONException
import org.json.JSONObject
import org.rivchain.cuplink.NotificationUtils.showToastMessage
import org.rivchain.cuplink.model.Contact
import org.rivchain.cuplink.util.RlpUtils
import org.rivchain.cuplink.util.Utils
import org.rivchain.cuplink.util.ViewUtil.generateBlockies
import org.rivchain.cuplink.util.ViewUtil.getRoundedCroppedBitmap

open class AddContactActivity: BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onServiceConnected()
        if(intent!=null && intent.extras!=null && intent.extras?.get("EXTRA_CONTACT") != null) {
            addContact(intent.extras?.get("EXTRA_CONTACT") as Contact)
        }
    }

    override fun onResume() {
        super.onResume()
        resume()
    }

    override fun onPause() {
        super.onPause()
        pause()
    }

    protected open fun onServiceConnected(){
        // nothing to do
    }

    protected open fun pause(){
        // nothing to do
    }

    protected open fun resume(){
        // nothing to do
    }

    protected open fun addContact(data: String) {
        var contact = RlpUtils.parseLink(data)
        if (contact != null) {
            addContact(contact)
        } else {
            val obj = JSONObject(data)
            contact = Contact.fromJSON(obj, false)
            addContact(contact)
        }
    }

    override fun addContact(contact: Contact) {
        if (contact.addresses.isEmpty()) {
            showToastMessage(this, R.string.contact_has_no_address_warning)
        }

        // lookup existing contacts by key and name
        val contacts = DatabaseCache.database.contacts
        val existingContactByPublicKey = contacts.getContactByPublicKey(contact.publicKey)
        val existingContactByName = contacts.getContactByName(contact.name)
        if (existingContactByPublicKey != null) {
            // contact with that public key exists
            showPubkeyConflictDialog(contact, existingContactByPublicKey)
        } else if (existingContactByName != null) {
            // contact with that name exists
            showNameConflictDialog(contact, existingContactByName)
        } else {
            // no conflict
            super.addContact(contact)
            finish()
        }
    }

    protected open fun showPubkeyConflictDialog(newContact: Contact, otherContact: Contact) {
        pause()
        val view: View = LayoutInflater.from(this).inflate(R.layout.dialog_add_contact_pubkey_conflict, null)
        val dialog = createBlurredPPTCDialog(view)
        dialog.setCanceledOnTouchOutside(false)
        val contactIcon =
            view.findViewById<ImageView>(R.id.contactIcon)
        val contactKey =
            view.findViewById<TextView>(R.id.contactKey)
        // Generate the blockies icon from the user's public key
        val blockiesBitmap = generateBlockies(otherContact.publicKey)

        // Create a circular bitmap from the blockies image
        val circularBitmap = getRoundedCroppedBitmap(blockiesBitmap)
        contactIcon.setImageBitmap(circularBitmap)
        contactKey.text = Utils.byteArrayToHexString(otherContact.publicKey)
        val nameTextView =
            view.findViewById<TextView>(R.id.public_key_conflicting_contact_textview)
        val abortButton = view.findViewById<Button>(R.id.public_key_conflict_abort_button)
        val replaceButton = view.findViewById<Button>(R.id.public_key_conflict_replace_button)
        nameTextView.text = otherContact.name
        replaceButton.setOnClickListener {
            deleteContact(otherContact.publicKey)
            addContact(newContact)

            // done
            showToastMessage(this@AddContactActivity, R.string.done)
            dialog.cancel()
            finish()
        }
        abortButton.setOnClickListener {
            dialog.cancel()
            resume()
        }
        dialog.show()
    }

    protected open fun showNameConflictDialog(newContact: Contact, other_contact: Contact) {
        pause()
        val view: View = LayoutInflater.from(this).inflate(R.layout.dialog_add_contact_name_conflict, null)
        val dialog = createBlurredPPTCDialog(view)
        val nameEditText = view.findViewById<TextInputEditText>(R.id.conflict_contact_edit_textview)
        val replaceButton = view.findViewById<Button>(R.id.conflict_contact_replace_button)
        val renameButton = view.findViewById<Button>(R.id.conflict_contact_rename_button)
        nameEditText.setText(other_contact.name)
        replaceButton.setOnClickListener {
            deleteContact(other_contact.publicKey)
            addContact(newContact)

            // done
            showToastMessage(this, R.string.done)
            dialog.cancel()
            finish()
        }
        renameButton.setOnClickListener {
            val name = nameEditText.text.toString()
            if (!Utils.isValidName(name)) {
                showToastMessage(this, R.string.contact_name_invalid)
                return@setOnClickListener
            }

            if (DatabaseCache.database.contacts.getContactByName(name) != null) {
                showToastMessage(this, R.string.contact_name_exists)
                return@setOnClickListener
            }

            // rename
            newContact.name = name
            addContact(newContact)

            // done
            showToastMessage(this, R.string.done)
            dialog.cancel()
            finish()
        }
        dialog.show()
    }

    protected open fun startManualInput() {
        pause()
        // Inflate the custom layout/view
        val inflater = layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_manual_contact_input, null)
        val et = dialogView.findViewById<TextInputEditText>(R.id.editTextInput)
        val cancelButton = dialogView.findViewById<Button>(R.id.CancelButton)
        val okButton = dialogView.findViewById<Button>(R.id.OkButton)
        // Set the custom view to the dialog
        val dialog: AlertDialog = createBlurredPPTCDialog(dialogView)
        okButton.setOnClickListener{
            try {
                val data = et.text.toString()
                addContact(data)
            } catch (e: JSONException) {
                e.printStackTrace()
                showToastMessage(this, R.string.invalid_qr_code_data)
            }
            dialog.cancel()
        }

        cancelButton.setOnClickListener{
            dialog.cancel()
            resume()
        }
        dialog.show()
    }

    companion object {
        fun handlePotentialCupLinkContactUrl(activity: Activity, potentialUrl: String) {
            val contact = RlpUtils.parseLink(potentialUrl)

            if (contact != null) {
                activity.startActivity(Intent(
                    activity,
                    AddContactActivity::class.java
                ).setAction("ADD_CONTACT").putExtra("EXTRA_CONTACT", contact))
            }
        }
    }
}