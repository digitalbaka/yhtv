package com.yunhu.tv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.yunhu.tv.R
import com.yunhu.tv.api.Api

/** 个人收藏表情面板 */
class StickerAdapter(
    private val onPick: (Api.Expression) -> Unit
) : RecyclerView.Adapter<StickerAdapter.VH>() {

    private val items = mutableListOf<Api.Expression>()

    fun submit(list: List<Api.Expression>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val img: ImageView = view.findViewById(R.id.iv_sticker)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_sticker, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.img.load(Api.fullImageUrl(e.url)) { placeholder(R.drawable.bg_card); error(R.drawable.bg_card) }
        h.itemView.setOnClickListener { onPick(e) }
        FocusEffects.apply(h.itemView, 1.12f)
    }
}
