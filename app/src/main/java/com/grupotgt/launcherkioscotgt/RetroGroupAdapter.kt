package com.grupotgt.launcherkioscotgt

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Adaptador exclusivamente visual para el selector de grupo del Panel IT. */
class RetroGroupAdapter(
    context: Context,
    items: List<String>,
    private val selectedValue: () -> String
) : ArrayAdapter<String>(context, 0, items) {

    override fun areAllItemsEnabled(): Boolean = false

    override fun isEnabled(position: Int): Boolean = !isHeader(getItem(position))

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        bindView(position, convertView, parent)

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
        bindView(position, convertView, parent)

    private fun bindView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = (convertView as? TextView)
            ?: LayoutInflater.from(context).inflate(
                R.layout.item_retro_group_dropdown,
                parent,
                false
            ) as TextView
        val item = getItem(position).orEmpty()
        val header = isHeader(item)
        val selected = !header && item == selectedValue()

        row.text = item
        row.isEnabled = !header
        row.contentDescription = if (header) "Cabecera Grupo" else "Grupo $item"
        row.setTextColor(Color.parseColor(if (header) "#70FF83" else "#B9F7C1"))
        row.setTypeface(Typeface.MONOSPACE, if (header) Typeface.BOLD else Typeface.NORMAL)
        row.textSize = if (header) 10f else 12f
        row.background = ContextCompat.getDrawable(
            context,
            when {
                header -> R.drawable.bg_it_dropdown_header
                selected -> R.drawable.bg_it_dropdown_item_selected
                else -> R.drawable.bg_it_dropdown_item
            }
        )
        return row
    }

    private fun isHeader(item: String?): Boolean = item?.trim()?.equals("Grupo", true) == true
}
