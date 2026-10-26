package com.yunhu.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yunhu.tv.api.Api
import com.yunhu.tv.databinding.ActivityLoginBinding
import com.yunhu.tv.util.Session
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Session.isLoggedIn) {
            openMain()
            return
        }
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etEmail.requestFocus()

        binding.btnLogin.setOnClickListener { doLogin() }
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                doLogin()
                true
            } else false
        }
    }

    private fun doLogin() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        if (email.isEmpty() || password.isEmpty()) {
            binding.tvStatus.text = "请输入邮箱和密码"
            return
        }
        setLoading(true, "登录中…")
        lifecycleScope.launch {
            try {
                val token = Api.emailLogin(email, password)
                Session.token = token
                try {
                    val info = Api.getSelfInfo()
                    Session.userId = info.userId
                    Session.nickname = info.nickname
                    Session.avatarUrl = info.avatarUrl
                } catch (_: Exception) {
                    // token 已拿到，用户信息失败不阻塞登录
                }
                openMain()
            } catch (e: Api.ApiException) {
                setLoading(false, "登录失败：${e.message}")
            } catch (e: Exception) {
                setLoading(false, "网络错误，请检查网络连接")
            }
        }
    }

    private fun setLoading(loading: Boolean, status: String) {
        binding.btnLogin.isEnabled = !loading
        binding.etEmail.isEnabled = !loading
        binding.etPassword.isEnabled = !loading
        binding.tvStatus.text = status
        binding.tvStatus.setTextColor(
            if (loading) getColor(com.yunhu.tv.R.color.text_secondary)
            else getColor(com.yunhu.tv.R.color.danger)
        )
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
