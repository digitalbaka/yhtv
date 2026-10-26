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

/**
 * 表情面板：常用 Emoji（点击插入输入框）+ 个人收藏表情（点击直接发送）。
 */
class StickerAdapter(
    private val onEmoji: (String) -> Unit,
    private val onPick: (Api.Expression) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_EMOJI = 0
        private const val TYPE_FAV = 1

        /** 常用 Emoji，收藏表情为空时面板也有内容可用 */
        val COMMON_EMOJI = listOf(
            "😀", "😄", "😁", "😂", "🤣", "😊", "😍", "😘",
            "😜", "🤔", "😴", "😷", "😎", "🥳", "😭", "😱",
            "😡", "👍", "👎", "👏", "🙏", "💪", "🤝", "✌️",
            "👌", "❤️", "💔", "🎉", "🎂", "🔥", "⭐", "🌹",
            "🍉", "🍺", "☕", "🐶", "🐱", "🐭", "🐮", "🐷"
        )
    }

    sealed class Row {
        data class Emoji(val ch: String) : Row()
        data class Fav(val exp: Api.Expression) : Row()
    }

    private val rows = mutableListOf<Row>()

    init {
        COMMON_EMOJI.forEach { rows.add(Row.Emoji(it)) }
    }

    /** 追加个人收藏表情 */
    fun submitFavorites(list: List<Api.Expression>) {
        rows.removeAll { it is Row.Fav }
        list.forEach { rows.add(Row.Fav(it)) }
        notifyDataSetChanged()
    }

    class EmojiVH(view: View) : RecyclerView.ViewHolder(view) {
        val emoji: TextView = view.findViewById(R.id.tv_emoji)
    }

    class FavVH(view: View) : RecyclerView.ViewHolder(view) {
        val img: ImageView = view.findViewById(R.id.iv_sticker)
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Emoji) TYPE_EMOJI else TYPE_FAV

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_EMOJI) {
            EmojiVH(inf.inflate(R.layout.item_emoji, parent, false))
        } else {
            FavVH(inf.inflate(R.layout.item_sticker, parent, false))
        }
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Emoji -> {
                (h as EmojiVH).emoji.text = row.ch
                h.itemView.setOnClickListener { onEmoji(row.ch) }
            }
            is Row.Fav -> {
                (h as FavVH).img.load(Api.fullImageUrl(row.exp.url)) {
                    placeholder(R.drawable.bg_card)
                    error(R.drawable.bg_card)
                }
                h.itemView.setOnClickListener { onPick(row.exp) }
            }
        }
        FocusEffects.apply(h.itemView, 1.12f)
    }
}
