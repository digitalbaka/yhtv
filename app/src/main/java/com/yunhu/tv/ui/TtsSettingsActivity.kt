package com.yunhu.tv.ui

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.yunhu.tv.databinding.ActivityTtsSettingsBinding
import com.yunhu.tv.tts.TtsSpeaker
import com.yunhu.tv.voice.VoicePrefs
import java.util.Locale

/**
 * 朗读设置（第三步）。
 *
 * 与 ASR 共用同一份配置存储；默认系统 TTS，不需要密钥。
 * 在线引擎为可选项，填错或超时会自动回退系统引擎，并在顶部显示当前用的引擎。
 */
class TtsSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTtsSettingsBinding
    private var enabled = false
    private var scope = VoicePrefs.TtsScope.NEW
    private var engine = "system"
    private var rate = 1.0f
    private var voiceName = ""
    private var voiceIndex = -1
    private var systemVoices: List<String> = emptyList()

    private var probe: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTtsSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        listOf(
            binding.btnToggle,
            binding.btnScopeAll,
            binding.btnScopeNew,
            binding.btnScopeUser,
            binding.btnScopeBot,
            binding.btnVoice,
            binding.btnRate08,
            binding.btnRate10,
            binding.btnRate12,
            binding.btnRate15,
            binding.btnEngineSystem,
            binding.btnEngineOnline,
            binding.btnSave,
            binding.btnPreview,
            binding.btnBack
        ).forEach { FocusEffects.apply(it) }

        binding.btnToggle.setOnClickListener {
            enabled = !enabled
            renderToggle()
        }
        binding.btnScopeAll.setOnClickListener { scope = VoicePrefs.TtsScope.ALL; renderScope() }
        binding.btnScopeNew.setOnClickListener { scope = VoicePrefs.TtsScope.NEW; renderScope() }
        binding.btnScopeUser.setOnClickListener { scope = VoicePrefs.TtsScope.USER; renderScope() }
        binding.btnScopeBot.setOnClickListener { scope = VoicePrefs.TtsScope.BOT; renderScope() }
        binding.btnVoice.setOnClickListener { nextVoice() }
        binding.btnRate08.setOnClickListener { rate = 0.8f; renderRate() }
        binding.btnRate10.setOnClickListener { rate = 1.0f; renderRate() }
        binding.btnRate12.setOnClickListener { rate = 1.2f; renderRate() }
        binding.btnRate15.setOnClickListener { rate = 1.5f; renderRate() }
        binding.btnEngineSystem.setOnClickListener {
            engine = "system"
            renderEngine()
        }
        binding.btnEngineOnline.setOnClickListener {
            engine = "online"
            renderEngine()
        }
        binding.btnSave.setOnClickListener { save(showToast = true) }
        binding.btnPreview.setOnClickListener {
            save(showToast = false)
            TtsSpeaker.speak("你好，这是云湖电视的朗读试听。")
            binding.tvHint.text = "正在试听，当前引擎：${TtsSpeaker.currentEngineLabel}"
        }
        binding.btnBack.setOnClickListener { finish() }

        load()
        binding.btnSave.requestFocus()
        loadSystemVoices()
    }

    override fun onResume() {
        super.onResume()
        binding.tvEngine.text = "当前引擎：${TtsSpeaker.currentEngineLabel}"
    }

    override fun onDestroy() {
        runCatching { probe?.shutdown() }
        probe = null
        super.onDestroy()
    }

    private fun load() {
        val s = VoicePrefs.ttsSettings
        enabled = s.enabled
        scope = s.scope
        engine = s.engine
        rate = s.rate
        voiceName = s.voice
        binding.etTargetUser.setText(s.targetUser)
        binding.etTtsBaseUrl.setText(s.baseUrl)
        binding.etTtsModel.setText(s.model)
        binding.etTtsVoice.setText(s.voice)
        binding.etTtsToken.setText("")
        binding.tvHint.text = if (s.enabled) {
            "朗读已开启，范围：${VoicePrefs.TtsScope.label(s.scope)}"
        } else {
            "朗读默认关闭，打开后按所选范围朗读新消息。"
        }
        renderToggle()
        renderScope()
        renderRate()
        renderEngine()
    }

    private fun renderToggle() {
        binding.btnToggle.text = if (enabled) "已开启" else "已关闭"
        binding.btnToggle.isSelected = enabled
        binding.btnToggle.alpha = if (enabled) 1.0f else 0.55f
    }

    private fun renderScope() {
        val map = mapOf(
            binding.btnScopeAll to VoicePrefs.TtsScope.ALL,
            binding.btnScopeNew to VoicePrefs.TtsScope.NEW,
            binding.btnScopeUser to VoicePrefs.TtsScope.USER,
            binding.btnScopeBot to VoicePrefs.TtsScope.BOT
        )
        map.forEach { (btn, value) ->
            btn.isSelected = scope == value
            btn.alpha = if (scope == value) 1.0f else 0.55f
        }
        binding.etTargetUser.visibility =
            if (scope == VoicePrefs.TtsScope.USER) android.view.View.VISIBLE
            else android.view.View.GONE
    }

    private fun renderRate() {
        val map = mapOf(
            binding.btnRate08 to 0.8f,
            binding.btnRate10 to 1.0f,
            binding.btnRate12 to 1.2f,
            binding.btnRate15 to 1.5f
        )
        map.forEach { (btn, value) ->
            btn.isSelected = rate == value
            btn.alpha = if (rate == value) 1.0f else 0.55f
        }
    }

    private fun renderEngine() {
        binding.btnEngineSystem.alpha = if (engine == "system") 1.0f else 0.55f
        binding.btnEngineOnline.alpha = if (engine == "online") 1.0f else 0.55f
        binding.tvEngine.text = if (engine == "system") {
            "当前引擎：${TtsSpeaker.currentEngineLabel}"
        } else {
            "当前引擎：在线 TTS（失败自动回退）"
        }
        val online = engine == "online"
        binding.etTtsBaseUrl.isEnabled = online
        binding.etTtsModel.isEnabled = online
        binding.etTtsToken.isEnabled = online
    }

    /** 音色：在系统中文音色之间循环 */
    private fun loadSystemVoices() {
        probe = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                binding.btnVoice.text = "音色：系统无可用引擎"
                return@TextToSpeech
            }
            val voices = probe?.voices
                ?.filter { it.locale.language == Locale.CHINA.language }
                ?.map { it.name }
                .orEmpty()
            systemVoices = voices
            voiceIndex = voices.indexOf(voiceName)
            renderVoiceLabel()
        }
    }

    private fun nextVoice() {
        if (systemVoices.isEmpty()) {
            binding.tvHint.text = "这台电视没有可枚举的系统音色，将跟随系统默认音色"
            return
        }
        voiceIndex = if (voiceIndex + 1 >= systemVoices.size) -1 else voiceIndex + 1
        voiceName = if (voiceIndex >= 0) systemVoices[voiceIndex] else ""
        renderVoiceLabel()
    }

    private fun renderVoiceLabel() {
        binding.btnVoice.text = if (voiceName.isEmpty()) {
            "音色：跟随系统"
        } else {
            "音色：${voiceName.substringAfterLast('-').take(12)}"
        }
    }

    private fun save(showToast: Boolean) {
        val baseUrl = binding.etTtsBaseUrl.text.toString().trim()
        val model = binding.etTtsModel.text.toString().trim()
        val typedToken = binding.etTtsToken.text.toString().trim()
        if (engine == "online" && baseUrl.isNotEmpty() &&
            !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")
        ) {
            binding.tvHint.text = "在线地址需以 http:// 或 https:// 开头"
            return
        }
        val old = VoicePrefs.ttsSettings
        val saved = old.copy(
            enabled = enabled,
            scope = scope,
            targetUser = binding.etTargetUser.text.toString().trim(),
            engine = engine,
            voice = voiceName,
            rate = rate,
            provider = if (engine == "online") "custom" else "system",
            baseUrl = baseUrl,
            model = model,
            token = typedToken.ifEmpty { old.token }
        )
        VoicePrefs.ttsSettings = saved
        binding.etTtsToken.setText("")
        binding.tvHint.text = buildString {
            append(if (saved.enabled) "已保存，朗读已开启" else "已保存，朗读仍是关闭状态")
            if (saved.engine == "online" && !VoicePrefs.isTtsOnlineReady(saved)) {
                append("；在线信息不完整，将使用系统引擎")
            }
        }
        binding.tvEngine.text = "当前引擎：${TtsSpeaker.currentEngineLabel}"
        if (showToast) Toast.makeText(this, "朗读设置已保存", Toast.LENGTH_SHORT).show()
    }
}
