package com.yunhu.tv.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yunhu.tv.App
import com.yunhu.tv.R
import com.yunhu.tv.api.Api
import com.yunhu.tv.api.ChatType
import com.yunhu.tv.databinding.ActivitySettingsBinding
import com.yunhu.tv.util.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「我的」二级设置页（第六步）。
 *
 * 分组：账号 / 语音与朗读 / 存储 / 调试（仅 debug 包）/ 退出登录（底部独立）。
 * 单列纵向列表，左右键不改变焦点；上下到边缘时停住，不循环。
 * 从子页面返回时，焦点回到离开时那一行，滚动位置保持不变。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** 离开页面时的焦点 id，用于返回时恢复 */
    private var lastFocusId = 0

    private val rows by lazy {
        listOf(
            binding.rowProfile,
            binding.rowCopyId,
            binding.rowVoiceSettings,
            binding.rowMicLink,
            binding.rowTts,
            binding.rowClearCache,
            binding.rowVoiceDebug,
            binding.rowLogout
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        binding.headerDebug.visibility = if (debuggable) View.VISIBLE else View.GONE
        binding.rowVoiceDebug.visibility = if (debuggable) View.VISIBLE else View.GONE

        binding.tvCopySub.text = Session.userId

        binding.btnBack.setOnClickListener { finish() }

        binding.rowProfile.setOnClickListener {
            startActivity(
                Intent(this, UserProfileActivity::class.java)
                    .putExtra(UserProfileActivity.EXTRA_USER_ID, Session.userId)
                    .putExtra(UserProfileActivity.EXTRA_CHAT_TYPE, ChatType.USER)
                    .putExtra(UserProfileActivity.EXTRA_NAME, Session.nickname)
                    .putExtra(UserProfileActivity.EXTRA_AVATAR, Session.avatarUrl)
            )
        }
        binding.rowCopyId.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("user_id", Session.userId))
            Toast.makeText(this, "已复制 ID", Toast.LENGTH_SHORT).show()
        }
        binding.rowVoiceSettings.setOnClickListener {
            startActivity(Intent(this, VoiceSettingsActivity::class.java))
        }
        binding.rowMicLink.setOnClickListener {
            startActivity(Intent(this, MicLinkActivity::class.java))
        }
        binding.rowTts.setOnClickListener {
            startActivity(Intent(this, TtsSettingsActivity::class.java))
        }
        binding.rowClearCache.setOnClickListener { confirmClearCache() }
        binding.rowVoiceDebug.setOnClickListener {
            startActivity(Intent(this, VoiceDebugPanelActivity::class.java))
        }
        binding.rowLogout.setOnClickListener { confirmLogout() }

        setupFocusChain()
        refreshCacheSize()

        binding.btnBack.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // 从二级页面返回：回到离开时那一行；ScrollView 会把它滚回可视区
        if (lastFocusId != 0) {
            findViewById<View>(lastFocusId)?.requestFocus()
        }
    }

    /**
     * 显式焦点链：
     * 返回 ⇄ 第一项 … ⇄ 最后一项；第一项按上回「返回」，最后一项按下停住；
     * 每一项左右都指向自己（单列，左右键不改变焦点）。
     */
    private fun setupFocusChain() {
        val visible = rows.filter { it.visibility == View.VISIBLE }
        binding.btnBack.nextFocusUpId = binding.btnBack.id
        binding.btnBack.nextFocusLeftId = binding.btnBack.id
        binding.btnBack.nextFocusRightId = binding.btnBack.id
        binding.btnBack.nextFocusDownId = visible.first().id

        visible.forEachIndexed { index, row ->
            row.nextFocusUpId = if (index == 0) binding.btnBack.id else visible[index - 1].id
            row.nextFocusDownId = if (index == visible.size - 1) row.id else visible[index + 1].id
            row.nextFocusLeftId = row.id
            row.nextFocusRightId = row.id
            row.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) lastFocusId = view.id
            }
        }
    }

    // ---------- 清除缓存 ----------

    private fun refreshCacheSize() {
        lifecycleScope.launch {
            val size = withContext(Dispatchers.IO) { cacheSize(cacheDir) }
            binding.tvCacheSub.text = if (size > 0) formatBytes(size) else ""
        }
    }

    private fun confirmClearCache() {
        lifecycleScope.launch {
            val size = withContext(Dispatchers.IO) { cacheSize(cacheDir) }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("清除缓存？")
                .setMessage(if (size > 0) "将清理 ${formatBytes(size)} 的临时文件" else "当前没有可清理的缓存")
                .setPositiveButton("清除") { _, _ ->
                    lifecycleScope.launch {
                        val freed = withContext(Dispatchers.IO) {
                            val before = cacheSize(cacheDir)
                            deleteAll(cacheDir)
                            before - cacheSize(cacheDir)
                        }
                        Toast.makeText(
                            this@SettingsActivity,
                            if (freed > 0) "已清理 ${formatBytes(freed)}" else "已清理",
                            Toast.LENGTH_SHORT
                        ).show()
                        refreshCacheSize()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun cacheSize(dir: java.io.File): Long {
        if (!dir.exists()) return 0
        var total = 0L
        dir.listFiles()?.forEach {
            total += if (it.isDirectory) cacheSize(it) else it.length()
        }
        return total
    }

    private fun deleteAll(dir: java.io.File) {
        dir.listFiles()?.forEach {
            if (it.isDirectory) deleteAll(it) else try { it.delete() } catch (_: Exception) {}
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "%.1fKB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1fMB".format(bytes / 1024.0 / 1024.0)
        else -> "%.1fGB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    }

    // ---------- 退出登录 ----------

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("退出登录？")
            .setPositiveButton("退出") { _, _ ->
                lifecycleScope.launch {
                    Api.logout()
                    App.wsClient.stop()
                    Session.clear()
                    val intent = Intent(this@SettingsActivity, LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
