package tech.vvs.vvs_launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class EpgAdapter : RecyclerView.Adapter<EpgAdapter.VH>() {

    private val items = mutableListOf<EpgItem>()

    fun submit(list: List<EpgItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.epg_item, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val time: TextView = v.findViewById(R.id.epgTime)
        private val title: TextView = v.findViewById(R.id.epgTitle)
        fun bind(item: EpgItem) {
            val start = java.text.SimpleDateFormat("hh:mm a").format(java.util.Date(item.startMillis))
            val end = java.text.SimpleDateFormat("hh:mm a").format(java.util.Date(item.endMillis))
            time.text = "$start — $end · ${item.durationMinutes} min"
            title.text = item.title
        }
    }
}
