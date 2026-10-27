package com.yunhu.tv.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.yunhu.tv.ui.AddFriendActivity
import com.yunhu.tv.ui.PostDetailActivity

/**
 * 解析并跳转应用内 yunhu:// 链接。
 * 支持：
 *  yunhu://chat-add?id=xxx&type=user|group|bot
 *  yunhu://post-detail?id=xxx
 */
object YunhuRouter {

    fun extractYunhuLink(text: String): String? {
        val i = text.indexOf("yunhu://")
        if (i < 0) return null
        // 取到空白或常见分隔符为止
        val end = text.indexOfAny(charArrayOf(' ', '\n', '\t', '"', '<', '>', '，', '。'), i)
            .let { if (it < 0) text.length else it }
        return text.substring(i, end)
    }

    fun open(context: Context, raw: String): Boolean {
        val link = if (raw.startsWith("yunhu://")) raw else extractYunhuLink(raw) ?: return false
        return try {
            val uri = Uri.parse(link)
            when (uri.host) {
                "chat-add" -> {
                    val id = uri.getQueryParameter("id").orEmpty()
                    val type = uri.getQueryParameter("type").orEmpty()
                    if (id.isEmpty()) return false
                    context.startActivity(
                        Intent(context, AddFriendActivity::class.java)
                            .putExtra(AddFriendActivity.EXTRA_ID, id)
                            .putExtra(AddFriendActivity.EXTRA_TYPE, type)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    true
                }
                "post-detail" -> {
                    val id = uri.getQueryParameter("id")?.toLongOrNull() ?: return false
                    context.startActivity(
                        Intent(context, PostDetailActivity::class.java)
                            .putExtra(PostDetailActivity.EXTRA_POST_ID, id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    true
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }
}
