package org.rivchain.cuplink.adapter

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class OverlapDecoration(private val overlapWidth: Int) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        // Apply overlap only to items after the first one
        if (position != 0) {
            outRect.left = -overlapWidth
        }
    }
}