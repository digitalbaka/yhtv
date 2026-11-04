package com.yunhu.pad.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import com.yunhu.pad.App
import com.yunhu.pad.R
import com.yunhu.pad.api.Api
import com.yunhu.pad.api.Qiniu
import com.yunhu.pad.databinding.ActivitySettingsBinding
import com.yunhu.pad.util.MediaPrep
import com.yunhu.pad.util.Prefs
import com.yunhu.pad.util.Session
import kotlinx.coroutines.launch
import java.io.File

/**
 * 统一设置入口：账号（头像/邮箱/密码/在线设备）、外观（深浅色）、动态头像、回车发送、聊天背景。
 * 全部写入 Prefs（SharedPreferences）或服务端，重启后保持。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val pickBackground = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { saveBackground(it) }
    }

    private val pickAvatar = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { uploadAvatar(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnChangeAvatar.setOnClickListener { pickAvatar.launch("image/*") }
        binding.btnBindEmail.setOnClickListener { showEmailDialog(change = false) }
        binding.btnChangeEmail.setOnClickListener { showEmailDialog(change = true) }
        binding.btnChangePassword.setOnClickListener { showPasswordDialog() }
        binding.btnOfflineDevice.setOnClickListener { confirmOfflineCurrentDevice() }

        binding.btnThemeSystem.setOnClickListener { changeTheme(Prefs.THEME_SYSTEM) }
        binding.btnThemeLight.setOnClickListener { changeTheme(Prefs.THEME_LIGHT) }
        binding.btnThemeDark.setOnClickListener { changeTheme(Prefs.THEME_DARK) }

        binding.switchDynamicAvatar.isChecked = Prefs.dynamicAvatar
        binding.switchDynamicAvatar.setOnCheckedChangeListener { _, checked ->
            Prefs.dynamicAvatar = checked
            App.instance.rebuildImageLoader()
        }

        binding.switchEnterSend.isChecked = Prefs.enterToSend
        binding.switchEnterSend.setOnCheckedChangeListener { _, checked ->
            Prefs.enterToSend = checked
        }

        binding.btnPickBg.setOnClickListener { pickBackground.launch("image/*") }
        binding.btnResetBg.setOnClickListener { resetBackground() }

        renderTheme()
        renderBackground()
        renderDevice()
        loadSelf()
    }

    // ---------- 账号 ----------

    private fun loadSelf() {
        lifecycleScope.launch {
            try {
                val me = Api.getSelfInfo()
                binding.tvMyName.text = me.nickname.ifEmpty { me.userId }
                binding.ivMyAvatar.load(Api.fullImageUrl(me.avatarUrl)) {
                    placeholder(R.drawable.bg_card)
                    error(R.drawable.bg_card)
                }
            } catch (e: Exception) {
                binding.tvMyName.text = "账号信息加载失败: ${e.message}（点按重试）"
                binding.tvMyName.setOnClickListener { loadSelf() }
            }
        }
    }

    /** 选图 → EXIF 纠正并压缩 → 七牛上传 → /v1/user/edit-avatar */
    private fun uploadAvatar(uri: Uri) {
        binding.btnChangeAvatar.isEnabled = false
        binding.btnChangeAvatar.text = "上传中…"
        lifecycleScope.launch {
            try {
                val raw = MediaPrep.copyToDir(this@SettingsActivity, uri, File(cacheDir, "avatar"), "avatar.jpg")
                val image = MediaPrep.compressImage(raw, File(cacheDir, "avatar_out"))
                val token = Api.qiniuToken("")
                val key = Qiniu.upload(image.file, token)
                Api.editAvatar(Api.fullImageUrl(key))
                binding.ivMyAvatar.load(Api.fullImageUrl(key)) {
                    placeholder(R.drawable.bg_card)
                    error(R.drawable.bg_card)
                }
                toast("头像已更新")
            } catch (e: Exception) {
                toast("头像上传失败: ${e.message}")
            } finally {
                binding.btnChangeAvatar.isEnabled = true
                binding.btnChangeAvatar.text = "更换头像"
            }
        }
    }

    /**
     * 绑定 / 更换邮箱。文档要求 captcha 字段（邮箱验证码），
     * 但获取验证码接口只在文档里列了 code/id（人机验证）没有说明获取方式，
     * 这里按“先收验证码再提交”的流程如实实现，发送失败会把服务端原因展示出来。
     */
    private fun showEmailDialog(change: Boolean) {
        val email = EditText(this).apply { hint = "邮箱地址"; inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val code = EditText(this).apply { hint = "邮箱验证码" }
        val sendBtn = Button(this).apply { text = "获取验证码" }
        val codeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(code, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
            addView(email)
            addView(codeRow)
        }
        sendBtn.setOnClickListener {
            val addr = email.text.toString().trim()
            if (addr.isEmpty()) { toast("请先填写邮箱"); return@setOnClickListener }
            sendBtn.isEnabled = false
            lifecycleScope.launch {
                try {
                    Api.emailVerificationCode(addr)
                    toast("验证码已请求，请查收邮箱")
                } catch (e: Exception) {
                    toast("获取验证码失败: ${e.message}")
                } finally {
                    sendBtn.isEnabled = true
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(if (change) "更换邮箱" else "绑定邮箱")
            .setView(box)
            .setPositiveButton("提交") { _, _ ->
                val addr = email.text.toString().trim()
                val captcha = code.text.toString().trim()
                if (addr.isEmpty() || captcha.isEmpty()) { toast("邮箱和验证码都必须填写"); return@setPositiveButton }
                lifecycleScope.launch {
                    try {
                        if (change) Api.changeEmail(addr, captcha) else Api.bindEmail(addr, captcha)
                        toast(if (change) "邮箱已更换" else "邮箱已绑定")
                    } catch (e: Exception) {
                        toast("提交失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPasswordDialog() {
        val email = EditText(this).apply {
            hint = "账号邮箱"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val code = EditText(this).apply { hint = "邮箱验证码" }
        val pwd = EditText(this).apply {
            hint = "新密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val sendBtn = Button(this).apply { text = "获取验证码" }
        val codeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(code, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
            addView(email)
            addView(codeRow)
            addView(pwd)
        }
        sendBtn.setOnClickListener {
            val addr = email.text.toString().trim()
            if (addr.isEmpty()) { toast("请先填写邮箱"); return@setOnClickListener }
            sendBtn.isEnabled = false
            lifecycleScope.launch {
                try {
                    Api.emailVerificationCode(addr, "forget_password")
                    toast("验证码已请求，请查收邮箱")
                } catch (e: Exception) {
                    toast("获取验证码失败: ${e.message}")
                } finally {
                    sendBtn.isEnabled = true
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("修改密码")
            .setView(box)
            .setPositiveButton("提交") { _, _ ->
                val addr = email.text.toString().trim()
                val captcha = code.text.toString().trim()
                val newPwd = pwd.text.toString()
                if (addr.isEmpty() || captcha.isEmpty() || newPwd.isEmpty()) {
                    toast("邮箱、验证码、新密码都必须填写")
                    return@setPositiveButton
                }
                if (newPwd.length < 6) { toast("密码至少 6 位"); return@setPositiveButton }
                lifecycleScope.launch {
                    try {
                        Api.forgetPassword(addr, captcha, newPwd)
                        toast("密码已修改")
                    } catch (e: Exception) {
                        toast("修改失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 文档没有在线设备列表接口，这里只展示本机设备，下线也只针对本机。
     */
    private fun renderDevice() {
        val model = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        binding.tvDeviceInfo.text = "本机 · $model\n设备 ID: ${Session.deviceId}\n当前登录设备"
    }

    private fun confirmOfflineCurrentDevice() {
        AlertDialog.Builder(this)
            .setTitle("下线设备")
            .setMessage("将使本机立即退出登录，确定继续？")
            .setPositiveButton("下线") { _, _ ->
                lifecycleScope.launch {
                    try {
                        Api.deviceOffline(Session.deviceId)
                        Session.clear()
                        startActivity(
                            Intent(this@SettingsActivity, LoginActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        )
                        finish()
                    } catch (e: Exception) {
                        toast("下线失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun changeTheme(mode: Int) {
        if (Prefs.themeMode == mode) return
        Prefs.themeMode = mode
        // setDefaultNightMode 会让现有 Activity 自动重建，无需再手动 recreate
        App.instance.applyNightMode()
        renderTheme()
    }

    private fun renderTheme() {
        val mode = Prefs.themeMode
        binding.btnThemeSystem.isSelected = mode == Prefs.THEME_SYSTEM
        binding.btnThemeLight.isSelected = mode == Prefs.THEME_LIGHT
        binding.btnThemeDark.isSelected = mode == Prefs.THEME_DARK
        val accent = ContextCompat.getColor(this, R.color.accent)
        val normal = ContextCompat.getColor(this, R.color.text_primary)
        binding.btnThemeSystem.setTextColor(if (mode == Prefs.THEME_SYSTEM) accent else normal)
        binding.btnThemeLight.setTextColor(if (mode == Prefs.THEME_LIGHT) accent else normal)
        binding.btnThemeDark.setTextColor(if (mode == Prefs.THEME_DARK) accent else normal)
    }

    private fun renderBackground() {
        val path = Prefs.chatBackgroundPath
        val file = if (path.isEmpty()) null else File(path)
        val exists = file != null && file.exists()
        if (exists) {
            binding.tvBgState.text = "已自定义"
            binding.ivBgPreview.setImageBitmap(
                runCatching { BitmapFactory.decodeFile(file!!.absolutePath) }.getOrNull()
            )
            binding.btnResetBg.isEnabled = true
        } else {
            binding.tvBgState.text = "默认背景"
            binding.ivBgPreview.setImageDrawable(null)
            binding.btnResetBg.isEnabled = false
        }
    }

    /** SAF 选图后立刻复制进应用私有目录，避免授权过期后失效 */
    private fun saveBackground(uri: Uri) {
        try {
            val dest = File(filesDir, "chat_bg_image")
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
            } ?: throw IllegalStateException("无法读取所选图片")
            Prefs.chatBackgroundPath = dest.absolutePath
            renderBackground()
            toast("聊天背景已更新")
        } catch (e: Exception) {
            toast("设置失败: ${e.message}")
        }
    }

    private fun resetBackground() {
        val path = Prefs.chatBackgroundPath
        if (path.isNotEmpty()) File(path).delete()
        Prefs.chatBackgroundPath = ""
        renderBackground()
        toast("已恢复默认背景")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
