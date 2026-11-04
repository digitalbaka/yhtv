# 云湖平板版（yunhu-pad）

云湖聊天软件的 Android 平板/大屏触控设备客户端，基于 **YunHuTV（云湖电视版）** 移植，
沿用同一套云湖开放 API（https://yh-api.yyyyt.top/api/）与业务实现，界面与交互按平板调整。

兼容 **Android 5.0（API 21）及以上、1GB 运存及以上**的平板设备。

## 与电视版的差异

| 项目 | 云湖电视版 | 云湖平板版 |
| --- | --- | --- |
| 包名 | `com.yunhu.tv` | `com.yunhu.pad`（可与电视版同机共存） |
| 应用名 | 云湖TV | 云湖Pad |
| 最低系统 | Android 6.0（API 23） | **Android 5.0（API 21）** |
| 桌面入口 | `LEANBACK_LAUNCHER` + 普通桌面 | 仅普通桌面 `LAUNCHER` |
| 输入方式 | 遥控器焦点导航 | 触屏点按 / 长按（焦点态仍保留） |
| 屏幕方向 | 电视横屏 | 未锁定方向，横竖屏自由旋转 |
| 应用图标 | 系统默认 | 独立图标：紫色云朵 "e" + HD 角标 |
| 堆内存 | 默认 | `largeHeap="true"`（适配 1GB 运存机型） |

具体改动：

- **触屏交互**：页面控件原本即使用 `setOnClickListener`，触屏可直接点击；三个焦点背景
  （`bg_button_focus` / `bg_item_focus` / `bg_tab_focus`）补充 `state_pressed` 按下反馈，
  触摸时有可见的高亮变化。
- **消息长按菜单**：恢复 `setOnLongClickListener` 长按唤出「查看图片 / 复制 / 撤回」等操作，
  遥控器 `MENU` 键仍作为备选方式保留（`MessageAdapter`）。
- **登录页**：移除「遥控器：上下移动焦点，确认键输入/登录」提示。
- **Manifest**：移除 leanback 声明与电视 banner；声明 `android.hardware.touchscreen`
  `required="true"`、`android.hardware.microphone` `required="false"`。
- **主题**：`Theme.YunHuTv` → `Theme.YunHuPad`。
- **图标**：五档 mipmap（48/72/96/144/192）替换为紫色云朵图标，四角透明。

## 功能

- 邮箱密码登录（token 持久化，免重复登录）
- 会话列表：最近消息、未读角标、@提醒，长按删除会话
- 通讯录：好友 / 群聊 / 机器人分组浏览，点按进入聊天
- 聊天：文字、图片、表情（内置常用 Emoji + 个人收藏）、Markdown 图片直接展示、
  文件/语音/通话卡片提示、消息引用、撤回（群管理员可撤回他人消息）、复制、
  实时推送（WebSocket）、机器人流式消息追加、上翻加载历史
- 社区：已关注/全部版块浏览、文章列表分页加载、文章阅读、发评论、点赞、投币
- 图片全屏查看、视频播放
- 我的：昵称、ID、金币数、退出登录

## 触屏操作

| 操作 | 作用 |
| --- | --- |
| 点按会话 / 联系人 | 进入聊天 |
| 点按输入框 | 唤出输入法 |
| 点按发送 | 发送消息 |
| 长按会话 | 删除会话 |
| 长按消息 | 查看图片 / 复制文字 / 撤回 |
| 系统返回 | 返回上一级 / 退出看图 |
| 屏幕旋转 | 页面适配横竖屏 |

## 版本信息

当前发布包（`app/release/app-release.apk`）实际读回：

- versionCode：`611042033`
- versionName：`1.1.2611042033`
- compileSdk / targetSdk：34，minSdk：21

版本号由构建时间自动生成（`app/build.gradle.kts`）：

- versionCode = `yMMddHHmm`（9 位，不超 Int 上限）
- versionName = `1.1.<yyMMddHHmm>`

因此每次打包版本号都会变化，属于预期行为。

## 构建

本仓库为纯源码工程，需用 Android Studio 构建 APK：

1. 安装 Android Studio（Hedgehog 及以上），JDK 17
2. `Open` 选择本目录，Gradle Sync 会自动补齐 gradle-wrapper（工程内仅含 `gradle/wrapper/gradle-wrapper.properties`）
3. `Build > Build APK(s)`，输出在 `app/build/outputs/apk/`
4. 通过 U 盘 / ADB 安装到平板：`adb install app-debug.apk`

release 构建未配置正式签名证书，默认使用 debug 签名，`adb install -r` 可直接覆盖安装。

## 平板上手

1. `adb install -r app-release.apk`（首次安装可省略 `-r`）
2. 打开「云湖Pad」，用云湖账号的邮箱 + 密码登录
3. 首页三个 Tab：消息 / 通讯录 / 我的
4. 横屏使用体验更佳：界面沿用电视版为大屏设计的布局，纵向留白较多

## 技术要点

- minSdk 21 / targetSdk 34，Kotlin + ViewBinding
- 云湖 HTTP 接口为 Protobuf 二进制协议，工程内置极简 varint 编解码器（`api/Proto.kt`），无 protoc 依赖
- WebSocket（`wss://chat-ws-go.jwzhd.com/ws`）：登录帧 + 25s 心跳 + 指数退避自动重连
- 图床（`*.jwznb.com`）要求 `Referer` 头，已在 Coil 拦截器中统一处理（`App.kt`）
- 视频使用 ExoPlayer + OkHttp 数据源（复用带 Referer / 证书放行的客户端）

## 已知限制

- 没有 `mipmap-anydpi-v26` 自适应图标：Android 8+ 桌面会将位图整体缩放，个别 ROM 的
  强制圆形/方形遮罩可能裁切四角。
- 没有 `layout-sw600dp` 等平板专用布局，界面沿用电视版单一布局；超大屏或竖屏下留白较多，
  聊天页暂未做左右分栏。
- release 包为 debug 签名，仅适合自用分发，不能上架应用商店。
- 未在 1GB 运存的低端平板上做压力测试，长时间挂后台（WebSocket 常驻）可能被系统回收。

## 目录结构

```
app/src/main/java/com/yunhu/pad/
├── App.kt                 # 应用入口：Session 初始化、Coil 图床 Referer
├── api/
│   ├── Proto.kt           # Protobuf varint 编解码
│   ├── ProtoModels.kt     # 消息/会话/通讯录等数据模型与请求响应编解码
│   ├── Api.kt             # HTTP API：登录/会话/消息/通讯录/表情
│   └── WsClient.kt        # WebSocket 实时推送客户端
├── util/                  # Session、时间格式化、Markdown 转纯文本、路由
└── ui/                    # 登录、主页（消息/通讯录/我的）、聊天、社区、全屏看图
```

API 文档来源：https://yh-api.yyyyt.top/api/
