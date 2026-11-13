# Android SDK 架构设计与实现

> 本文描述 Xiaomi AI 智能眼镜（M2442G1）开源 Android SDK 的模块划分、类职责、线程模型、
> 状态机、数据流、背压与错误模型。
> 帧格式与不变量见 [a5a5.md](a5a5.md) 与 [FORMAL.md](FORMAL.md)；本文只讲**实现怎么摆**。
>
> **对账基准**：`sdk/` 当前代码 + 导出树实测（368 项单测，0 失败）。
> 文档与代码冲突时以代码为准。

---

## 1. 模块化架构

7 个 Gradle 模块，严格单向依赖：

```text
sdk/
├─ settings.gradle.kts
├─ core-proto/    :core-proto   纯 JVM：varint、Hex、CRC16-ARC、精简 JSON
├─ core-api/      :core-api     纯 JVM：**公开契约层**（事件、状态、查询 DTO、协议常量）
├─ core/          :core         纯 JVM：**引擎**（L1 解包、L2 复用、SAR 状态机、握手、加密）
├─ transport/     :transport    Android 库（minSdk 24）：SPP socket I/O、泵线程、系统广播
├─ facade/        :facade       Android 库：**公开门面**，产出 `io.github.slchy.glasses2grok:sdk` AAR
├─ ktx/           :ktx          Android 库：可选协程扩展，产出 `io.github.slchy.glasses2grok:sdk-ktx` AAR
└─ sample/        :sample       演示 App（可直接 adb install）
```

### 1.1 依赖与隔离

```text
ktx ──api──▶ facade ──api──▶ core-api ──implementation──▶ core-proto
                  ├──implementation──▶ core ──api──▶ core-api
                  │                          └──implementation──▶ core-proto
                  └──implementation──▶ transport ──api──▶ core
sample ──implementation──▶ facade
```

| 模块 | 类型 | 产物 | 接入者可见 | 职责边界 |
|---|---|---|---|---|
| `:core-proto` | JVM | jar | 不可见 | 无状态字节工具（`Pb` / `Hex` / `Crc16Arc` / `MiniJson`） |
| `:core-api` | JVM | jar | **公开** | 事件模型、状态、查询 DTO、协议常量 |
| `:core` | JVM | jar | 不可见 | 协议引擎与状态机 |
| `:transport` | Android | aar | 不可见 | 蓝牙 socket、I/O 调度、时钟 |
| `:facade` | Android | **AAR `sdk`** | **公开** | 唯一入口 `GlassesSession` |
| `:ktx` | Android | **AAR `sdk-ktx`** | **公开** | `Flow<GlassesEvent>` 与挂起扩展 |
| `:sample` | Android | apk | 示例 | 端到端演示与冒烟 |

- **纯 JVM 协议层**：`core-proto` / `core-api` / `core` 不引用任何 `android.*`，
  所以滑动窗口、握手、加密、MASS 重组都能在桌面毫秒级回归。
- **公开面收敛**：`:core` 与 `:transport` 是 `implementation`，`SarEngine`、`L1Parser`、
  `HandshakeDriver`、`EnginePump` 都**不在**消费者的编译类路径上 —— 误用内部组件导致
  状态失步的路径被编译期堵死。

---

## 2. 分包与类职责

### 2.1 `:core-api`（契约层）

```text
io.github.slchy.glasses2grok.core
├─ session/   SessionState · GlassesEvent · ErrorCode
├─ config/    GlassesProtocol · GlassesOptions · GlassesEndpoints
├─ query/     QueryIds · QueryResult · DeviceInfo · DeviceStatus · MediaSummary · MediaList
│             RecordStatus · GestureSettings · ReportData
├─ interconn/ WearStatus · MisSpec · CaptureAlert · Interconnection
├─ live/      LiveStreaming（Resolution / FrameRate / StreamMode / Mode / LiveState）
├─ voice/     VoiceIds · VoiceEvent · VoiceCodec
├─ wifi/      WifiAp · WifiP2p（Ids / Codec / Info / Result）
├─ media/     RecordIds · RecordType · RecordAction · RecordCodec · MediaFile* · MediaFormat
├─ album/     AlbumApi · AlbumEntry · AlbumFile
├─ wear/      WearPacket · WearCodec
├─ control/   ControlDecision · ControlRouter（判定表）
├─ mass/      MassFileData
├─ system/    SystemTimeCodec
└─ sar/       SarConfig · Clock
```

