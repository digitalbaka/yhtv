# TODO（云湖平板端 com.yunhu.pad）

## 1. 网络异常时在线 TTS 失败提示缺中文前缀

**现状**：只有服务端返回 4xx/5xx 时，提示才是「在线引擎失败（HTTP 状态码：服务端原因）」。连接层面的失败（拒绝连接、域名解析失败、超时等）目前直接把 OkHttp 的英文原文交给用户，实测提示为 `Failed to connect to /127.0.0.1:1，且本机缺少中文语音引擎，无法朗读`。

**要改成**：「在线引擎失败（网络异常：…）」，英文原文只放在括号内的细节里。

**为什么这轮没做**：收尾轮口径是先跑完真机验收和提交，这条只记录、不夹带改动。

## 2. AndroidKeyStore 条目删除只有代码路径证据

**现状**：`yhpad_tts_online_key` 的删除只有代码路径证据——`SecretStore.clear()` 里调用 `deleteEntry(KEY_ALIAS)`，调用点 `TtsConfig.clearOnline()` 明确、执行无异常；没有取到只读证据。

**为什么没取到**：设备是 Android 7、包不可调试、无 root，四条只读路径都走不通：

- `dumpsys keystore` → `Can't find service: keystore`
- `/data/misc/keystore/user_0` → `Permission denied`
- `run-as com.yunhu.pad` → `Package 'com.yunhu.pad' is not debuggable`
- `adb root` → `adbd cannot run as root in production builds`

**待办**：等哪天装 debug 包验别的功能时，顺带用只读方式确认该 alias 是否真的不存在（不要打印任何密钥内容）。
