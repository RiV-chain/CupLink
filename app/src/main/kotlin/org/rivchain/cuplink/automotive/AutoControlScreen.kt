package org.rivchain.cuplink.automotive

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.core.graphics.drawable.IconCompat
import org.rivchain.cuplink.CallActivity
import org.rivchain.cuplink.R
import org.rivchain.cuplink.model.Contact
import org.rivchain.cuplink.model.Contacts

@RequiresApi(Build.VERSION_CODES.M)
class AutoControlScreen(private val carContext: CarContext, private val contacts: Contacts) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val itemListBuilder = ItemList.Builder().apply {
            /**
             * Disabled contact list till the AA authentication is done somehow
             */
            /*
            for (contact in contacts.contactList) {
                addItem(
                    Row.Builder()
                        .setTitle(contact.name)
                        .setImage(getCarIconFromDrawable(R.drawable.ic_contacts))
                        .setOnClickListener {
                            startCall(contact)
                        }
                        .build()
                )
            }
             */
        }

        val listTemplateBuilder = ListTemplate.Builder().apply {
            setSingleList(itemListBuilder.build())
            setHeaderAction(Action.BACK)
        }

        return listTemplateBuilder.build()
    }

    private fun getCarIconFromDrawable(drawableResId: Int): CarIcon {
        val iconCompat = IconCompat.createWithResource(carContext, drawableResId)
        val carColor = CarColor.createCustom(Color.WHITE, Color.BLACK) // Set the tint color to white and dark color to black
        return CarIcon.Builder(iconCompat)
            .setTint(carColor)
            .build()
    }

    private fun startCall(contact: Contact) {
        val intent = Intent(carContext, CallActivity::class.java)
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.action = "ACTION_OUTGOING_CALL"
        intent.putExtra("EXTRA_CONTACT", contact)
        carContext.startActivity(intent)
    }
}