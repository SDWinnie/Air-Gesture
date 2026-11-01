package com.sdwinnie.airgesture.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sdwinnie.airgesture.R
import com.sdwinnie.airgesture.databinding.ItemRadialSlotBinding
import com.sdwinnie.airgesture.input.RadialAppSlotManager
import com.sdwinnie.airgesture.model.RadialAppSlot

class RadialSlotAdapter(
    private val slotManager: RadialAppSlotManager,
    private val onSlotClicked: (RadialAppSlot) -> Unit
) : RecyclerView.Adapter<RadialSlotAdapter.SlotViewHolder>() {

    private val items = ArrayList<RadialAppSlot>(15)

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newSlots: List<RadialAppSlot>) {
        items.clear()
        items.addAll(newSlots)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlotViewHolder {
        val binding = ItemRadialSlotBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SlotViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SlotViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class SlotViewHolder(
        private val binding: ItemRadialSlotBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("SetTextI18n")
        fun bind(slot: RadialAppSlot) {
            binding.tvSlotAngleBadge.text = "#${slot.slotIndex + 1} • ${slot.centerAngleDegrees.toInt()}°"
            binding.tvSlotAppName.text = slot.label
            binding.tvSlotPackage.text = slot.packageName.ifBlank { "Tap to assign app" }

            val iconBmp = slotManager.getCachedAppIcon(slot.packageName, 72)
            if (iconBmp != null) {
                binding.ivSlotAppIcon.setImageBitmap(iconBmp)
            } else {
                binding.ivSlotAppIcon.setImageResource(R.drawable.ic_hand_gesture)
            }

            binding.root.setOnClickListener {
                onSlotClicked(slot)
            }
        }
    }
}
