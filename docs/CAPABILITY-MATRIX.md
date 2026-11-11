# 协议能力矩阵

> 小米 AI 智能眼镜（型号 M2442G1）的协议能力清单，以及开源 Android SDK 的覆盖情况。
> 帧格式、窗口与状态机见 [a5a5.md](a5a5.md) / [FORMAL.md](FORMAL.md)；本文只回答三件事：**能做什么、怎么触发、SDK 管不管**。
>
> 通道层次：`L2 channel`（1–9，传输通道）→ `WearPacket.type`（1–23，命令族）→ `WearPacket.id`（业务命令号）。
> 业务 `id` 与 protobuf 字段号没有线性关系，以协议常量定义为准。

| 标记 | 含义 |
|---|---|
| ✅ | SDK 有具名封装，直接调用 |
| 🟡 | 协议层能发，但没有高层封装（只能 `sendWear(type, id, payload)`） |
| ❌ | 刻意不做，理由见表 |
| 🔍 | 证据来自静态分析，无真机交互样本（**不标则默认真机闭环**） |

---

## 1. 连接与身份

| 能力 | 通道 · 命令 | SDK |
|---|---|---|
| SPP 建连 + 三步握手 | L1 CMD 协商 → `Account` type=1 id=26/27（AppVerify 26 → DeviceVerify 26 → AppConfirm 27 → DeviceConfirm 27）| ✅ `GlassesSession.open()` 内部完成 |
| 绑定凭据（token16）| `Account` 载荷，16 字节 | ✅ 仅作入参：SDK 不产出、不持久化 |
| 设备状态 | `System` type=2 id=1 → `DeviceStatus.battery{capacity, chargeStatus}` | ✅ `deviceStatus()` |
| 设备信息（型号 / 固件 / SN）| `System` id=2 `DEVICE_INFO` | ✅ `deviceInfo()`（真机固件 1.8.12.0） |
| 时间同步 | `System` id=3 `SetSystemTime`，`needResponse=false` | ✅ `syncTime()` |
| 电量与充电（查询 + 推送双通路）| 查询 `System` id=1；推送 id=97 `DeviceStatus.Battery`（边沿触发）| ✅ `deviceStatus().batteryPercent` / `.chargeStatus` + `GlassesEvent.BatteryChanged` |
| 修改设备名称 🔍 | `System` id=107 | ❌ 写入性操作，无实测样本 |
| 解除绑定 / 恢复出厂 🔍 | `System` id=0 `Reset` | ❌ 破坏性，会清空配对凭据 |

---

## 2. 佩戴与交互

| 能力 | 通道 · 命令 | SDK |
|---|---|---|
| 查询佩戴状态 | `System` id=93 → 1 佩戴 / 2 未佩戴 | ✅ `wearStatus()`（阻塞）/ `requestWearStatus()`（异步，结果走事件） |
| 佩戴变化上报 | `System` id=79 → 1 佩戴中 / 2 未佩戴（摘下同时触发 Voice 状态同步）| ✅ `GlassesEvent.WearChanged`（`isWorn` / `isOff`） |
| 单击 / 双击（媒体控制）| `System` id=15 `SetHeadSet{musicControl}`（1 单击 / 2 双击）| ✅ `setMusicControl()` |
| 长按触控板（语音唤醒）| `Voice` id=10 → id=0 状态流转 | ✅ `VoiceEvent.OpenDevice` / `Status` |
| 前划 / 后划（音量）| 标准 AVRCP `SetAbsoluteVolume`（0–127），**不走 SPP** | ✅ `AudioVolume` |
| 单击触控板（播放 / 暂停）| 标准 AVRCP media key，**不走 SPP** | ❌ 由 Android 宿主注册 `MediaSession` 接管 |
| 双指按压（录音）| 设备侧 `Media` id=22 上报 + `System` id=91 状态；手机侧 `Media` id=20 `RequestRecord` | ✅ `RecordStatusEvent` + `requestRecord()` / `startRecording()` / `stopRecording()` |
| 镜腿按键动作上报 | `Interconnection` type=23 id=30 `PUBLISH_MIS_SPEC` | ✅ `MisSpecEvent` + `CaptureAlertEvent` |
| 短按拍照 / 长按录像判定 | `System` id=91 的告警状态载荷（容器字段 58）：`camera.status == 5` 为录像中，`2/3` 为联机就绪 | ✅ `CaptureAlert.isRecording` / `.isCapturing` / `.isIdle` |
| 手势设置读取 | `System` id=14，按位掩码请求配置段 | ✅ `systemSetting()` |
| 手势设置写入 | `System` id=15（`SetHeadSet` / `SetMediaSetting` 等）| ✅ `setShootingMode()` / `setRecordControl()` |

