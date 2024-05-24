package org.rivchain.cuplink.view

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatAutoCompleteTextView

class CenteredAutoCompleteTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatAutoCompleteTextView(context, attrs) {

    override fun showDropDown() {
        val screenWidth = resources.displayMetrics.widthPixels
        val dropDownWidth = this.dropDownWidth
        val offsetX = (screenWidth - dropDownWidth) / 2

        this.dropDownHorizontalOffset = offsetX
        super.showDropDown()
    }
}