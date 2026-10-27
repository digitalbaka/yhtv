package com.yunhu.tv.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.yunhu.tv.R
import com.yunhu.tv.api.Api
import com.yunhu.tv.api.ChatMsg
import com.yunhu.tv.api.ContentType
import com.yunhu.tv.util.MarkdownLite
import com.yunhu.tv.util.Session

class MessageAdapter(
    private val onClick: (ChatMsg) -> Unit,
    private val onLongClick: (ChatMsg) -> Unit
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    companion object {
        private const val TYPE_LEFT = 0
        private const val TYPE_RIGHT = 1
        private const val MAX_MESSAGES = 200

        /** 是否为自己发送的消息 */
        fun isOwn(m: ChatMsg): Boolean =
            m.direction == "right" || (m.sender.chatId.isNotEmpty() && m.sender.chatId == Session.userId)

        /** 消息中可全屏查看的图片地址，非图片类返回空串 */
        fun imageUrlOf(m: ChatMsg): String = when (m.contentType) {
            ContentType.IMAGE -> Api.fullImageUrl(m.content.imageUrl)
            ContentType.STICKER -> Api.fullImageUrl(m.content.image)
            else -> ""
        }
    }

    private val items = mutableListOf<ChatMsg>()

    /** 全量设置（时间正序：旧 -> 新） */
    fun submitAll(list: List<ChatMsg>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** 顶部插入更早的历史消息 */
    fun prependOlder(list: List<ChatMsg>) {
        if (list.isEmpty()) return
        items.addAll(0, list)
        notifyItemRangeInserted(0, list.size)
    }

    fun firstMsgId(): String? = items.firstOrNull()?.msgId?.ifEmpty { null }

    fun has(msgId: String): Boolean = msgId.isNotEmpty() && items.any { it.msgId == msgId }

    /** 追加一条消息，msgId 重复时忽略。返回是否真正插入 */
    fun append(m: ChatMsg): Boolean {
        if (has(m.msgId)) return false
        items.add(m)
        // 限制内存中的消息条数，防止长时间使用 OOM 闪退
        while (items.size > MAX_MESSAGES) {
            items.removeAt(0)
            notifyItemRemoved(0)
        }
        notifyItemInserted(items.size - 1)
        return true
    }

    /** 流式消息：向已有消息追加文本 */
    fun applyStream(msgId: String, extra: String) {
        val i = items.indexOfFirst { it.msgId == msgId }
        if (i >= 0) {
            items[i].content.text += extra
            notifyItemChanged(i)
        }
    }

    /** 编辑消息：替换内容 */
    fun applyEdit(m: ChatMsg) {
        val i = items.indexOfFirst { it.msgId == m.msgId }
        if (i >= 0) {
            items[i].content = m.content
            items[i].editTimestamp = m.editTimestamp
            notifyItemChanged(i)
        }
    }

    fun markRecalled(msgId: String) {
        val i = items.indexOfFirst { it.msgId == msgId }
        if (i >= 0) {
            items[i].recallTimestamp = System.currentTimeMillis()
            notifyItemChanged(i)
        }
    }

    override fun getItemViewType(position: Int): Int =
        if (isOwn(items[position])) TYPE_RIGHT else TYPE_LEFT

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.iv_avatar)
        val sender: TextView = view.findViewById(R.id.tv_sender)
        val quote: TextView = view.findViewById(R.id.tv_quote)
        val text: TextView = view.findViewById(R.id.tv_text)
        val image: ImageView = view.findViewById(R.id.iv_image)
        val fileCard: TextView = view.findViewById(R.id.tv_file_card)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == TYPE_RIGHT) R.layout.item_msg_right else R.layout.item_msg_left
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val m = items[position]
        val own = getItemViewType(position) == TYPE_RIGHT

        // 复位复用状态
        h.text.visibility = View.GONE
        h.image.visibility = View.GONE
        h.fileCard.visibility = View.GONE
        h.quote.visibility = View.GONE
        h.text.text = ""
        h.text.setTextColor(ContextCompat.getColor(h.itemView.context, R.color.text_primary))

        val isTip = m.contentType == ContentType.TIP
        h.avatar.visibility = if (isTip) View.INVISIBLE else View.VISIBLE
        if (!own && !isTip) {
            h.sender.visibility = View.VISIBLE
            h.sender.text = m.sender.name
        } else {
            h.sender.visibility = View.GONE
        }
        h.avatar.load(Api.fullImageUrl(m.sender.avatarUrl)) {
            placeholder(R.drawable.bg_card)
            error(R.drawable.bg_card)
        }

        if (m.isRecalled) {
            h.text.visibility = View.VISIBLE
            h.text.text = "消息已撤回"
            h.text.setTextColor(ContextCompat.getColor(h.itemView.context, R.color.text_secondary))
        } else {
            bindContent(h, m)
            if (m.content.quoteMsgText.isNotEmpty()) {
                h.quote.visibility = View.VISIBLE
                h.quote.text = "引用: ${m.content.quoteMsgText}"
            }
        }

        h.itemView.setOnClickListener { onClick(m) }
        // 遥控器菜单键触发消息操作菜单（替换原先的长按）
        h.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                onLongClick(m)
                true
            } else false
        }
        FocusEffects.apply(h.itemView)
    }

    private fun bindContent(h: VH, m: ChatMsg) {
        when (m.contentType) {
            ContentType.TEXT -> showText(h, m.content.text)
            ContentType.MARKDOWN -> {
                showText(h, MarkdownLite.toPlain(m.content.text))
                // Markdown 中的图片直接展示，不再只显示 [图片] 占位文本
                MarkdownLite.extractImages(m.content.text).firstOrNull()?.let {
                    showImage(h, Api.fullImageUrl(it))
                }
            }
            ContentType.HTML -> {
                h.text.visibility = View.VISIBLE
                h.text.text = HtmlCompat.fromHtml(m.content.text, HtmlCompat.FROM_HTML_MODE_LEGACY)
            }
            ContentType.IMAGE -> {
                if (m.content.text.isNotEmpty()) showText(h, m.content.text)
                showImage(h, Api.fullImageUrl(m.content.imageUrl))
            }
            ContentType.STICKER -> showImage(h, Api.fullImageUrl(m.content.image))
            ContentType.FILE -> showFile(
                h,
                if (m.content.videoUrl.isNotEmpty()) "[视频] ${m.content.fileName}"
                else "[文件] ${m.content.fileName}\n${formatSize(m.content.fileSize)}"
            )
            ContentType.AUDIO -> showFile(h, "[语音] ${m.content.audioTime} 秒")
            ContentType.CALL -> showFile(h, "[通话] ${m.content.callStatusText.ifEmpty { "语音通话" }}")
            ContentType.FORM -> showText(h, "[表单消息，请在手机端查看]")
            ContentType.A2UI -> showText(h, "[互动消息，请在手机端查看]")
            ContentType.TIP -> showText(h, m.content.tip.ifEmpty { m.content.text })
            else -> showText(h, m.content.text.ifEmpty { "[暂不支持的消息类型 ${m.contentType}]" })
        }
    }

    private fun showText(h: VH, t: String) {
        h.text.visibility = View.VISIBLE
        h.text.text = t
    }

    private fun showImage(h: VH, url: String) {
        h.image.visibility = View.VISIBLE
        // size() 限制解码尺寸，配合视图上限，降低内存占用
        h.image.load(url) {
            size(840, 640)
            placeholder(R.drawable.bg_card)
            error(R.drawable.bg_card)
        }
    }

    private fun showFile(h: VH, t: String) {
        h.fileCard.visibility = View.VISIBLE
        h.fileCard.text = t
    }

    private fun formatSize(size: Long): String = when {
        size <= 0 -> ""
        size < 1024 -> "${size}B"
        size < 1024 * 1024 -> "${size / 1024}KB"
        else -> "%.1fMB".format(size / 1024.0 / 1024.0)
    }
}
