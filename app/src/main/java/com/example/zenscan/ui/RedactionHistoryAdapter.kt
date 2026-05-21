package com.example.zenscan.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.zenscan.databinding.ItemRecentRedactionBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RedactionHistoryAdapter(
    private val onItemClick: (File) -> Unit,
    private val onItemLongClick: (File) -> Unit
) : ListAdapter<File, RedactionHistoryAdapter.ViewHolder>(DiffCallback()) {

    fun updateData(newFiles: List<File>) {
        submitList(newFiles)
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
            // Load thumbnail using Coil library securely
            binding.thumbnail.load(file) {
                crossfade(true)
            }

            // Display formatted metadata parsed directly from filename
            val (dateText, detailsText) = parseFilenameMetadata(file)
            binding.fileName.text = dateText
            binding.redactionCount.text = detailsText

            // Trigger action sheet on card select
            binding.root.setOnClickListener {
                onItemClick(file)
            }

            // Trigger delete option on long click
            binding.root.setOnLongClickListener {
                onItemLongClick(file)
                true
            }
        }

        private fun parseFilenameMetadata(file: File): Pair<String, String> {
            val name = file.name
            // Protocol check: ZenScan_1779287415835_3_redactions.jpg
            if (name.startsWith("ZenScan_") && name.endsWith(".jpg")) {
                try {
                    val payload = name.removePrefix("ZenScan_").removeSuffix(".jpg")
                    val parts = payload.split("_")
                    if (parts.size >= 3) {
                        val timestamp = parts[0].toLongOrNull()
                        val count = parts[1].toIntOrNull()

                        val formattedDate = if (timestamp != null) {
                            val sdf = SimpleDateFormat("MMM dd, yyyy · h:mm a", Locale.getDefault())
                            sdf.format(Date(timestamp))
                        } else {
                            "Recent Redaction"
                        }

                        val details = if (count != null && count > 0) {
                            "$count sensitive item(s) protected"
                        } else {
                            "Custom brush/manual redaction"
                        }

                        return Pair(formattedDate, details)
                    }
                } catch (e: Exception) {
                    // Fallback to basic processing below
                }
            }

            // Standard fallback using system file parameters
            val sdf = SimpleDateFormat("MMM dd, yyyy · h:mm a", Locale.getDefault())
            val formattedDate = sdf.format(Date(file.lastModified()))
            val sizeKb = file.length() / 1024
            return Pair(formattedDate, "Redacted Image (${sizeKb} KB)")
        }
    }
}
