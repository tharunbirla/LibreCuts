package com.tharunbirla.librecuts

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

class FrameAdapter(
    private var frameBitmaps: List<Bitmap>,
    var itemWidth: Int
) : RecyclerView.Adapter<FrameAdapter.FrameViewHolder>() {

    class FrameViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.frameImageView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FrameViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_frame, parent, false)
        return FrameViewHolder(view)
    }

    override fun onBindViewHolder(holder: FrameViewHolder, position: Int) {
        holder.itemView.layoutParams = (holder.itemView.layoutParams ?: ViewGroup.LayoutParams(
            itemWidth,
            ViewGroup.LayoutParams.MATCH_PARENT
        )).apply {
            width = itemWidth
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }

        if (position < frameBitmaps.size) {
            holder.imageView.setImageBitmap(frameBitmaps[position])
            holder.imageView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        } else {
            holder.imageView.setImageBitmap(null)
            holder.imageView.setBackgroundColor(android.graphics.Color.parseColor("#2C2C2C"))
        }
    }

    override fun getItemCount(): Int = 15

    fun updateFrames(newFrames: List<Bitmap>) {
        this.frameBitmaps = newFrames
        notifyDataSetChanged()
    }

    fun addFrame(bitmap: Bitmap) {
        val mut = this.frameBitmaps.toMutableList()
        mut.add(bitmap)
        this.frameBitmaps = mut
        notifyItemChanged(mut.size - 1)
    }
}
