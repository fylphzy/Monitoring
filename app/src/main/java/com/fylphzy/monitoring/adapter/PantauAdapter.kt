package com.fylphzy.monitoring.adapter

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.fylphzy.monitoring.MainActivity
import com.fylphzy.monitoring.R
import com.fylphzy.monitoring.model.Pantau

class PantauAdapter(
    private val context: Context,
    private var list: List<Pantau>
) : RecyclerView.Adapter<PantauAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvInfoBaris1: TextView = view.findViewById(R.id.tvInfoBaris1)
        val tvInfoBaris2: TextView = view.findViewById(R.id.tvInfoBaris2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_user, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = list[position]

        // Status konfirmasi dari string resource
        val statusKonfirmasi = if (item.confStatus == 1) {
            context.getString(R.string.status_sudah)
        } else {
            context.getString(R.string.status_belum)
        }

        // Format baris pertama
        holder.tvInfoBaris1.text = context.getString(
            R.string.user_status_format,
            item.username,
            item.id,
            statusKonfirmasi
        )

        // Baris kedua DARURAT!!!
        holder.tvInfoBaris2.text = context.getString(R.string.darurat)

        // Warna kedua baris sama sesuai status
        val warna = if (item.confStatus == 1) Color.GREEN else Color.RED
        holder.tvInfoBaris1.setTextColor(warna)
        holder.tvInfoBaris2.setTextColor(warna)

        // Klik item → panggil openDetail di MainActivity
        holder.itemView.setOnClickListener {
            if (context is MainActivity) {
                context.openDetail(item)
            }
        }
    }

    override fun getItemCount() = list.size

    fun updateData(newList: List<Pantau>) {
        val diffCallback = PantauDiffCallback(list, newList)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        list = newList
        diffResult.dispatchUpdatesTo(this)
    }

    private class PantauDiffCallback(
        private val oldList: List<Pantau>,
        private val newList: List<Pantau>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].id == newList[newItemPosition].id
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }
}