`GlassesEvent` 的全部成员（宿主实际收到的就是这些）：

`StateChanged` · `Audio` · `Image` · `Control` · `MisSpecEvent` · `CaptureAlertEvent` ·
`WearChanged` · `RecordStatusEvent` · `BatteryChanged` · `WifiApChanged` · `WifiP2pChanged` ·
`MassFile` · `ReportDataReceived` · `LiveStateChanged` · `Voice` · `Error` · `Diagnostic`

### 2.2 `:core`（引擎）

```text
io.github.slchy.glasses2grok.core
├─ session/   SessionEngine（唯一真实状态）· ByteTransport
├─ sar/       SarEngine（GBN 窗口 / ACK / NAK / 车道配额）· SarInternals
├─ handshake/ HandshakeDriver（协商 → AppVerify → DeviceVerify → AppConfirm → DeviceConfirm）
├─ crypto/    Crypto（HMAC-SHA256 / HKDF / AES-CTR）· SessionKeys
├─ l1/        L1（帧构造）· L1Parser（魔数重同步 + CRC）
├─ l2/        L2 · L2Codec
├─ control/   ControlRouter（实现）
├─ mass/      MassAssembler（语音/图片重组）· MassFileAssembler（`ch=2` 文件分片）· MassCodec
└─ media/     MediaFile
```

| 组件 | 职责 | 边界 |
|---|---|---|
| `L1Parser` | 滑动窗口找 `A5 A5`、重同步、CRC16 校验 | 只解一帧，不管重传 |
| `SarEngine` | Go-Back-N、累积 ACK、NAK、重传、三车道配额 | 纯状态机，不自建线程，时钟外部注入 |
| `HandshakeDriver` | 推进握手三步、算/验 HMAC-SHA256 | 结构化 protobuf 解析，不靠字节偏移 |
| `ControlRouter` | `(type, id)` 分发与通道合法性 | 未知类型安全丢弃，不猜结构 |
| `MassAssembler` | 按 `MassType` 分流，重组 mSBC / JPEG | 不解码，原样交付；图片流按 `FIRST_IMAGE` 定边界 + 15 s 空闲超时 |
| `SessionEngine` | 串起 L1/L2/SAR/握手/加密/控制面 | 状态转移单调 |

### 2.3 `:transport`（传输）

```text
io.github.slchy.glasses2grok.transport
├─ SppLink          流套接字抽象（可用性门禁 + 关闭原因）
├─ AndroidSppLink   BluetoothSocket 适配：看门狗、系统广播、重配对节流
├─ EnginePump       引擎线程：任务队列 / 有界 RX 队列（世代号）/ 周期 tick
├─ SessionDriver    supervisor：建连、退避重连、终态保障
├─ ConnectGate      connect() 与看门狗的窄竞态裁决（CAS，只有一方赢）
├─ Backoff          指数退避（抖动 + 上限）
├─ RxGapTracker     累计 RX 丢弃 → 单块音频空洞换算
└─ UptimeClock      Clock 实现：`SystemClock.elapsedRealtime()`（CLOCK_BOOTTIME，含休眠）
```

> 用 `elapsedRealtime()` 而不是 `nanoTime()`：后者在深度睡眠期间不前进，而 SAR 重传超时、
> 退避、连接门禁都建立在这条时间轴上，休眠后醒来会「欠账」。同一模块的重配对节流本来
> 就用 `elapsedRealtime()`，两处现已统一。

### 2.4 `:facade`（公开门面）

```text
io.github.slchy.glasses2grok.sdk
├─ GlassesSession     唯一入口：open / 查询 / 发送 / 写设置 / close
├─ GlassesEventSource 事件源接口（state + 订阅）
├─ GlassesListener    fun interface onEvent(GlassesEvent)
├─ SessionOptions     回调线程 / 建连超时 / 重试上限 / tick / 诊断
├─ CallbackDispatcher MainThread · SingleThread · CallingThread
├─ SendResult         Queued（**SAR 已受理**）· Rejected(code, reason)
├─ SettingWriteResult Ok(segment, value) / 失败分支（写设置是阻塞往返，与 SendResult 分开）
├─ SdkErrorCode       错误码常量（数字唯一来源是契约层 `ErrorCode`）
├─ AlbumClient        AP / P2P 上的 HTTP 相册（列表 / 下载 / 删除）
├─ AudioVolume        标准 AVRCP 绝对音量封装
├─ P2pGroup           P2P 组信息（网络名 / 口令 / 状态）
│  └─ P2pGroupOwner   手机侧 createGroup() 当 GO，让眼镜加入
├─ EventDispatcher    回调线程调度（内部）
└─ CallbackQueue      回调积压上限 256 + 丢弃计数（内部）
```

