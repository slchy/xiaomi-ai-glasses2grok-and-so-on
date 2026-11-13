# xiaomi-ai-glasses2grok-and-so-on

**Third-party Bluetooth SPP protocol analysis and an Android SDK for the Xiaomi AI Glasses (M2442G1).**

[English](README.en.md)<br>
[演示视频](https://www.bilibili.com/video/BV1jn456gEeQ/)

[![CI](https://github.com/slchy/xiaomi-ai-glasses2grok-and-so-on/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/slchy/xiaomi-ai-glasses2grok-and-so-on/actions/workflows/ci.yml)

针对小米 AI 智能眼镜（型号 M2442G1）的第三方蓝牙 SPP 通信协议解析与 Android SDK 驱动实现。

---

## 技术文档与规范

- **协议底层规范**：见 [docs/a5a5.md](docs/a5a5.md)（L1 帧格式、滑动窗口、魔数重同步与 SAR 状态机）。
- **协议形式化规范与证明**：见 [docs/FORMAL.md](docs/FORMAL.md)（状态机不变量、GBN 窗口可靠性与反例探索）。
- **协议能力矩阵**：见 [docs/CAPABILITY-MATRIX.md](docs/CAPABILITY-MATRIX.md)（涵盖 58 项硬件与协议能力、实测证据等级与 SDK 封装覆盖）。
- **SDK 架构与实现**：见 [docs/SDK-ARCHITECTURE.md](docs/SDK-ARCHITECTURE.md)（7 模块解耦设计、无锁并发线程模型、车道背压与状态机）。
- **帧解析参考实现（Python）**：位于 `python/a5a5.py`。
- **Android SDK 完整工程**：位于 `sdk/`。
- **自建云端语音后端**：位于 `cloud/`（mSBC 解码 → 判停 → ASR → Agent → TTS，见下）。
- **云端架构**：见 [docs/CLOUD-ARCHITECTURE.md](docs/CLOUD-ARCHITECTURE.md)（分层、并发模型、状态机、错误模型与扩展点）。

---

## Android SDK

SDK 采用严格的分层单向依赖架构，划分为 7 个模块：

| 模块 | 类型 | 坐标 / 产物 | 职责说明 |
|---|---|---|---|
| `:core-proto` | JVM | 内部实现 | Varint / Hex / CRC16-ARC / MiniJson 基础工具 |
| `:core-api` | JVM | 内部 API | 公开契约层：状态定义、事件模型、查询 DTO 与常量 |
| `:core` | JVM | 内部实现 | 核心协议引擎：L1 解包、L2 多路复用、SAR 状态机、握手认证 |
| `:transport` | Android | 内部实现 | 蓝牙 SPP Socket I/O、后台泵线程与系统广播监听 |
| `:facade` | Android | `io.github.slchy.glasses2grok:sdk` | **主对外门面 AAR**：导出 `GlassesSession` 与高层 API |
| `:ktx` | Android | `io.github.slchy.glasses2grok:sdk-ktx` | **可选协程扩展 AAR**：提供 `Flow<GlassesEvent>` 与挂起扩展 |
| `:sample` | Android | 演示应用 | 单页仪表盘（连接 / 握手 / 状态 / 诊断 + 日志复制）与协议探针面板 |

### 快速接入 (Gradle)

目前**没有发布到 Maven Central**，先用本地仓库：

```bash
cd sdk
./gradlew publishToMavenLocal        # Windows: gradlew.bat publishToMavenLocal
```

然后让消费工程能解析 `mavenLocal()`（如果你的构建限制了仓库，需要显式加进
`pluginManagement` / `dependencyResolutionManagement`）：

```kotlin
// build.gradle.kts
repositories {
    mavenLocal()
    mavenCentral()
    google()
}
dependencies {
    implementation("io.github.slchy.glasses2grok:sdk:0.2.2")
    implementation("io.github.slchy.glasses2grok:sdk-ktx:0.2.2") // 可选，提供 Kotlin 协程 / Flow 支持
}
```

### 快速上手 (Kotlin)

```kotlin
import io.github.slchy.glasses2grok.core.session.GlassesEvent
import io.github.slchy.glasses2grok.sdk.GlassesSession

// 1. 获取已配对的蓝牙设备与 16 字节鉴权 Token
val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice("...")
val token16: ByteArray = ... // 16 字节硬件 Token

// 2. 异步开启会话（非阻塞，可在任意线程调用）
val session = GlassesSession.open(context, device, token16)

// 3. 注册事件监听器
session.addListener { event ->
    when (event) {
        is GlassesEvent.StateChanged -> println("会话状态: ${event.state}")
        is GlassesEvent.WearChanged  -> println("佩戴状态: ${event.status}")
        is GlassesEvent.BatteryChanged -> println("电量变化: ${event.status.batteryPercent}%")
        is GlassesEvent.Image -> {
            println("收到拍摄预览图: ${event.jpeg.size} 字节")
        }
        is GlassesEvent.Audio -> {
            // 实时上行音频流（mSBC 裸流）
        }
        is GlassesEvent.Error -> {
            System.err.println("错误 [${event.code}]: ${event.message}")
        }
        else -> Unit
    }
}

// 4. 调用高层业务能力
session.capturePhoto() // 触发拍照
session.syncTime()     // 同步设备时间

// 5. 使用 Kotlin Flow 消费（需引入 sdk-ktx）
// lifecycleScope.launch {
//     session.events().collect { event -> ... }
//     session.audio().collect { chunk -> ... }
// }

// 6. 优雅关闭会话
session.close()
```

### 构建与测试

**环境要求**：JDK 17（AGP 8.5 的下限）、Android SDK（`compileSdk 34`，`minSdk 24`）。
Android 模块需要能找到 SDK：设 `ANDROID_HOME`，或在 `sdk/local.properties` 写 `sdk.dir=<路径>`
（该文件已在 `.gitignore` 里，不会入库）。

SDK 包含覆盖全协议栈的纯 JVM 单元测试与黄金向量回归：

```bash
cd sdk
./gradlew test          # 全部模块的单测（经纯 JVM 仿真，不需要真机）
./gradlew build         # 额外做 lint 与各 AAR 打包
```

---

## 云端语音后端（可选）

`sdk/` 管「手机 ↔ 眼镜」，`cloud/` 管「手机 ↔ 大模型」。手机把眼镜的上行 mSBC **原样**转发过来，
云端解码 → 判停 → ASR → Agent → TTS，再把 PCM 流式推回手机播放：

> 分层、并发模型、状态机与错误模型见 [docs/CLOUD-ARCHITECTURE.md](docs/CLOUD-ARCHITECTURE.md)。

```text
眼镜 ──SPP──▶ 手机（sdk/sample）──TLS/TCP──▶ cloud/ ──▶ ASR ──▶ Agent ──▶ TTS
                                               └─ 原始档案 / 离线补播 / 调试控制口
```

| 环节 | 默认实现 | 换成别的 |
|---|---|---|
| 上行解码 | `ffmpeg`（mSBC → PCM16 16 kHz） | — |
| 判停 | 能量门；可选 SmartTurn / silero-vad（`models/`，需自行下载） | `SMART_TURN=0` 关掉 |
| ASR | 阿里云实时语音识别 | `--stt canned`（回放固定一句）或实现 `providers.ts` 的接口 |
| Agent | 命令式 agent（默认用 `dsh` 的 SDK 会话） | `--agent echo` / `--agent none` |
| TTS | MiMo（聊天式风格提示词：情绪标签可切语调） | `--tts silent` 或实现 `providers.ts` 的接口 |

不接真机、不填任何凭据也能跑通整条链路：

```bash
cd cloud
node src/main.ts --listen 8766 --stt canned --tts silent --agent echo   # 终端 1
node tools/fake-phone.ts --port 8766 --sbc ../some-capture.sbc          # 终端 2（假手机）
```

- 需要 **Node 22.6+**（直接跑 `.ts`：22.x 加 `--experimental-strip-types`，23+ 默认开启）。
- 系统里的 `ffmpeg` 是唯一必需的外部命令；`onnxruntime-node` 只在启用 silero-vad 时才加载。
- `--sbc` 给一段 mSBC 录音即可模拟说话；不给也能连上，只是没有上行音频。
- 接真模型：`cp .env.example .env` 填 ASR/TTS 凭据，再换掉 `--stt/--tts/--agent`。

**凭据一律不入库**：`.env`、`tokens.txt`、自签证书都在 `.gitignore` 里；仓库里只有空模板。

纯逻辑单测（不需要网络与凭据）：

```bash
node --test tests/*.test.ts
```

许可：`cloud/` 采用 [MIT License](LICENSES/MIT.txt)。

---

## Python 快速验证

针对 L1 帧解包与校验，仓库提供了无第三方依赖的 Python 参考实现：

```bash
python tests/test_frame.py
```

```python
from a5a5 import build, parse

frame = build(b"hi", ftype=3, seq=0)
frames, bad_crc, resync, dropped = parse(frame)
```

> 调用时请将 `python/` 加入 `PYTHONPATH`。

---

## 许可证 (Licensing)

本项目采用多许可证分层管理，详见 [LICENSE.md](LICENSE.md)：

- **协议规范与帧参考实现 (`docs/`, `python/`)**：[CC0 1.0 Universal](LICENSES/CC0-1.0.txt)（公有领域奉献）
- **Android SDK 核心驱动 (`sdk/core`, `transport`, `facade`, `ktx`)**：[PolyForm Noncommercial License 1.0.0](LICENSES/PolyForm-Noncommercial-1.0.0.txt)（仅限非商业研究）
- **示例应用代码 (`sdk/sample/`)**：[MIT License](LICENSES/MIT.txt)

---

## 免责声明 (Disclaimer)

1. 本项目为独立研究者的第三方开源成果，**与小米科技（Xiaomi）或蜂巢科技（Superhexa）无任何附属、赞助或认可关系**。
2. 所有提及的产品名称与商标版权均归原厂商所有，在此仅用于客观技术兼容性说明。
3. 本项目不提供、不代取、不保存任何硬件访问凭据（Token），使用本项目的研究者须自行确保对所连接的硬件拥有合法的所有权与操作权限。