> ⚠️ **改设置必须整段重发**：协议要求重发该段的完整字段集合（如 `headset` 含佩戴检测、自适应音量、防漏音、音乐控制、音效）。只发单字段会把同段其余字段覆盖成 0。

---

## 3. 语音（上行音频流）

| 能力 | 通道 · 命令 | SDK |
|---|---|---|
| 唤醒事件 | `Voice` id=10（设备→手机）| ✅ `VoiceEvent.OpenDevice` |
| 会话状态 | `Voice` id=0（设备→手机）| ✅ `VoiceEvent.Status` |
| 会话结束（优雅退出）| `Voice` id=5（手机→眼镜），`VoiceStatus` 全字段置 0 | ✅ `endVoiceSession()`；`close()` 时自动调用 |
| 错误同步 | `Voice` id=6（摘下 → 错误码 4）| ✅ `VoiceEvent.Error.isTakenOff` |
| 语音音频流 | `L2 ch=3 MASS_VOICE`，`MassType` 1/2/6/7，mSBC | ✅ `GlassesEvent.Audio`（原始帧，未解码为 PCM） |
| 多模态问答触发 | `Voice` id=4 `REQUEST_MULTI_MODAL` | ✅ `capturePhoto()`（自建或复用唤醒上下文） |
| 眼镜播音（TTS）| `Voice` 指令通道 + 标准蓝牙 A2DP 链路 | ❌ 上层业务与多媒体播放，不属 SPP 封装范围 |
| 眼动校准 🔍 | `Voice` id=14 `REQUEST_ET_CALIB` / id=13 `GET_AON_CALIB` | ❌ 未开放，无回包样本 |

---

## 4. 拍照 / 图片 / 媒体

| 能力 | 通道 · 命令 | SDK |
|---|---|---|
| 拍照触发 | `Voice` id=4 `multiModel` | ✅ `capturePhoto()` / `capturePhotoWithIds()`（真机 100% 出图） |
| 预览缩略图 | `L2 ch=3`，`MassType` 3/4 → 576×432 JPEG | ✅ `GlassesEvent.Image`（取 `event.jpeg`） |
| OCR 图 / 高清跟随图 | `MassType` 5 `IMAGE_OCR` / 11 `IMAGE_HD_FOLLOW` | ✅ 协议层可组装（后者未实测） |
| 原图 / 录像 / 原始文件 | Wi-Fi 8080：`GET /v1/filelists` → `/v1/files/<url>`（HEIC 4.8–9.8 MB）| ✅ `AlbumClient`（增量查询 / 断点下载 / 删除） |
| 录音 / 陀螺仪文件取件 | 同上 Wi-Fi 通道（音频文件 + `.gyro` 伴生文件）| ✅ `AlbumClient.download`（实测 26.4 MB / ~700 ms） |
| 相册缩略图分片 | `Media` id=17 / id=18 → `L2 ch=2 MASS` 分片（首片带 2B 长度 + identifier，100×100 PNG）| ✅ `requestMediaFile` / `requestMediaFileList` / `confirmMediaFile` + `MassFileAssembler` |
| 媒体摘要 / 列表 | `Media` id=14 / id=15（回包 id=16）| ✅ `mediaSummary()` / `listMedia()` |
| 播放状态上报 | `Media` id=1 `PLAYER_INFO`（眼镜→手机）| ✅ 事件流直接透出 |
| 录音状态 / 请求 | `Media` id=21 查询 / id=22 上报 / id=20 `RequestRecord` | ✅ `recordStatus()` + `RecordStatusEvent` + `startRecording()` / `stopRecording()` |

> ⚠️ **分流只能看 `MassType`**：MASS 数据流严格按数据头里的 `MassType` 分流，不能按帧长度推断类型。

---

## 5. 直播推流

| 能力 | 通道 · 命令 | SDK |
|---|---|---|
| 开启 / 停止 / 查询 | `Interconnection` id=46 `StartGlassLiveStreaming`、id=33/34；id=45 `liveQuery` | ✅ `startLive()` / `stopLive()` / `queryLiveStream()` |
| 推流参数 | 推流参数载荷 `{resolution, fps, bps, livePlatform, liveMode, liveUrl}` | ✅ 参数可自定义 |
| 状态回执 | `Interconnection` id=47 `Status{code, rtspUrl}`：1 开启 / 2 停止 / 3 占用 / 4 Wi-Fi 失败 / 5 RTMP 失败 / 6 过热 / 7 电量低 / 8 未佩戴 / 9 指示灯被挡 / 10、13 忙 / 11 录音中 / 12 通话中 / 14 升级中 / 15 固件重连 | ✅ `GlassesEvent.LiveStateChanged` |

- `livePlatform=0` 是标准 RTMP 直推：眼镜自己当推流客户端，宿主不需要中转。
- 第三方开源实现（`mahdi-malv/xiaolink`）交叉验证过 480p / 720p / 1080p 直推。
- SDK 只负责协调热点与下发参数，不做本地解码与播放。