> 顶层公开类型 **11** 个；`EventDispatcher` / `CallbackQueue` 是 `internal`，
> 但 `callbackDrops` 会把后者的计数透出来。

---

## 3. 线程模型

```text
  ┌────────────────┐
  │ supervisor 线程 │ 门禁 → 建连 → 起 RX → 等断开 → 退避重连
  └───────┬────────┘ 跨层调用走 pump.call() 取回执，不直接读写协议变量
          │ connect() 投递到连接池，带超时看门狗
  ┌───────▼────────┐
  │  RX 读取线程    │ 独占阻塞 read()，只搬字节，不解析
  └───────┬────────┘
          │ 有界字节队列 256 KB（满则丢最旧，计入 droppedRxBytes / droppedRxChunks）
  ┌───────▼────────┐
  │  引擎线程       │ L1 解帧 → SAR 推进 → 分流 → 定时器；socket.write() 也在这一线程
  └───────┬────────┘
          │ 状态变更与业务事件
  ┌───────▼────────┐
  │  回调派发       │ 默认主线程（CallbackDispatcher 可换专属线程或直调）
  └────────────────┘
```

1. **单线程状态机**：解包、窗口更新、重传计时、编码全在引擎线程顺序执行，引擎内部**无锁**。
2. **RX 独立读**：`write()` 在对端不读时会阻塞，读写同线程会互相等死，所以 RX 单独一条线程。
3. **世代号隔离**：换 socket 时递增世代号并清空 RX 队列，旧 socket 的尾巴不会被当成新会话的帧。
4. **非阻塞外壳**：`open()` 立即返回；`close()` 立刻把状态推到 `CLOSING` 并在后台收尾，
   `Activity.onDestroy` 里调用不会 ANR。
5. **RX 丢弃对宿主可见**：`rxDroppedChunks` / `rxDroppedBytes` / `rxStaleChunks` 直接透出泵的计数；
   语音块上另有 `droppedBefore`（见 §5.1），用来区分「设备没说话」和「本端把块丢了」。

---

## 4. 状态机与握手

```text
IDLE ──open()──▶ CONNECTING ──socket 建连──▶ HANDSHAKING ──DeviceConfirm──▶ READY
                    │                           │                            │
                    │ 建连失败 / 重试耗尽         │ 握手失败                    │ 链路断开
                    ▼                           ▼                            ▼
                  FAILED ◀─────────────────────┘                     RECONNECTING
                  （终态）                                               │ 退避完成
                                                                        └──▶ CONNECTING
任意状态 ──close()──▶ CLOSING ──▶ CLOSED（终态）
```

`SessionState` 共 8 个值：`IDLE / CONNECTING / HANDSHAKING / READY / RECONNECTING / CLOSING / CLOSED / FAILED`，
终态是 `CLOSED` 与 `FAILED`。

### 4.1 握手时序

```text
客户端 (SDK)                                    眼镜
   │──── L1 CMD 协商 (seq=0) ───────────────────▶│
   │◀─── L1 CMD 应答（带 MPS / TX_WIN / 超时）────│
   │──── AppVerify   (type=1 id=26, AppRandom) ─▶│
   │◀─── DeviceVerify(type=1 id=26, DeviceSign) ─│
   │    [HMAC-SHA256 验签]
   │──── AppConfirm  (type=1 id=27, AppSign) ───▶│
   │◀─── DeviceConfirm(type=1 id=27) ────────────│
   │    [READY]
```

- **重连必须重新协商**：换 socket 就重置 L1 序列号并重做握手，不复用旧密钥。
- **建连失败**走 `maxConnectAttempts` 退避；**蓝牙关闭 / 未配对**时 supervisor 挂起等待
  （`isAvailable()` 门禁），不烧重试预算。
- **握手失败是终态**，但按成因分码：`108` = 设备签名对不上（token 错，重试无意义）、
  `208` = 超时 / 没等到 DeviceVerify（瞬时，宿主可退避后重新 `open()`）。
  SDK 不自动重连 —— 那会把「设备不可用」伪装成「正在连接」。

---

## 5. 数据流

### 5.1 通道复用

