# 云湖 TV（yunhu-tv）

云湖聊天软件的 Android 电视版客户端，基于云湖开放 API（https://yh-api.yyyyt.top/api/）开发。
界面针对大屏与遥控器操作设计，兼容 Android 6.0（API 23）及以上电视/盒子设备。

## 功能

- 邮箱密码登录（token 持久化，免重复登录）
- 会话列表：最近消息、未读角标、@提醒，长按删除会话
- 通讯录：好友 / 群聊 / 机器人分组浏览，点按进入聊天
- 聊天：文字、图片、表情（个人收藏）、Markdown/HTML 转纯文本展示、
  文件/语音/通话卡片提示、消息引用、撤回、复制、实时推送（WebSocket）、
  机器人流式消息追加、上翻加载历史
- 图片全屏查看
- 我的：昵称、ID、金币数、退出登录

## 遥控器操作

| 按键 | 作用 |
| --- | --- |
| 方向键 | 移动焦点（焦点项会放大并高亮描边） |
| 确认键 | 打开会话 / 唤出输入法输入 / 发送 / 打开图片 |
| 长按确认键 | 会话：删除；消息：查看图片 / 复制文字 / 撤回 |
| 返回键 | 返回上一级 / 退出看图 |

## 构建

本仓库为纯源码工程，需用 Android Studio 构建 APK：

1. 安装 Android Studio（Hedgehog 及以上），JDK 17
2. `Open` 选择本目录，Gradle Sync 会自动补齐 gradle-wrapper（工程内仅含 `gradle/wrapper/gradle-wrapper.properties`）
3. `Build > Build APK(s)`，输出在 `app/build/outputs/apk/`
4. 通过 U 盘 / ADB 安装到电视：`adb install app-debug.apk`

## 技术要点

- minSdk 23 / targetSdk 34，Kotlin + ViewBinding
- 声明 `LEANBACK_LAUNCHER`，同时兼容普通安卓桌面入口
- 云湖 HTTP 接口为 Protobuf 二进制协议，工程内置极简 varint 编解码器（`api/Proto.kt`），无 protoc 依赖
- WebSocket（`wss://chat-ws-go.jwzhd.com/ws`）：登录帧 + 25s 心跳 + 指数退避自动重连
- 图床（`*.jwznb.com`）要求 `Referer` 头，已在 Coil 拦截器中统一处理（`App.kt`）
- 未设计应用图标，Manifest 暂用系统图标与纯色 banner，后续可自行替换

## 目录结构

```
app/src/main/java/com/yunhu/tv/
├── App.kt                 # 应用入口：Session 初始化、Coil 图床 Referer
├── api/
│   ├── Proto.kt           # Protobuf varint 编解码
│   ├── ProtoModels.kt     # 消息/会话/通讯录等数据模型与请求响应编解码
│   ├── Api.kt             # HTTP API：登录/会话/消息/通讯录/表情
│   └── WsClient.kt        # WebSocket 实时推送客户端
├── util/                  # Session、时间格式化、Markdown 转纯文本
└── ui/                    # 登录、主页（消息/通讯录/我的）、聊天、全屏看图
```

API 文档来源：https://yh-api.yyyyt.top/api/
