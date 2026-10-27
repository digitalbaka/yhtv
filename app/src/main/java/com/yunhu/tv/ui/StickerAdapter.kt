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
 * 表情面板：Emoji Tab（点击插入输入框）+ 收藏表情 Tab（点击直接发送）。
 * 两个 Tab 各自独立数据源，由 ChatActivity 切换 Tab 时调用 setTab()。
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

        const val TAB_EMOJI = 0
        const val TAB_FAV = 1
    }

    private var currentTab = TAB_EMOJI
    private val favList = mutableListOf<Api.Expression>()

    /** 当前激活的 Tab（供外部在刷新收藏数据时复用） */
    fun currentTab(): Int = currentTab

    /** Tab 切换后刷新列表；返回是否为空（ChatActivity 据此显示空占位） */
    fun setTab(tab: Int): Boolean {
        if (currentTab != tab) {
            currentTab = tab
            notifyDataSetChanged()
        }
        return itemCount == 0
    }

    /** 追加个人收藏表情（Fav Tab 的数据源） */
    fun submitFavorites(list: List<Api.Expression>) {
        favList.clear()
        favList.addAll(list)
        if (currentTab == TAB_FAV) notifyDataSetChanged()
    }

    class EmojiVH(view: View) : RecyclerView.ViewHolder(view) {
        val emoji: TextView = view.findViewById(R.id.tv_emoji)
    }

    class FavVH(view: View) : RecyclerView.ViewHolder(view) {
        val img: ImageView = view.findViewById(R.id.iv_sticker)
    }

    override fun getItemViewType(position: Int): Int =
        if (currentTab == TAB_EMOJI) TYPE_EMOJI else TYPE_FAV

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_EMOJI) {
            EmojiVH(inf.inflate(R.layout.item_emoji, parent, false))
        } else {
            FavVH(inf.inflate(R.layout.item_sticker, parent, false))
        }
    }

    override fun getItemCount(): Int = if (currentTab == TAB_EMOJI) COMMON_EMOJI.size else favList.size

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        if (currentTab == TAB_EMOJI) {
            val vh = h as EmojiVH
            vh.emoji.text = COMMON_EMOJI[position]
            h.itemView.setOnClickListener { onEmoji(COMMON_EMOJI[position]) }
        } else {
            val exp = favList[position]
            val vh = h as FavVH
            vh.img.load(Api.fullImageUrl(exp.url)) {
                placeholder(R.drawable.bg_card)
                error(R.drawable.bg_card)
            }
            h.itemView.setOnClickListener { onPick(exp) }
        }
        FocusEffects.apply(h.itemView, 1.12f)
    }
}
