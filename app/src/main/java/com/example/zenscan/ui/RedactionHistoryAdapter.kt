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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class RedactionHistoryAdapter(
    private val preferencesManager: PreferencesManager,
    private val onItemClick: (File) -> Unit,
    private val onItemLongClick: (File) -> Unit
) : ListAdapter<File, RedactionHistoryAdapter.ViewHolder>(DiffCallback()) {

    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.clear()
    }

    inner class ViewHolder(
        private val binding: ItemRecentRedactionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var decodeJob: Job? = null

        fun bind(file: File) {
            decodeJob?.cancel()
            val path = file.absolutePath
            binding.thumbnail.tag = path
            binding.thumbnail.setImageBitmap(null)

            decodeJob = adapterScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                    BitmapFactory.decodeFile(path, opts)
                }
                if (binding.thumbnail.tag == path) {
                    binding.thumbnail.setImageBitmap(bmp)
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

        fun clear() {
            decodeJob?.cancel()
            decodeJob = null
        }

        private fun parseFilenameMetadata(file: File): Pair<String, String> {
            val name = file.name
            val parts = name.removePrefix("ZenScan_").removeSuffix(".jpg").split("_")
            val timestamp = parts.getOrNull(0)?.toLongOrNull() ?: file.lastModified()
            val formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy · h:mm a", Locale.getDefault())
            val dateText = Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.systemDefault())
                .format(formatter)

            val metadata = preferencesManager.getHistoryMetadata(name)
                ?: parts.getOrNull(1)?.toIntOrNull()?.let { count ->
                    if (count > 0) "$count sensitive item(s) protected" else "Custom brush/manual redaction"
                }
                ?: "Redacted Image (${file.length() / 1024} KB)"

            return Pair(dateText, metadata)
        }
    }
}
