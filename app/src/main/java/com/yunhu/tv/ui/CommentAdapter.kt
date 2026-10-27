package com.yunhu.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.yunhu.tv.R
import com.yunhu.tv.api.Api

/** 文章评论列表（含嵌套回复） */
class CommentAdapter : RecyclerView.Adapter<CommentAdapter.VH>() {

    private val items = mutableListOf<Api.Comment>()

    fun submit(list: List<Api.Comment>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.iv_comment_avatar)
        val name: TextView = view.findViewById(R.id.tv_comment_name)
        val time: TextView = view.findViewById(R.id.tv_comment_time)
        val content: TextView = view.findViewById(R.id.tv_comment_content)
        val meta: TextView = view.findViewById(R.id.tv_comment_meta)
        val replies: LinearLayout = view.findViewById(R.id.ll_replies)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_comment, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        h.name.text = c.senderNickname.ifEmpty { c.senderId }
        h.time.text = c.createTimeText
        h.content.text = c.content
        if (c.likeNum > 0 || c.amountNum > 0) {
            h.meta.visibility = View.VISIBLE
            h.meta.text = "赞 ${c.likeNum} · 币 ${c.amountNum}"
        } else {
            h.meta.visibility = View.GONE
        }
        h.avatar.load(c.senderAvatar) { placeholder(R.drawable.bg_card); error(R.drawable.bg_card) }
        bindReplies(h.replies, c.replies)
    }

    /** 把回复动态渲染到 ll_replies，复用 item_comment_reply 布局 */
    private fun bindReplies(container: LinearLayout, replies: List<Api.Comment>) {
        container.removeAllViews()
        if (replies.isEmpty()) return
        val ctx = container.context
        val density = ctx.resources.displayMetrics.density
        replies.forEach { r ->
            val row = LayoutInflater.from(ctx).inflate(R.layout.item_comment_reply, container, false)
            row.findViewById<ImageView>(R.id.iv_reply_avatar)
                .load(r.senderAvatar) { placeholder(R.drawable.bg_card); error(R.drawable.bg_card) }
            row.findViewById<TextView>(R.id.tv_reply_name).text = r.senderNickname.ifEmpty { r.senderId }
            row.findViewById<TextView>(R.id.tv_reply_time).text = r.createTimeText
            row.findViewById<TextView>(R.id.tv_reply_content).text = r.content
            // 回复项之间留一点间距
            (row.layoutParams as? LinearLayout.LayoutParams)?.topMargin = (6 * density).toInt()
            container.addView(row)
        }
    }
}
