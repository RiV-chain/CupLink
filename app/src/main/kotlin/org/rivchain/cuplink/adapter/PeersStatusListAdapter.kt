package org.rivchain.cuplink.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.rivchain.cuplink.R
import org.rivchain.cuplink.rivmesh.models.PeerInfo

class PeersStatusListAdapter(private val context: Context, private val peerInfoList: List<PeerInfo>) :
    RecyclerView.Adapter<PeersStatusListAdapter.PeerInfoViewHolder>() {

    inner class PeerInfoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val peerIcon: ImageView = view.findViewById(R.id.peerIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerInfoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_peer_status, parent, false)
        return PeerInfoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PeerInfoViewHolder, position: Int) {
        val peerInfo = peerInfoList[position]
        Glide.with(holder.itemView.context)
            .load(peerInfo.getCountry(context)!!.flagID)
            .centerInside()
            .circleCrop()
            .into(holder.peerIcon)
        //holder.peerIcon.setImageResource(peerInfo.getCountry(context)!!.flagID)
        // Set onClickListener to handle story click
        holder.itemView.setOnClickListener {
            // Handle story click
        }
    }

    override fun getItemCount() = peerInfoList.size
}
