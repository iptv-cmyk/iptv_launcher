package tech.vvs.vvs_launcher

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView adapter for displaying the list of available channels.
 *
 * This adapter uses a [DiffUtil.ItemCallback] for efficient updates when the
 * channel list changes. An item click invokes the supplied [onClick] lambda
 * with the selected [Channel].
 */
class ChannelAdapter(
    private val onClick: (Channel) -> Unit
) : ListAdapter<Channel, ChannelAdapter.ChannelViewHolder>(ChannelDiffCallback()) {

    private var selectedUri: String? = null

    fun updateSelection(uri: String?) {
        selectedUri = uri
        Log.d("ChannelAdapter", "Selected URI: $uri")
        notifyDataSetChanged() // efficient enough for small lists; use itemAnimator for better UX if needed
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.channel_item, parent, false)
        return ChannelViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = getItem(position)
        holder.bind(channel, channel.uri == selectedUri)
    }

    /**
     * ViewHolder class for channel items.
     */
    class ChannelViewHolder(
        itemView: View,
        private val onClick: (Channel) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.channelName)
        private var currentChannel: Channel? = null

        init {
            itemView.setOnClickListener {
                currentChannel?.let {
                    if (it.uri != "udp://@0.0.0.0") {
                        Log.d("ChannelAdapter", "Channel clicked: ${it.name}, URI: ${it.uri}")
                        onClick(it)
                    }
                }
            }
        }

        fun bind(channel: Channel, isSelected: Boolean) {
            currentChannel = channel
            //Log.d("ChannelAdapter", "Binding channel: ${channel.name} (${channel.uri})")

            // Display both number and name if number is available.
            val displayName = if (channel.number != null) {
                "${channel.number}. ${channel.name}"
            } else {
                channel.name
            }
            nameText.text = displayName

            // Use 'activated' state for selection so the selector drawable handles focus/activated states
            itemView.isActivated = isSelected

            if (channel.uri == "udp://@0.0.0.0") {
                nameText.setTextColor(android.graphics.Color.GRAY)
                itemView.isEnabled = false
                itemView.isClickable = false
                itemView.isFocusable = false
            } else {
                nameText.setTextColor(androidx.core.content.ContextCompat.getColor(itemView.context, android.R.color.white))
                itemView.isEnabled = true
                itemView.isClickable = true
                itemView.isFocusable = true
            }
        }
    }

    /**
     * DiffUtil callback for efficiently updating the list when the channels change.
     */
    private class ChannelDiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            // Two items are the same if their URI matches.
            return oldItem.uri == newItem.uri
        }

        override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem == newItem
        }
    }
}