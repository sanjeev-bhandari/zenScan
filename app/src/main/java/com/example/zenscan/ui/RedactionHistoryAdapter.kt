package com.example.zenscan.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.zenscan.data.PreferencesManager
import com.example.zenscan.databinding.ItemRecentRedactionBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RedactionHistoryAdapter(
    private val preferencesManager: PreferencesManager,
    private val onItemClick: (File) -> Unit,
    private val onItemLongClick: (File) -> Unit
) : ListAdapter<File, RedactionHistoryAdapter.ViewHolder>(DiffCallback()) {

    companion object {
        private val dateFormat = SimpleDateFormat("MMM dd, yyyy · h:mm a", Locale.getDefault())
    }

    private class DiffCallback : DiffUtil.ItemCallback<File>() {
        override fun areItemsTheSame(oldItem: File, newItem: File): Boolean =
            oldItem.absolutePath == newItem.absolutePath

        override fun areContentsTheSame(oldItem: File, newItem: File): Boolean =
            oldItem.lastModified() == newItem.lastModified() && oldItem.length() == newItem.length()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecentRedactionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemRecentRedactionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(file: File) {
            val tag = file.absolutePath
            binding.thumbnail.tag = tag
            CoroutineScope(Dispatchers.IO).launch {
                val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                val bmp = BitmapFactory.decodeFile(tag, opts)
                withContext(Dispatchers.Main) {
                    if (binding.thumbnail.tag == tag) {
                        binding.thumbnail.setImageBitmap(bmp)
                    }
                }
            }

            val (dateText, detailsText) = parseFilenameMetadata(file)
            binding.fileName.text = dateText
            binding.fileName.contentDescription = "Saved on $dateText"
            binding.redactionCount.text = detailsText
            binding.root.contentDescription = "Redacted image saved on $dateText. $detailsText."

            binding.root.setOnClickListener { onItemClick(file) }
            binding.root.setOnLongClickListener {
                onItemLongClick(file)
                true
            }
        }

        private fun parseFilenameMetadata(file: File): Pair<String, String> {
            val name = file.name
            val parts = name.removePrefix("ZenScan_").removeSuffix(".jpg").split("_")
            val timestamp = parts.getOrNull(0)?.toLongOrNull() ?: file.lastModified()
            val dateText = dateFormat.format(Date(timestamp))

            val metadata = preferencesManager.getHistoryMetadata(name)
                ?: parts.getOrNull(1)?.toIntOrNull()?.let { count ->
                    if (count > 0) "$count sensitive item(s) protected" else "Custom brush/manual redaction"
                }
                ?: "Redacted Image (${file.length() / 1024} KB)"

            return Pair(dateText, metadata)
        }
    }
}
