package com.yunhu.pad.ui

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
import com.yunhu.pad.R
import com.yunhu.pad.api.Api
import com.yunhu.pad.api.GroupInfo
import com.yunhu.pad.databinding.ActivityGroupSettingsBinding
import kotlinx.coroutines.launch

/** 群聊设置页 */
class GroupSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GROUP_ID = "group_id"
    }

    private lateinit var binding: ActivityGroupSettingsBinding
    private var groupId = ""
    private var info: GroupInfo? = null
    /** 加载群信息时回填 Switch 会触发监听，用该标记避免误发请求 */
    private var applyingInfo = false

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
        binding.rowGroupTags.setOnClickListener { manageTags() }
        binding.rowMsgTypeLimit.setOnClickListener { editMsgTypeLimit() }
        binding.rowGroupKeyword.setOnClickListener { editGroupKeyword() }
        binding.rowAutoDelete.setOnClickListener { editAutoDelete() }
        binding.rowReportGroup.setOnClickListener { reportGroup() }
        binding.swHideRecommend.setOnCheckedChangeListener { _, checked ->
            if (applyingInfo) return@setOnCheckedChangeListener
            setHideRecommend(checked)
        }
        binding.swStopUpload.setOnCheckedChangeListener { _, checked ->
            if (applyingInfo) return@setOnCheckedChangeListener
            setStopUpload(checked)
        }
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
                if (g.isAdmin) {
                    binding.rowEditGroup.visibility = android.view.View.VISIBLE
                    binding.rowGroupTags.visibility = android.view.View.VISIBLE
                    binding.rowMsgTypeLimit.visibility = android.view.View.VISIBLE
                }
                // 自动销毁 / 禁止上传 / 群口令按文档要求仅群主可操作
                if (g.isOwner) {
                    binding.rowAutoDelete.visibility = android.view.View.VISIBLE
                    binding.rowStopUpload.visibility = android.view.View.VISIBLE
                    binding.rowGroupKeyword.visibility = android.view.View.VISIBLE
                }
                binding.tvMsgTypeLimit.text =
                    if (g.limitedMsgType.isBlank()) "未限制" else "已限制 ${g.limitedMsgType}"
                binding.tvGroupKeyword.text = g.groupCode.ifEmpty { "未设置" }
                binding.tvAutoDelete.text = when (g.autoDeleteMessage) {
                    0L -> "不销毁"
                    90L -> "90 天"
                    365L -> "1 年"
                    730L -> "2 年"
                    else -> "${g.autoDeleteMessage} 天"
                }
                applyingInfo = true
                binding.swHideRecommend.isChecked = !g.recommandation
                binding.swStopUpload.isChecked = g.denyUpload
                applyingInfo = false
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

    // ---------- 群标签 ----------

    private fun manageTags() {
        lifecycleScope.launch {
            try {
                val tags = Api.groupTagList(groupId, 100, 1)
                val labels = mutableListOf("＋ 新建群标签")
                tags.forEach { labels.add("【${it.tag}】${it.desc}") }
                AlertDialog.Builder(this@GroupSettingsActivity)
                    .setTitle("群标签（${tags.size}）")
                    .setItems(labels.toTypedArray()) { _, which ->
                        if (which == 0) showTagEditor(null) else showTagActions(tags[which - 1])
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            } catch (e: Exception) {
                toast("获取群标签失败: ${e.message}")
            }
        }
    }

    private fun showTagEditor(tag: Api.GroupTag?) {
        val nameInput = EditText(this).apply { setText(tag?.tag.orEmpty()); hint = "标签名（必填）" }
        val colorInput = EditText(this).apply { setText(tag?.color.orEmpty()); hint = "颜色，如 #FF5722（可留空）" }
        val descInput = EditText(this).apply { setText(tag?.desc.orEmpty()); hint = "说明（可留空）" }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
            addView(nameInput); addView(colorInput); addView(descInput)
        }
        AlertDialog.Builder(this)
            .setTitle(if (tag == null) "新建群标签" else "编辑群标签")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) { toast("标签名不能为空"); return@setPositiveButton }
                val color = colorInput.text.toString().trim()
                val desc = descInput.text.toString().trim()
                lifecycleScope.launch {
                    try {
                        if (tag == null) {
                            Api.createGroupTag(groupId, name, color, desc)
                        } else {
                            Api.editGroupTag(tag.id, groupId, name, color, desc, tag.sort)
                        }
                        toast("已保存")
                        manageTags()
                    } catch (e: Exception) {
                        toast("保存失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showTagActions(tag: Api.GroupTag) {
        AlertDialog.Builder(this)
            .setTitle("【${tag.tag}】")
            .setItems(arrayOf("编辑", "删除")) { _, which ->
                if (which == 0) {
                    showTagEditor(tag)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("删除标签")
                        .setMessage("确定删除【${tag.tag}】吗？已关联的成员会一并取消。")
                        .setPositiveButton("删除") { _, _ ->
                            lifecycleScope.launch {
                                try {
                                    Api.deleteGroupTag(tag.id)
                                    toast("已删除")
                                    manageTags()
                                } catch (e: Exception) {
                                    toast("删除失败: ${e.message}")
                                }
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            .show()
    }

    // ---------- 进阶设置 ----------

    private val msgTypeChoices = listOf(
        1 to "文本", 2 to "图片", 3 to "Markdown", 4 to "文件",
        6 to "帖子", 7 to "表情", 8 to "HTML", 10 to "视频", 11 to "语音", 13 to "语音通话"
    )

    private fun editMsgTypeLimit() {
        val cur = info?.limitedMsgType.orEmpty()
        val checked = cur.split(",").mapNotNull { it.trim().toIntOrNull() }
        val boxes = BooleanArray(msgTypeChoices.size) { msgTypeChoices[it].first in checked }
        AlertDialog.Builder(this)
            .setTitle("禁止发送的消息类型（不选表示不限制）")
            .setMultiChoiceItems(msgTypeChoices.map { it.second }.toTypedArray(), boxes) { _, i, isChecked ->
                boxes[i] = isChecked
            }
            .setPositiveButton("保存") { _, _ ->
                val type = msgTypeChoices.filterIndexed { i, _ -> boxes[i] }.joinToString(",") { it.first.toString() }
                lifecycleScope.launch {
                    try {
                        Api.setMsgTypeLimit(groupId, type)
                        toast("已保存")
                        load()
                    } catch (e: Exception) {
                        toast("设置失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editGroupKeyword() {
        val input = EditText(this).apply {
            setText(info?.groupCode.orEmpty())
            hint = "群口令（留空表示清除）"
        }
        AlertDialog.Builder(this)
            .setTitle("加入群口令")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                lifecycleScope.launch {
                    try {
                        Api.editGroupKeyword(groupId, input.text.toString().trim())
                        toast("已保存")
                        load()
                    } catch (e: Exception) {
                        toast("设置失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editAutoDelete() {
        val labels = arrayOf("不自动删除", "90 天后删除", "1 年后删除", "2 年后删除")
        val values = intArrayOf(0, 90, 365, 730)
        val cur = info?.autoDeleteMessage ?: 0
        val index = values.indexOfFirst { it.toLong() == cur }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("消息自动销毁")
            .setSingleChoiceItems(labels, index) { dialog, which ->
                lifecycleScope.launch {
                    try {
                        Api.setAutoDeleteMessage(groupId, values[which])
                        toast("已保存")
                        dialog.dismiss()
                        load()
                    } catch (e: Exception) {
                        toast("设置失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setStopUpload(checked: Boolean) {
        lifecycleScope.launch {
            try {
                Api.setStopMemberUpload(groupId, if (checked) 1 else 0)
                toast(if (checked) "已禁止成员上传" else "已允许成员上传")
            } catch (e: Exception) {
                applyingInfo = true
                binding.swStopUpload.isChecked = !checked
                applyingInfo = false
                toast("设置失败: ${e.message}")
            }
        }
    }

    private fun setHideRecommend(hide: Boolean) {
        lifecycleScope.launch {
            try {
                Api.setGroupSwitch(groupId, if (hide) 1 else 0)
                toast(if (hide) "已隐藏该群" else "已加入群推荐")
            } catch (e: Exception) {
                applyingInfo = true
                binding.swHideRecommend.isChecked = !hide
                applyingInfo = false
                toast("设置失败: ${e.message}")
            }
        }
    }

    private val reportReasons = arrayOf(
        "色情低俗", "时政不时消息", "垃圾广告", "青少年不易", "辱骂攻击",
        "侵犯权益", "违法犯罪", "开盒网暴", "其他"
    )

    private fun reportGroup() {
        AlertDialog.Builder(this)
            .setTitle("举报该群")
            .setItems(reportReasons) { _, which ->
                lifecycleScope.launch {
                    try {
                        Api.reportChat(groupId, 2, info?.name.orEmpty(), "[群聊]", reportReasons[which])
                        toast("举报已提交")
                    } catch (e: Exception) {
                        toast("举报失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

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
