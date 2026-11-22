package com.m3u8.liteplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FileAdapter(
    private val items: List<MediaFile>,
    private val onClick: (MediaFile) -> Unit
) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.fileThumb)
        val name: TextView = view.findViewById(R.id.fileName)
        val path: TextView = view.findViewById(R.id.filePath)
        val size: TextView = view.findViewById(R.id.fileSize)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val key = item.uri.toString()

        holder.name.text = item.name
        holder.path.text = item.path
        holder.size.text = formatSize(item.size)
        holder.itemView.setOnClickListener { onClick(item) }

        // 防止 RecyclerView 复用时旧回调把图设错行
        holder.thumb.tag = key
        val cached = ThumbnailLoader.get(key)
        if (cached != null) {
            holder.thumb.setImageBitmap(cached)
            return
        }
        holder.thumb.setImageResource(android.R.drawable.ic_menu_gallery)
        ThumbnailLoader.load(holder.itemView.context, item) { bitmap ->
            if (holder.thumb.tag == key && bitmap != null) {
                holder.thumb.setImageBitmap(bitmap)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
        else -> String.format("%.2f GB", bytes / 1073741824.0)
    }
}
