# TODO（云湖平板端 com.yunhu.pad）

## 1. AndroidKeyStore 条目删除只有代码路径证据

**现状**：`yhpad_tts_online_key` 的删除只有代码路径证据——`SecretStore.clear()` 里调用 `deleteEntry(KEY_ALIAS)`，调用点 `TtsConfig.clearOnline()` 明确、执行无异常；没有取到只读证据。

**为什么没取到**：设备是 Android 7、包不可调试、无 root，四条只读路径都走不通：

- `dumpsys keystore` → `Can't find service: keystore`
- `/data/misc/keystore/user_0` → `Permission denied`
- `run-as com.yunhu.pad` → `Package 'com.yunhu.pad' is not debuggable`
- `adb root` → `adbd cannot run as root in production builds`

**待办**：等哪天装 debug 包验别的功能时，顺带用只读方式确认该 alias 是否真的不存在（不要打印任何密钥内容）。
