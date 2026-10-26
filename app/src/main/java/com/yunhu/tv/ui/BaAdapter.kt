package com.yunhu.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.yunhu.tv.R
import com.yunhu.tv.api.Api

/** 社区分区列表 */
class BaAdapter(
    private val onClick: (Api.Ba) -> Unit
) : RecyclerView.Adapter<BaAdapter.VH>() {

    private val items = mutableListOf<Api.Ba>()

    fun submit(list: List<Api.Ba>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.iv_ba_avatar)
        val name: TextView = view.findViewById(R.id.tv_ba_name)
        val meta: TextView = view.findViewById(R.id.tv_ba_meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_ba, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val ba = items[position]
        h.name.text = ba.name.ifEmpty { "分区 ${ba.id}" }
        h.meta.text = "成员 ${ba.memberNum} · 文章 ${ba.postNum}"
        h.avatar.load(ba.avatar) { placeholder(R.drawable.bg_card); error(R.drawable.bg_card) }
        h.itemView.setOnClickListener { onClick(ba) }
        FocusEffects.apply(h.itemView)
    }
}
