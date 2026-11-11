package com.yunhu.tv.voice

/**
 * 服务商预置项。
 *
 * 这些值只用于「设置界面」的初始填充，绝不参与请求构造——
 * 发请求时一律读取 [VoicePrefs] 里用户保存的地址 / 模型 / token。
 */
object AsrProviders {

    const val CUSTOM = "custom"

    data class Preset(
        val id: String,
        val label: String,
        val baseUrl: String,
        val defaultModel: String
    )

    val SILICONFLOW = Preset(
        id = "siliconflow",
        label = "硅基流动",
        baseUrl = "https://api.siliconflow.cn/v1",
        defaultModel = "FunAudioLLM/SenseVoiceSmall"
    )

    val STEPFUN = Preset(
        id = "stepfun",
        label = "阶跃星辰",
        baseUrl = "https://api.stepfun.com/v1",
        defaultModel = "step-asr"
    )

    val ALL = listOf(SILICONFLOW, STEPFUN)

    fun byId(id: String): Preset? = ALL.firstOrNull { it.id == id }

    fun labelOf(id: String): String =
        if (id == CUSTOM) "自定义" else byId(id)?.label ?: "未选择"
}
