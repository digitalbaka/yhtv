package com.yunhu.tv.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.yunhu.tv.databinding.ActivityVoiceSettingsBinding
import com.yunhu.tv.voice.AsrProviders
import com.yunhu.tv.voice.VoicePrefs

/**
 * 语音识别设置。
 *
 * 只做 ASR 字段（服务商 / 地址 / 模型 / 密钥），TTS 字段在第三步再开界面。
 * 密钥输入框永远不回显已保存的值，界面只显示「已填写 / 未填写」和尾号。
 */
class VoiceSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoiceSettingsBinding
    private var selectedProvider: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        listOf(
            binding.btnProviderSiliconflow,
            binding.btnProviderStepfun,
            binding.btnProviderCustom,
            binding.btnTtl10,
            binding.btnTtl30,
            binding.btnTtl60,
            binding.btnTtl0,
            binding.btnSave,
            binding.btnQr,
            binding.btnBack
        ).forEach { FocusEffects.apply(it) }

        binding.btnTtl10.setOnClickListener { setTtl(10 * 60 * 1000L) }
        binding.btnTtl30.setOnClickListener { setTtl(30 * 60 * 1000L) }
        binding.btnTtl60.setOnClickListener { setTtl(60 * 60 * 1000L) }
        binding.btnTtl0.setOnClickListener { setTtl(0L) }

        binding.btnProviderSiliconflow.setOnClickListener { applyPreset(AsrProviders.SILICONFLOW) }
        binding.btnProviderStepfun.setOnClickListener { applyPreset(AsrProviders.STEPFUN) }
        binding.btnProviderCustom.setOnClickListener {
            selectedProvider = AsrProviders.CUSTOM
            binding.tvHint.text = "已切换为自定义，请填写自己的接口地址与模型名"
        }
        binding.btnSave.setOnClickListener { save() }
        binding.btnQr.setOnClickListener {
            startActivity(Intent(this, VoiceQrActivity::class.java))
        }
        binding.btnBack.setOnClickListener { finish() }

        load()
        binding.btnSave.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun load() {
        val cfg = VoicePrefs.asr
        selectedProvider = cfg.provider.ifEmpty { AsrProviders.SILICONFLOW.id }
        val preset = if (selectedProvider == AsrProviders.CUSTOM) null else {
            AsrProviders.byId(selectedProvider) ?: AsrProviders.SILICONFLOW
        }
        binding.etBaseUrl.setText(cfg.baseUrl.ifEmpty { preset?.baseUrl.orEmpty() })
        binding.etModel.setText(cfg.model.ifEmpty { preset?.defaultModel.orEmpty() })
        // 已保存的密钥不回显
        binding.etToken.setText("")
        refreshStatus()
    }

    private fun applyPreset(preset: AsrProviders.Preset) {
        selectedProvider = preset.id
        binding.etBaseUrl.setText(preset.baseUrl)
        binding.etModel.setText(preset.defaultModel)
        binding.tvHint.text = "已填入 ${preset.label} 的默认地址与模型，可自行修改"
    }

    private fun refreshStatus() {
        binding.tvAsrStatus.text = "状态：${VoicePrefs.describeAsr()}"
        refreshTtlButtons()
    }

    /** 有效期选中态：选中的按钮高亮为强调色 */
    private fun refreshTtlButtons() {
        val ttl = VoicePrefs.qrTtlMs
        val map = mapOf(
            binding.btnTtl10 to (10 * 60 * 1000L),
            binding.btnTtl30 to (30 * 60 * 1000L),
            binding.btnTtl60 to (60 * 60 * 1000L),
            binding.btnTtl0 to 0L
        )
        map.forEach { (btn, value) ->
            btn.isSelected = ttl == value
            btn.alpha = if (ttl == value) 1.0f else 0.55f
        }
    }

    private fun setTtl(value: Long) {
        VoicePrefs.qrTtlMs = value
        refreshTtlButtons()
        binding.tvHint.text = when (value) {
            0L -> "扫码链接永久有效（电视端服务重启后旧链接仍会失效）"
            else -> "扫码链接有效期已设为 ${value / 60000} 分钟"
        }
    }

    private fun save() {
        val baseUrl = binding.etBaseUrl.text.toString().trim()
        val model = binding.etModel.text.toString().trim()
        val typedToken = binding.etToken.text.toString().trim()

        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            binding.tvHint.text = "接口地址需以 http:// 或 https:// 开头"
            return
        }
        if (model.isEmpty()) {
            binding.tvHint.text = "模型名不能为空"
            return
        }

        val old = VoicePrefs.asr
        val provider = selectedProvider.ifEmpty { AsrProviders.CUSTOM }
        val saved = VoicePrefs.AsrConfig(
            provider = provider,
            baseUrl = baseUrl,
            model = model,
            token = typedToken.ifEmpty { old.token }
        )
        VoicePrefs.asr = saved
        binding.etToken.setText("")
        refreshStatus()
        binding.tvHint.text = if (VoicePrefs.isAsrReady(saved)) {
            "已保存，语音将使用在线识别。"
        } else {
            "已保存，但还没有密钥，语音仍使用占位文本。"
        }
        Toast.makeText(this, "语音识别配置已保存", Toast.LENGTH_SHORT).show()
    }
}
