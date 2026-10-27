package com.yunhu.tv.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.yunhu.tv.R
import com.yunhu.tv.api.Api
import com.yunhu.tv.api.ChatType
import com.yunhu.tv.databinding.ActivityUserProfileBinding
import com.yunhu.tv.util.Session
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 详情页：用户 / 机器人 / 群组通用，按钮文案随类型变化 */
class UserProfileActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_CHAT_TYPE = "chat_type"
        const val EXTRA_NAME = "name"
        const val EXTRA_AVATAR = "avatar"
    }

    private lateinit var binding: ActivityUserProfileBinding
    private var userId = ""
    private var chatType = ChatType.USER
    private var presetName = ""
    private var presetAvatar = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userId = intent.getStringExtra(EXTRA_USER_ID).orEmpty()
        chatType = intent.getIntExtra(EXTRA_CHAT_TYPE, ChatType.USER)
        presetName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        presetAvatar = intent.getStringExtra(EXTRA_AVATAR).orEmpty()
        if (userId.isEmpty()) { finish(); return }

        // 标题随类型变化
        binding.tvTitle.text = when (chatType) {
            ChatType.GROUP -> "群组详情"
            ChatType.BOT -> "机器人详情"
            else -> "用户详情"
        }

        // 加好友按钮文案随类型变化
        binding.btnAddFriend.text = when (chatType) {
            ChatType.GROUP -> "加群聊"
            ChatType.BOT -> "添加机器人"
            else -> "加为好友"
        }

        // 自己：隐藏加好友按钮（群组不隐藏）
        if (chatType == ChatType.USER && userId == Session.userId) {
            binding.btnAddFriend.visibility = View.GONE
        }

        binding.btnChat.setOnClickListener {
            val type = when (chatType) {
                ChatType.GROUP -> ChatType.GROUP
                ChatType.BOT -> ChatType.BOT
                else -> ChatType.USER
            }
            startActivity(
                Intent(this, ChatActivity::class.java)
                    .putExtra(ChatActivity.EXTRA_CHAT_ID, userId)
                    .putExtra(ChatActivity.EXTRA_CHAT_TYPE, type)
                    .putExtra(ChatActivity.EXTRA_CHAT_NAME, binding.tvName.text.toString())
            )
        }
        binding.btnAddFriend.setOnClickListener { showApplyDialog() }

        load()
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                binding.tvLoading.visibility = View.VISIBLE
                when (chatType) {
                    ChatType.GROUP -> loadGroup()
                    ChatType.BOT -> loadBot()
                    else -> loadUser()
                }
                binding.tvLoading.visibility = View.GONE
            } catch (e: Exception) {
                binding.tvLoading.text = "加载失败: ${e.message}"
            }
        }
    }

    /** 机器人没有独立详情接口，直接展示搜索结果中的基础信息 */
    private fun loadBot() {
        binding.ivAvatar.load(presetAvatar) { placeholder(R.drawable.bg_card); error(R.drawable.bg_card) }
        binding.tvName.text = presetName.ifEmpty { "机器人 $userId" }
        binding.tvId.text = "ID: $userId"
        binding.tvVip.visibility = View.GONE
        binding.tvIntroduction.text = "机器人"
        binding.tvGender.text = ""
        binding.tvBirthday.text = ""
        binding.tvCity.text = ""
        binding.tvRegister.text = ""
        binding.tvOnline.text = ""
        binding.tvIp.text = ""
    }

    private suspend fun loadUser() {
        val u = Api.getUser(userId)
        binding.ivAvatar.load(u.avatarUrl) { placeholder(R.drawable.bg_card); error(R.drawable.bg_card) }
        binding.tvName.text = u.name.ifEmpty { "用户 $userId" }
        binding.tvId.text = "ID: ${u.id.ifEmpty { userId }}"
        binding.tvVip.visibility = if (u.isVip) View.VISIBLE else View.GONE
        binding.tvIntroduction.text = if (u.profile.introduction.isNotEmpty()) u.profile.introduction else "暂无简介"
        binding.tvGender.text = "性别: " + when (u.profile.gender) {
            1 -> "男"
            2 -> "女"
            else -> "未设置"
        }
        binding.tvBirthday.text = if (u.profile.birthday > 0) {
            "生日: " + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(u.profile.birthday * 1000))
        } else "生日: 未设置"
        val city = listOf(u.profile.city, u.profile.district).filter { it.isNotEmpty() }.joinToString(" ")
        binding.tvCity.text = "地区: " + if (city.isEmpty()) "未设置" else city
        binding.tvRegister.text = if (u.registerTime.isNotEmpty()) "注册时间: ${u.registerTime}" else ""
        binding.tvOnline.text = "在线天数: ${u.onlineDay}  ·  连续在线: ${u.continuousOnlineDay} 天"
        binding.tvIp.text = if (u.ipGeo.isNotEmpty()) "IP 归属地: ${u.ipGeo}" else ""
    }

    private suspend fun loadGroup() {
        val g = Api.groupInfo(userId)
        binding.ivAvatar.load(g.avatarUrl) { placeholder(R.drawable.bg_card); error(R.drawable.bg_card) }
        binding.tvName.text = g.name.ifEmpty { "群组 $userId" }
        binding.tvId.text = "ID: ${g.groupId.ifEmpty { userId }}"
        binding.tvVip.visibility = View.GONE
        binding.tvIntroduction.text = if (g.introduction.isNotEmpty()) g.introduction else "暂无简介"
        binding.tvGender.text = "成员数: ${g.headcount}"
        binding.tvBirthday.text = "分类: ${g.categoryName.ifEmpty { "未分类" }}"
        binding.tvCity.text = if (g.isPrivate) "类型: 私密群" else "类型: 公开群"
        binding.tvRegister.text = if (g.groupCode.isNotEmpty()) "群口令: ${g.groupCode}" else ""
        binding.tvOnline.text = if (g.directJoin) "加群方式: 直接加入" else "加群方式: 需审核"
        binding.tvIp.text = ""
    }

    private fun showApplyDialog() {
        val typeName = when (chatType) {
            ChatType.GROUP -> "群聊"
            ChatType.BOT -> "机器人"
            else -> "好友"
        }
        val action = when (chatType) {
            ChatType.GROUP -> "加入"
            else -> "添加"
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "申请备注（可选）"
        }
        AlertDialog.Builder(this)
            .setTitle("$action$typeName")
            .setMessage("确定要$action ${binding.tvName.text} 吗？")
            .setView(input)
            .setPositiveButton(action) { _, _ ->
                lifecycleScope.launch {
                    try {
                        Api.friendApply(userId, chatType, input.text.toString().trim())
                        Toast.makeText(this@UserProfileActivity, "申请已发送", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@UserProfileActivity, e.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
