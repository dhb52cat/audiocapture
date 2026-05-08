# 系统音频录制 App (AudioCapture)

Android 内录应用 — 录制手机播放的任何声音（音乐、视频、游戏等）

---

## 功能特性

- 🎵 **录制系统内部音频**：使用 Android 10+ 的 `AudioPlaybackCapture` API
- 🎙️ **高品质编码**：AAC 192kbps，输出 M4A 格式
- ⏱️ **实时计时器**：显示录制时长
- 🌊 **波形动画**：录制中显示动态波形
- 📁 **文件管理**：自动保存，显示录音列表
- 🔔 **前台通知**：可从通知栏停止录制

---

## 系统要求

| 项目 | 要求 |
|------|------|
| 最低 Android 版本 | **Android 10 (API 29)** |
| 权限 | `RECORD_AUDIO`, `FOREGROUND_SERVICE` |
| 特殊授权 | MediaProjection（每次录制时弹窗确认）|

---

## 快速开始

### 1. 用 Android Studio 打开项目

```
File → Open → 选择 AudioCapture 文件夹
```

### 2. Sync Gradle

点击 **Sync Now** 等待依赖下载

### 3. 连接设备或启动模拟器

**注意**：模拟器系统版本必须是 Android 10+

### 4. 运行

点击 ▶ Run，安装到手机

---

## 使用方法

1. 打开 App
2. 在其他 App 中开始播放音乐（如 QQ音乐、本地播放器）
3. 回到 **系统录音** App，点击「● 开始录音」
4. 弹出屏幕录制授权弹窗 → 点击「立即开始」
5. 录音开始，波形动画显示
6. 点击「⏹ 停止录音」或从通知栏停止
7. 录音自动保存为 M4A 文件

---

## 文件保存位置

```
/Android/data/com.audiocapture/files/Recordings/录音_20241201_143022.m4a
```

可通过文件管理器或 ADB 访问：
```bash
adb pull /sdcard/Android/data/com.audiocapture/files/Recordings/
```

---

## 重要限制

| 情况 | 结果 |
|------|------|
| QQ音乐、网易云、本地播放器 | ✅ 通常可以录制 |
| Spotify、YouTube Music | ❌ 这些App设置了 `ALLOW_CAPTURE_BY_NONE`，会被拒绝 |
| 系统提示音、来电铃声 | ❌ 默认不可捕获 |
| 游戏音效 | ✅ 通常可以录制 |

> **原理说明**：Android 系统允许每个 App 声明自己是否允许被录制。
> 大部分音乐流媒体 App 出于版权保护会禁止录制。
> 本地播放器和部分国产应用通常没有这个限制。

---

## 项目结构

```
app/src/main/
├── java/com/audiocapture/
│   ├── MainActivity.kt          # 主界面、权限管理、UI交互
│   ├── AudioCaptureService.kt   # 核心录音服务（前台Service）
│   └── WaveformView.kt          # 自定义波形动画View
├── res/
│   ├── layout/activity_main.xml # 界面布局
│   └── values/                  # 颜色、字符串、主题
└── AndroidManifest.xml
```

---

## 核心技术

```kotlin
// Android 10+ 专属 API：捕获系统音频播放
val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)  // 媒体音频
    .addMatchingUsage(AudioAttributes.USAGE_GAME)   // 游戏音效
    .build()

val audioRecord = AudioRecord.Builder()
    .setAudioPlaybackCaptureConfig(captureConfig)   // 关键：内录配置
    .setAudioFormat(audioFormat)
    .build()
```

编码链：**PCM 原始数据 → MediaCodec AAC编码 → MediaMuxer 封装 → M4A文件**

---

## 常见问题

**Q: 弹窗说"无法录制"？**
A: 你播放的应用（如Spotify）主动禁止了系统录制，换用本地播放器试试

**Q: Android 9 或更低版本能用吗？**
A: 不能，`AudioPlaybackCapture` API 是 Android 10 新增的

**Q: 录音没有声音？**
A: 确保录音时手机音量不为0，且被录制的App正在播放音频
