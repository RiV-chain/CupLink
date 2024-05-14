package org.rivchain.cuplink.automotive

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.core.graphics.drawable.IconCompat
import org.rivchain.cuplink.R
import org.rivchain.cuplink.model.Contacts

class AutoControlScreen(private val carContext: CarContext, private val contacts: Contacts) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val itemListBuilder = ItemList.Builder().apply {
            for (contact in contacts.contactList) {
                addItem(
                    Row.Builder()
                        .setTitle(contact.name)
                        .setImage(getCarIconFromDrawable(R.drawable.ic_contacts))
                        .setOnClickListener {
                            // Handle click on contact
                        }
                        .build()
                )
            }
        }

        val listTemplateBuilder = ListTemplate.Builder().apply {
            setSingleList(itemListBuilder.build())
            setHeaderAction(Action.BACK)
        }

        return listTemplateBuilder.build()
    }

    private fun getCarIconFromDrawable(drawableResId: Int): CarIcon {
        val iconCompat = IconCompat.createWithResource(carContext, drawableResId)
        return CarIcon.Builder(iconCompat).build()
    }
}