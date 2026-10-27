package com.yunhu.tv.ui

import android.content.ClipData
import android.content.ClipboardManager
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
import com.yunhu.tv.api.GroupInfo
import com.yunhu.tv.databinding.ActivityGroupSettingsBinding
import kotlinx.coroutines.launch

/** 群聊设置页 */
class GroupSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GROUP_ID = "group_id"
    }

    private lateinit var binding: ActivityGroupSettingsBinding
    private var groupId = ""
    private var info: GroupInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        groupId = intent.getStringExtra(EXTRA_GROUP_ID).orEmpty()
        if (groupId.isEmpty()) { finish(); return }

        binding.rowMembers.setOnClickListener {
            startActivity(Intent(this, GroupMembersActivity::class.java).putExtra(GroupMembersActivity.EXTRA_GROUP_ID, groupId))
        }
        binding.rowMyNick.setOnClickListener { editMyNick() }
        binding.rowEditGroup.setOnClickListener { editGroup() }
        binding.btnCopyIntro.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("intro", info?.introduction.orEmpty()))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }
        binding.btnQuit.setOnClickListener { confirmQuit() }
        binding.swDisturb.setOnCheckedChangeListener { _, isChecked ->
            val target = if (isChecked) 1 else 0
            lifecycleScope.launch {
                try {
                    Api.setConversationNotify(groupId, target)
                } catch (e: Exception) {
                    binding.swDisturb.isChecked = !isChecked
                    Toast.makeText(this@GroupSettingsActivity, "设置失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        load()
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                val g = Api.groupInfo(groupId)
                info = g
                binding.tvLoading.visibility = android.view.View.GONE
                binding.ivGroupAvatar.load(g.avatarUrl) { placeholder(R.drawable.bg_card); error(R.drawable.bg_card) }
                binding.tvGroupName.text = g.name
                binding.tvGroupId.text = "ID: ${g.groupId}"
                binding.tvGroupCount.text = "${g.headcount} 名成员"
                binding.tvMemberCount.text = "${g.headcount}"
                binding.tvIntro.text = g.introduction.ifEmpty { "暂无简介" }
                binding.swDisturb.isChecked = g.doNotDisturb
                binding.tvMyNick.text = g.myGroupNickname.ifEmpty { "未设置" }
                // 群主/管理员显示编辑入口
                if (g.permissionLevel >= 2) {
                    binding.rowEditGroup.visibility = android.view.View.VISIBLE
                }
            } catch (e: Exception) {
                binding.tvLoading.text = "加载失败: ${e.message}"
            }
        }
    }

    private fun editMyNick() {
        val cur = info?.myGroupNickname.orEmpty()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(cur)
            hint = "我的群昵称"
        }
        AlertDialog.Builder(this)
            .setTitle("修改我的群昵称")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                lifecycleScope.launch {
                    try {
                        Api.editMyGroupNickname(groupId, input.text.toString().trim())
                        Toast.makeText(this@GroupSettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
                        load()
                    } catch (e: Exception) {
                        Toast.makeText(this@GroupSettingsActivity, e.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editGroup() {
        val g = info ?: return
        val nameInput = EditText(this).apply { setText(g.name); hint = "群名称" }
        val introInput = EditText(this).apply { setText(g.introduction); hint = "群简介" }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
            addView(nameInput)
            addView(introInput)
        }
        AlertDialog.Builder(this)
            .setTitle("编辑群信息")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                lifecycleScope.launch {
                    try {
                        Api.editGroup(groupId, nameInput.text.toString().trim(), introInput.text.toString().trim())
                        Toast.makeText(this@GroupSettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
                        load()
                    } catch (e: Exception) {
                        Toast.makeText(this@GroupSettingsActivity, e.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmQuit() {
        AlertDialog.Builder(this)
            .setTitle("退出群聊")
            .setMessage("确定退出「${info?.name}」吗？")
            .setPositiveButton("退出") { _, _ ->
                lifecycleScope.launch {
                    try {
                        Api.quitGroup(groupId)
                        Toast.makeText(this@GroupSettingsActivity, "已退出群聊", Toast.LENGTH_SHORT).show()
                        // 关闭会话并返回
                        Api.removeConversation(groupId)
                        val intent = Intent(this@GroupSettingsActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(intent)
                        finish()
                    } catch (e: Exception) {
                        Toast.makeText(this@GroupSettingsActivity, "退出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
