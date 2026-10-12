package com.sanabria.shadervulkan.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sanabria.shadervulkan.R
import com.sanabria.shadervulkan.model.ShaderItem
import com.sanabria.shadervulkan.ui.AutoScroll

/**
 * Lista de presets y carpetas al estilo de un listado de terminal: el activo lleva el cursor ► y la fila resaltada
 * (isActivated → bg_crt_row), las carpetas llevan <DIR> y ".." sube un nivel.
 */
class ShaderItemAdapter(
    private val items: List<ShaderItem>,
    private val onShaderSelected: (ShaderItem) -> Unit
) : RecyclerView.Adapter<ShaderItemAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_shader, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], onShaderSelected)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val marker: TextView = itemView.findViewById(R.id.txt_shader_marker)
        private val nameText: TextView = itemView.findViewById(R.id.txt_shader_name)
        private val infoText: TextView = itemView.findViewById(R.id.txt_shader_info)
        private val tagText: TextView = itemView.findViewById(R.id.txt_shader_tag)

        init {
            AutoScroll.attach(nameText)
            AutoScroll.attach(infoText)
        }

        fun bind(item: ShaderItem, onSelected: (ShaderItem) -> Unit) {
            val isUp = item.id == ShaderItem.FOLDER_UP_ID
            nameText.text = if (isUp) ".." else ShaderItem.cleanName(item.name)

            val info = when {
                isUp -> item.notes.ifBlank { itemView.context.getString(R.string.list_info_up) }
                item.id == ShaderItem.EXTERNAL_ROOT_ID -> itemView.context.getString(R.string.list_info_external_root)
                item.isFolder -> ""
                item.path.startsWith("shaders/") -> itemView.context.getString(R.string.list_info_builtin)
                else -> itemView.context.getString(R.string.list_info_external)
            }
            infoText.text = info
            infoText.visibility = if (info.isEmpty()) View.GONE else View.VISIBLE

            tagText.visibility = if (item.isFolder) View.VISIBLE else View.GONE
            marker.visibility = if (item.isActive) View.VISIBLE else View.INVISIBLE
            itemView.isActivated = item.isActive

            itemView.setOnClickListener { onSelected(item) }
        }
    }
}