| 业务 | 通道 | 标识 | 处理 | 交付 |
|---|---|---|---|---|
| 控制信令 | `CH_PB`(1) | `opCode=2`（加密） | `WearCodec` 解码 → `ControlRouter` | `GlassesEvent.Control` |
| 实时音频 | `CH_MASS_VOICE`(3) | `MassType` 1/2/6/7 | `MassAssembler` 切块 | `GlassesEvent.Audio(data, seq, timestampMs, droppedBefore)` |
| 拍摄图片 | `CH_MASS_VOICE`(3) | `MassType` 3/4，+11 高清续片 | 按 `FIRST_IMAGE` 重组 | `GlassesEvent.Image(jpeg)` |
| 相册缩略图 | `CH_MASS`(2) | 分片 | `MassFileAssembler` | `GlassesEvent.MassFile` |
| 语音事件 | `CH_PB`(1) | `type=14` | `VoiceCodec` | `GlassesEvent.Voice(VoiceEvent)` |

> **只看 `MassType` 分流，不按帧长猜类型**：图片分片可能恰好 582 字节，与语音包同长。

**音频块没有设备序号** —— MASS 12 字节头里既无 seq 也无时间戳，所以 `Audio` 的 `seq`
（会话内自增）与 `timestampMs`（引擎时钟）是**本端生成**的，`droppedBefore` 由 `:transport` 补。
典型一块是 **582 B = 10 × 57 B mSBC（75 ms）**。

**图片流边界与超时**：以 `FIRST_IMAGE` 作为「新图开始」的唯一结构信号（总长变化只作兜底），
否则「截断 + 同总长」会把两段字节拼成一张损坏的 JPEG；另加 tick 驱动的 15 s 空闲超时，
超时丢弃计入 `droppedByTimeoutCount`，与「被新流覆盖」的 `droppedIncompleteCount` 分开计。

### 5.2 配额与背压

| 车道 | 配额 | 满时行为 | 为什么 |
|---|---:|---|---|
| 控制 (Control) | 256 帧 | 拒绝入队，返回 `QUEUE_FULL` | 控制命令不能被音频饿死 |
| 音频 (Audio) | 64 帧 | 拒新帧并计数 | 语音有时效，宁可丢也不拖垮信令 |
| 图片 (Image) | 4 块 | 拒绝入队 | 限制内存驻留 |
| 回调 (Callback) | 256 条 | 丢最旧的非关键事件并计数 | 终态事件（`CLOSED` / `FAILED`）强制保全 |
| RX 字节队列 | 256 KB | 丢最旧并计数 | 解析慢于收包时，让「谁慢谁丢」可观测 |

SAR 只有**一条有序 seq 流**（Go-Back-N 不允许乱序），所以「车道」不是三条独立队列，
而是同一条队列上的三类配额：某类挤满只拒它自己。

---

## 6. 错误模型

错误是**带码的事件 + 同步拒绝**，不是异常，也不是字符串：

```kotlin
public object ErrorCode {
    const val NONE                      = 0
    const val AUTH_FAILED_TOKEN_INVALID = 108   // 设备签名对不上（token 错，重试无意义）
    const val TRANSPORT                 = 201   // socket / IO / 建连失败 / 重连耗尽
    const val PROTOCOL                  = 202   // 帧长/CRC/魔数、解密、报文解析失败
    const val TIMEOUT                   = 203   // 命令回执超时
    const val NOT_READY                 = 204
    const val CLOSED                    = 205
    const val NO_WAKEUP                 = 206   // 保留未用（v0 早期用过）
    const val QUEUE_FULL                = 207   // 发送车道满，该帧未入队
    const val HANDSHAKE_TRANSIENT       = 208   // 握手超时/没等到应答：终态，但重试有意义
}
```

`GlassesEvent.Error(code, message, cause)` 承载异步失败；`SendResult.Rejected(code, reason)`
承载**当场可知**的失败，两者用同一套码。

| 码 | 典型产出点 |
|---|---|
| 108 | 握手 `BadDeviceSign` |
| 201 | 建连失败 / 重连耗尽 / 传输中断 / 引擎异常 |
| 202 | 帧长/CRC、L2 头不足、解密失败、WearPacket 解析失败、密钥缺失、载荷超过远端 MPS |
| 203 | 查询类命令回执超时 |
| 204 | 未 READY 时发送 |
| 205 | 会话已关闭 |
| 207 | 发送车道配额满（消息里带车道名与配额） |
| 208 | 握手协商 / 验签 / 确认超时、没等到 DeviceVerify |

