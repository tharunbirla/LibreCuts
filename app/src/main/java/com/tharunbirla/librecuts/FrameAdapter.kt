package com.tharunbirla.librecuts

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.tharunbirla.librecuts.models.Clip
import com.tharunbirla.librecuts.models.MediaType

class FrameAdapter(
    private var clips: List<Clip>,
    private var thumbnails: Map<String, Bitmap> = emptyMap(),
    private val onClipClick: (Clip) -> Unit
) : RecyclerView.Adapter<FrameAdapter.FrameViewHolder>() {

    class FrameViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.frameImageView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FrameViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_frame, parent, false)
        return FrameViewHolder(view)
    }

    override fun onBindViewHolder(holder: FrameViewHolder, position: Int) {
        val clip = clips[position]

        if (clip.mediaType == MediaType.VIDEO) {
             val bitmap = thumbnails[clip.id]
             if (bitmap != null) {
                 holder.imageView.setImageBitmap(bitmap)
                 holder.imageView.scaleType = ImageView.ScaleType.CENTER_CROP
             } else {
                 holder.imageView.setBackgroundColor(Color.DKGRAY)
                 holder.imageView.setImageResource(0)
             }
        } else if (clip.mediaType == MediaType.AUDIO) {
             holder.imageView.setBackgroundColor(Color.GREEN)
             holder.imageView.setImageResource(R.drawable.ic_audio_24)
        } else {
             holder.imageView.setBackgroundColor(Color.BLUE)
             holder.imageView.setImageResource(R.drawable.ic_text_24)
        }

        holder.itemView.setOnClickListener { onClipClick(clip) }
    }

    override fun getItemCount(): Int = clips.size

    fun updateClips(newClips: List<Clip>) {
        clips = newClips
        notifyDataSetChanged()
    }

    fun updateThumbnails(newThumbnails: Map<String, Bitmap>) {
        thumbnails = newThumbnails
        notifyDataSetChanged() // Ideally notify item changed for specific ones
    }
}