---

## 6. 系统设置

| 能力 | 通道 · 命令 | SDK |
|---|---|---|
| 读取设置 | `System` id=14，`bitmask` 请求配置段（`1 shl (段号 - 1)`）| ✅ `systemSetting()` |
| 写入设置 | `System` id=15：`SetSilentUpgrade` / `SetAIAssistant` / `SetHeadSet` / `SetIndicatorLight` / `SetIndicatorVolume` / `SetMediaSetting` | ✅ `writeSystemSetting()` / `setSystemSettingField()`（读-改-写-校验） |
| 基础数据上报 | `System` id=80 `ReportData`（空载荷，`needResponse=false`）| ✅ `requestReportData()` / `requestReportDataAndWait()` |
| 语音唤醒开关 | `System` id=15 `SetAIAssistant{voiceWakeup}`（1 开 / 0 关）| ✅ `setVoiceWakeup()` |
| 语言 / 夜间模式 / 锁屏 🔍 | `System` 对应字段 | ❌ 非核心功能 |
| 查找眼镜（响铃）🔍 | `System` `FIND_MODE` | ❌ 缺安全退出机制实测 |

---

## 7. Wi-Fi 与高速传输

| 能力 | 通道 · 命令 | SDK |
|---|---|---|
| 读取 Wi-Fi 配置 | `System` id=86 `GetWiFiConfigList` | ✅ `wifiConfig()` |
| 开眼镜热点（AP）| `System` id=88 `EnableWiFiAP` → `{ssid, password, gateway}` | ✅ `enableWifiAp()` / `disableWifiAp()` + `GlassesEvent.WifiApChanged` |
| **P2P 建组取件（推荐）** | `System` id=99 `EnableWiFiP2PGC`：手机 `WifiP2pManager.createGroup()` 当 GO，眼镜作 Client，**全程无系统弹窗** | ✅ `P2pGroupOwner` + `enableWifiP2pGcAndWait()` + `connectP2pDefaultRoute()`，**需 API 29+** |
| 8080 HTTP 媒体服务 | `GET /v1/filelists`、`GET /v1/files/<url>`、`DELETE /v1/files?id=` | ✅ `AlbumClient`（实测 20–30 MB/s） |
| 网络透传 | `L2 ch=7 NETWORK`（双向，不依赖底层 ACK）| ✅ 协议层已实现 |

---

## 8. 刻意不做

| 能力 | 通道 · 命令 | 理由 |
|---|---|---|
| 固件升级准备 🔍 | `System` id=16/17 `PrepareOTA*`，数据走 `L2 ch=6 OTA` | OTA 服务端按设备 SN 白名单鉴权，第三方刷写极易变砖 |
| 升级进度上报 🔍 | `System` `OTA_PROGRESS` | 整域不开放 |
| 强制升级标志 🔍 | `System` `FORCE_UPGRADE` | 整域不开放 |
| 工厂模式查询 / 写入 🔍 | `Factory` type=13 id=6 / id=0 | 涉及产线校准参数，写入风险极高 |
| 裸字节注入 🔍 | `System` id=33 `SendMisPacket` | 绕过协议栈注入裸包，风险不可控 |
| 离线陀螺仪数据 | 相册 Wi-Fi 通道原样下载 | `.gyro` 是媒体文件的一部分，SDK 不解析 EIS 数据 |
| 实时 IMU 流 🔍 | `Factory` 与 `Fitness` 两个命令族里的传感器结构 | 设备默认不激活，无实时输出通道 |
| IMU 自检 🔍 | — | 只返回布尔判定码，无原始采样 |

此外按安全准则直接排除，不再逐条列出：

1. **支付与金融凭据** —— 协议里的第三方支付 / RPC 模块一律不碰。
2. **设备解绑与硬件重置** —— `System id=0 Reset` 会清空配对凭据（见 §1）。
3. **底层 AVRCP 拦截** —— 音量与媒体键由 Android 蓝牙栈处理，SDK 不插手（见 §2）。

---

## 9. 覆盖统计

**合计 58 项：43 项已封装（✅），15 项刻意不做（❌），无 🟡 / ⬜ 项。**

> 统计口径：§1–§7 的能力行 + §8 的刻意不做行，逐行相加。**只保留合计**——2026-11-11 曾出现分域统计表与实际行数不一致（表里 58 行、统计写 67），重算后删掉了那张表，避免再次漂移。逐项以各节表格为准。

---

## 10. 相关文档

- **L1 / SAR 协议规范**：[a5a5.md](a5a5.md)
- **形式化规范与证明**：[FORMAL.md](FORMAL.md)
- **SDK 架构与实现**：[SDK-ARCHITECTURE.md](SDK-ARCHITECTURE.md)