**`SendResult` 的语义边界**：`Queued` = **SAR 已受理**（帧进了发送车道、字节交给了链路），
**不是**「设备已收到」—— 那是 ACK 的事，用查询接口或事件确认。当场能判定的失败一律走
`Rejected`，不必等异步事件。写设置走 `SettingWriteResult`（阻塞往返 + 读回校验），
因为「已入队」对写设置没有意义。

---

## 7. 公开 API

### 7.1 `GlassesSession`

```kotlin
// 建连（非阻塞，立即返回 CONNECTING 会话）
GlassesSession.open(context, device, token16: ByteArray, options: SessionOptions = SessionOptions())

// 查询（阻塞，带超时；结果在 QueryResult.Ok / QueryResult.Failed 里）
deviceInfo(3_000)  deviceStatus(3_000)  wearStatus(3_000)  mediaSummary(3_000)
listMedia(5_000)   recordStatus(3_000)  wifiConfig(3_000)  systemSetting(...)
requestReportDataAndWait(5_000)  enableWifiApAndWait(5_000)  enableWifiP2pGcAndWait(...)

// 发送（非阻塞，返回 SendResult）
requestWearStatus()  syncTime(...)  endVoiceSession()
capturePhoto()  capturePhotoWithIds(...)
requestMediaFile(...)  requestMediaFileList(...)  confirmMediaFile(...)
requestRecord(...)  startRecording(...)  stopRecording(...)
startLive(...)  stopLive()  queryLiveStream()
enableWifiAp()  disableWifiAp(...)  setWifiConfig(...)  enableWifiP2pGc(...)
requestReportData()
sendWear(type, id, payload)      // 逃生口：未封装能力的低阶下发

// 写设置（阻塞往返 + 读回校验，返回 SettingWriteResult）
setMusicControl(...)  setShootingMode(...)  setRecordControl(...)  setVoiceWakeup(...)
writeSystemSetting(...)  setSystemSettingField(...)

// 诊断
callbackDrops  rxDroppedChunks  rxDroppedBytes  rxStaleChunks
rawSendCount   canCapturePhoto

// 订阅与生命周期
state  setListener(...)  addListener(...)  removeListener(...)
close()  closeAndWait()
```

`SessionOptions`：`callbackDispatcher`（默认主线程）· `connectTimeoutMs`（15 s）·
`maxConnectAttempts`（5）· `tickMs`（50）· `diagnostics`（额外发 `Diagnostic` 事件）。

### 7.2 `:ktx`

```kotlin
session.events().collect { event -> ... }      // Flow<GlassesEvent>
session.audio().collect { chunk -> ... }       // Flow<ByteArray>（只放行 Audio 载荷）
session.awaitReady(timeoutMs = 15_000)         // 挂起到 READY；超时抛 SdkTimeoutException
```

> `events()` 与 `audio()` 各自 `setListener` —— 同时用会互相顶掉，只能留一个。

---

## 8. 测试与持续集成

1. **黄金向量**：`tests/golden/vectors.txt` 固化 L1 帧、CRC、HKDF/HMAC 与 MASS 头，
   纯 JVM 实现与抓包逐字节对账。
2. **纯 JVM 仿真**：`sdk/` 共 **368 项**单元测试（导出树实测，0 失败），`FakeClock` 推进时间，
   不连硬件就能覆盖滑动窗口、超时、重传与背压。
3. **Python 参考实现**：`python/a5a5.py` + `tests/test_frame.py`（14 项），与 Kotlin 共用同一份向量。
4. **CI**：`.github/workflows/ci.yml` 在每次 push / PR 跑 `./gradlew test`、`./gradlew assemble`
   与 Python 用例；`release.yml` 在 `v*` tag 上打包 jar / AAR / APK 并发版。
5. **隔离性**：`:sample` 代表「只依赖 `:facade` 的消费者」，构建时若有人把 `:core` 漏回
   公开类路径会直接编译失败。

---

## 9. 明确没做的

- **音频不解码**：交付 mSBC 原始载荷，PCM 解码与播放是宿主的事。
- **不做丢帧补偿**：`Audio.seq` 只用于观测「本端有没有漏」，不做填补或重排。
- **不做大图流式交付**：图片整块交付，`maxImageBytes`（8 MB）超限即丢并计数。
- **不做自动重连兜底**：握手失败是终态，重试由宿主按错误码决定。
- **不做 OTA / 工厂 / 解绑**：见 [CAPABILITY-MATRIX.md](CAPABILITY-MATRIX.md) 的「刻意不做」一节。
