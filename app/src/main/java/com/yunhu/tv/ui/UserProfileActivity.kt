package com.yunhu.tv.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
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

/** 用户详情页：资料展示 + 发消息 + 加好友 */
class UserProfileActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_USER_ID = "user_id"
    }

    private lateinit var binding: ActivityUserProfileBinding
    private var userId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userId = intent.getStringExtra(EXTRA_USER_ID).orEmpty()
        if (userId.isEmpty()) { finish(); return }

        // 自己：隐藏加好友按钮
        if (userId == Session.userId) binding.btnAddFriend.visibility = android.view.View.GONE

        binding.btnChat.setOnClickListener {
            startActivity(
                Intent(this, ChatActivity::class.java)
                    .putExtra(ChatActivity.EXTRA_CHAT_ID, userId)
                    .putExtra(ChatActivity.EXTRA_CHAT_TYPE, ChatType.USER)
                    .putExtra(ChatActivity.EXTRA_CHAT_NAME, binding.tvName.text.toString())
            )
        }
        binding.btnAddFriend.setOnClickListener { showApplyDialog() }

        load()
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                val u = Api.getUser(userId)
                binding.tvLoading.visibility = android.view.View.GONE
                binding.ivAvatar.load(u.avatarUrl) { placeholder(R.drawable.bg_card); error(R.drawable.bg_card) }
                binding.tvName.text = u.name.ifEmpty { "用户 $userId" }
                binding.tvId.text = "ID: ${u.id.ifEmpty { userId }}"
                binding.tvVip.visibility = if (u.isVip) android.view.View.VISIBLE else android.view.View.GONE
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
            } catch (e: Exception) {
                binding.tvLoading.text = "加载失败: ${e.message}"
            }
        }
    }

    private fun showApplyDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "申请备注（可选）"
        }
        AlertDialog.Builder(this)
            .setTitle("添加好友")
            .setMessage("向 ${binding.tvName.text} 发送好友申请")
            .setView(input)
            .setPositiveButton("发送") { _, _ ->
                lifecycleScope.launch {
                    try {
                        Api.friendApply(userId, ChatType.USER, input.text.toString().trim())
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
