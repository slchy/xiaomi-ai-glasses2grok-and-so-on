# Android SDK 架构设计与实现

> 本文系统阐述 Xiaomi AI 智能眼镜（M2442G1）开源 Android SDK 的模块划分、包与类设计、线程模型、状态机运转、数据流分流、背压保护与错误处理机制。
> 协议底层二进制规范请参阅 [docs/a5a5.md](a5a5.md) 与 [docs/FORMAL.md](FORMAL.md)。

---

## 1. 模块化架构

SDK 采用严格的分层单向依赖架构，划分为 7 个模块：

```text
sdk/
├─ settings.gradle.kts
├─ core-proto/    :core-proto   纯 JVM 模块：基础协议工具（varint、Hex、CRC16、精简 JSON 解析等）
├─ core-api/      :core-api     纯 JVM 模块：**公开契约层**（事件、状态模型、查询 DTO、能力常量、编解码器）
├─ core/          :core         纯 JVM 模块：**核心引擎**（L1 解包、L2 多路复用、SAR 状态机、握手认证、加密通道）
├─ transport/     :transport    Android 库（minSdk 24）：蓝牙 SPP Socket I/O、后台泵线程与系统广播监听
├─ facade/        :facade       Android 库：**开发者公开门面**，产出 `com.miaiglasses:sdk` AAR
├─ ktx/           :ktx          Android 库：可选协程扩展，产出 `com.miaiglasses:sdk-ktx` AAR（提供 Flow 适配）
└─ sample/        :sample       Android 示例与冒烟验证应用
```

### 1.1 依赖与隔离原则

各模块依赖关系严格保持单向，杜绝循环依赖与内部实现泄漏：

```text
ktx ──api──> facade ──api──> core-api ──implementation──> core-proto
                 └──implementation──> core ──api──> core-api
                 └──implementation──> transport ──api──> core
```

| 模块 | 类型 | 发布产物 | 对接入者可见性 | 职责边界 |
|---|---|---|---|---|
| `:core-proto` | JVM | jar | 内部私有 | 基础无状态字节与编解码辅助工具 |
| `:core-api` | JVM | jar | **完全公开** | 状态定义、事件模型、查询结构体与协议常量 |
| `:core` | JVM | jar | 内部私有 | 协议引擎与状态机，通过 `implementation` 封装 |
| `:transport` | Android | aar | 内部私有 | 蓝牙套接字、I/O 调度与系统层事件处理 |
| `:facade` | Android | **AAR (`sdk`)** | **完全公开** | 唯一公开入口，导出 `GlassesSession` 与监听器 |
| `:ktx` | Android | **AAR (`sdk-ktx`)** | **完全公开** | 提供 `Flow<GlassesEvent>` 与挂起扩展函数 |
| `:sample` | Android | apk | 示例程序 | 宿主接入示范与端到端功能验证 |

- **无 Android 依赖的纯 JVM 协议层**：`:core-proto`、`:core-api` 与 `:core` 均为纯 Kotlin/JVM 模块，不引入任何 `android.*` 依赖，可在桌面单测环境进行毫秒级高并发回归。
- **严格的公开面收敛**：`:facade` 仅向接入者暴露 `core-api` 中的数据契约和门面类。引擎核心（如 `SarEngine`、`L1Parser`、`HandshakeDriver` 等）被限制在编译期沙箱内，防止宿主误用内部组件导致状态失步。

---

## 2. 核心包与类设计

### 2.1 契约层与引擎层 (`:core-api` / `:core`)

```text
com.miaiglasses.core
├─ [core-api] 会话状态与事件   session/ SessionState · GlassesEvent · GlassesEventSource
├─ [core-api] 协议端点与配置   config/ GlassesEndpoints · GlassesOptions · GlassesProtocol
├─ [core-api] 查询模型与结果   query/ QueryIds · QueryResult · DeviceInfo · DeviceStatus · MediaSummary
│                                     MediaList · RecordStatus · MediaKind · ReportDataResult
├─ [core-api] 设备能力与语音   live/ · interconn/ · wifi/ · voice/(VoiceIds/VoiceEvent/VoiceCodec)
├─ [core-api] 媒体与相册模型   media/(Record* · MediaFormat) · album/(AlbumApi/AlbumEntry/AlbumFile)
├─ [core-api] 载荷协议 DTO     wear/WearPacket · control/ControlDecision · mass/MassFileData
├─ [core-proto] 协议工具       Pb (varint) · Hex · Crc16Arc · MiniJson
└─ [core] 协议驱动与调度引擎   sar/SarEngine · handshake/HandshakeDriver · crypto/(Crypto/SessionKeys)
                              l1/(L1/L1Parser) · l2/L2 · control/ControlRouter · mass/MassAssembler
                              session/(SessionEngine/ByteTransport)
```

| 核心组件 | 职责定义 | 边界约束 |
|---|---|---|
| `L1Parser` | 滑动窗口魔数同步（`A5 A5`）、报文重同步、CRC16 校验 | 仅做单帧解包与切分，不介入重传逻辑 |
| `SarEngine` | Go-Back-N 滑动窗口、累积确认 ACK、选择性拒绝 NAK、帧重传与发送配额控制 | 纯状态机逻辑，不自建线程，时钟由外部注入 |
| `HandshakeDriver` | 驱动 SPP 握手协议推进与基于 Token 的 HMAC-SHA256 签名计算校验 | 严格结构化解析，不依赖特定字节偏移硬编码 |
| `ControlRouter` | `(type, id)` 业务命令分发与合法通道校验 | 遇到未知类型安全丢弃，不臆测载荷结构 |
| `MassAssembler` | 按 `MassType` 切片分流，重组首帧与后续帧（语音 mSBC / 图片 JPEG） | 仅交付完整二进制载荷，不强制引入系统级多媒体解码库 |
| `SessionEngine` | 协调 L1/L2、SAR、握手、加密与控制面，维护唯一真实状态 | 保证状态转移单调一致 |

### 2.2 传输层 (`:transport`)

```text
com.miaiglasses.transport
├─ SppLink         底层流套接字抽象接口（提供可用性门禁与关闭原因追踪）
├─ AndroidSppLink  Android BluetoothSocket 适配实现、看门狗定时器与系统广播监听
├─ EnginePump      引擎调度循环：任务队列、有界 RX 缓冲区（带世代号）、周期 tick 调度
├─ SessionDriver   Supervisor 监督线程，管理连接建立、指数退避重连与终态保障
├─ Backoff         指数退避重试控制器（带抖动与上限控制）
└─ UptimeClock     纳秒级单调递增时钟（不受系统时钟调整干扰）
```

### 2.3 门面层 (`:facade`)

```text
com.miaiglasses.sdk
├─ GlassesSession       核心调用门面：open / sendWear / capturePhoto / 查询方法 / close
├─ GlassesEventSource   抽象事件源接口（提供 state 属性与订阅能力）
├─ GlassesListener      函数式接口：onEvent(GlassesEvent)
├─ SessionOptions       连接配置：建连超时、重试上限、回调调度器、诊断模式
├─ CallbackDispatcher   回调线程调度策略：MainThread / SingleThread / CallingThread
├─ SendResult           发送执行结果：Queued（已入队）/ Rejected（被拒绝）
└─ SdkErrorCode         统一对外错误码定义
```

---

## 3. 线程模型与调度架构

SDK 内部建立了一套严格分工、无锁化的轻量级线程模型：

```text
  ┌────────────────┐
  │ supervisor 线程 │ 监督周期：门禁检查 → 创建 Socket → 启动 RX → 监听断开 → 退避重连
  └───────┬────────┘ （跨层通信走 pump.call() 同步取回执，不直接读写协议变量）
          │ 投递至连接池，带 Future 超时保护
  ┌───────▼────────┐
  │  RX 读取线程    │ 独占阻塞 read() 循环，只负责提取原始字节推入有界队列，不解析业务
  └───────┬────────┘
          │ 有界字节队列（256 KB 上限，满则丢弃最旧并计数，携带连接世代代号）
  ┌───────▼────────┐
  │  引擎处理线程    │ 单线程驱动：L1 解帧 → SAR 状态推进 → 数据分流 → 定时器驱动
  └───────┬────────┘ 同步执行 socket.write()，消除发送并发竞争
          │ 状态变更与业务事件推入
  ┌───────▼────────┐
  │  回调派发线程    │ 默认 Android 主线程（可通过 CallbackDispatcher 切换至独立线程）
  └────────────────┘
```

### 3.1 线程安全与并发设计原则

1. **单线程状态机（无锁并发）**：所有的协议解包、窗口更新、重传计时与命令编码全部在 `EnginePump` 引擎线程中以单线程顺序执行。从根本上杜绝了多线程竞态条件与锁争用，确保协议时钟与窗口状态严格单调递增。
2. **RX 线程独立读**：蓝牙 `BluetoothSocket.write()` 在对端接收阻塞时可能会产生阻塞。若读写处于同一线程，将造成双向等待导致的永久死锁。因此，RX 线程独立运行阻塞 `read()`，读取的数据通过线程安全有界队列投递给引擎。
3. **世代号（Epoch）隔离**：在蓝牙断线重连发生切换 Socket 时，supervisor 会递增生成新的世代号并清空接收队列。滞留在旧 Socket 中的残留字节投递到引擎后会被直接丢弃，防止污染新会话的握手序列。
4. **非阻塞与超时防护**：
   - `open()` 为非阻塞方法，立即返回 `GlassesSession` 句柄，建连流程异步进行。
   - `close()` 保证立即返回并将状态置为 `Closing`，所有资源释放由后台线程异步收尾，避免宿主在 Android `Activity.onDestroy` 中调用时触发 ANR。

---

## 4. 状态机与连接生命周期

```text
Idle ──open()──▶ Connecting ──Socket建连──▶ Handshaking ──DeviceConfirm──▶ Ready
                    │                            │                           │
                    │ 建连失败                    │ 签名校验失败/超时           │ 链路异常断开
                    ▼                            ▼                           ▼
                  Failed ◀───────────────────────┘                    Reconnecting ──退避完成──▶ Connecting
                                                                           │ close()
任意状态 ───────────────close()──────────────────▶ Closing ──▶ Closed（终态）
```

### 4.1 会话握手时序

进入 `Handshaking` 状态后，SDK 与眼镜端通过四步确认报文完成双向安全校验：

```text
客户端 (Android SDK)                             智能眼镜 (Glasses)
       │                                                 │
       │─────── L1 CMD 协商 (seq=0, ftype=CMD) ─────────▶│
       │◀────── L1 CMD 应答 ─────────────────────────────│
       │                                                 │
       │─────── AppVerify (type=1, id=26, AppRandom) ───▶│
       │◀────── DeviceVerify (type=1, id=26, DeviceSign) ─│
       │                                                 │
  [计算 HMAC-SHA256 并验证设备签名]                       │
       │                                                 │
       │─────── AppConfirm (type=1, id=27, AppSign) ────▶│
       │◀────── DeviceConfirm (type=1, id=27) ───────────│
       │                                                 │
  [握手圆满完成，进入 Ready 状态]                           │
```

- **重连强制重协商**：每次套接字重连，必须重置 L1 序列号并重新发起握手协商，严禁沿用旧会话的加密状态。
- **故障自愈与重试预算**：当系统蓝牙关闭或设备处于未配对状态时，supervisor 暂停连接并进入待命等待，不空耗重试预算；待系统广播通知蓝牙就绪后自动唤醒重连。

---

## 5. 数据流分流与背压保护

### 5.1 通道多路复用

| 业务车道 | 传输通道 | 数据标识 | 处理流程 | 宿主交付形式 |
|---|---|---|---|---|
| **控制信令** | `CH_PB` (1) | `opCode=2` (加密) | `WearCodec` 解码 → `ControlRouter` 路由 | `GlassesEvent.Control` |
| **实时音频** | `CH_MASS_VOICE` (3) | `MassType=1/2/6/7` | `MassAssembler` 帧重组 | `GlassesEvent.Audio`（mSBC 裸流）|
| **拍摄图片** | `CH_MASS_VOICE` (3) | `MassType=3/4` | 首帧与续帧按分片重组 | `GlassesEvent.Image`（JPEG 数据）|
| **媒体缩略图** | `CH_MASS` (2) | 分片传输 | `MassFileAssembler` 拼装 | `GlassesEvent.MassFile`（PNG 缩略图）|
| **语音事件** | `CH_PB` (1) | `type=14` | `VoiceCodec` 结构化解析 | `VoiceEvent.OpenDevice` / `Status` |

> **关键准则**：MASS 多媒体数据必须严格按分片头中的 `MassType` 字段分流，不可按单包字节长度作为推断依据。

### 5.2 队列配额与车道背压

为保障关键控制指令不被大吞吐量的数据帧淹没，SAR 引擎在单一递增序列号流之上构建了多车道配额隔离：

| 车道类别 | 队列配额 | 满载处置策略 | 设计考量 |
|---|---:|---|---|
| **控制信令 (Control)** | 256 帧 | 拒绝入队，抛出 `QUEUE_FULL` | 保证高层状态与控制命令通道通畅 |
| **音频流 (Audio)** | 64 帧 | 拒入新帧，计入丢包统计 | 语音数据具有时效性，宁舍弃旧数据也不拖垮信令 |
| **大图传输 (Image)** | 4 块 | 拒绝入队，返回限流错误码 | 限制内存驻留，防止 OOM |
| **回调分发 (Callback)** | 256 条 | 淘汰最旧的非关键数据，终态保全 | 关键状态变更（如 `Closed`/`Failed`）强制保全，非关键丢弃并记录计数 |

---

## 6. 统一错误模型

SDK 采用结构化错误事件与统一错误码体系，避免通过异常捕获控制业务流程：

```kotlin
public object ErrorCode {
    const val AUTH_FAILED_TOKEN_INVALID = 108   // 设备握手验签失败（Token 错误或无效）
    const val TRANSPORT                 = 201   // 底层套接字读写失败 / 重连次数耗尽
    const val PROTOCOL                  = 202   // 帧魔数损坏、CRC 校验失败、解密异常或报文格式错误
    const val TIMEOUT                   = 203   // 协商、握手、指令确认等待超时
    const val NOT_READY                 = 204   // 会话未处于 Ready 状态时调用发送
    const val CLOSED                    = 205   // 会话已关闭
    const val NO_WAKEUP                 = 206   // 唤醒会话未建立或已结束
    const val QUEUE_FULL                = 207   // 发送队列配额满，当前帧被丢弃
}
```

```kotlin
public class GlassesEvent.Error(
    public val code: Int,
    public val message: String,
    public val cause: Throwable? = null,
) : GlassesEvent
```

---

## 7. 公开 API 门面与示例

### 7.1 核心门面 (`GlassesSession`)

```kotlin
class GlassesSession internal constructor(/* ... */) : GlassesEventSource {
    companion object {
        /**
         * 异步开启连接会话（非阻塞，可在任意线程调用）
         */
        fun open(
            context: Context,
            device: BluetoothDevice,
            token16: ByteArray,
            options: SessionOptions = SessionOptions()
        ): GlassesSession
    }

    override val state: SessionState

    // —— 监听器订阅 ——
    fun setListener(listener: GlassesListener?)
    fun addListener(listener: GlassesListener)
    fun removeListener(listener: GlassesListener)

    // —— 常用业务能力（异步调用或阻塞查询）——
    fun deviceInfo(timeoutMs: Long = 3_000): QueryResult<DeviceInfo>
    fun deviceStatus(timeoutMs: Long = 3_000): QueryResult<DeviceStatus>
    fun wearStatus(timeoutMs: Long = 3_000): QueryResult<WearStatus>
    fun syncTime(): SendResult
    fun capturePhoto(): SendResult
    fun startRecording(kind: RecordKind = RecordKind.NORMAL, timeoutMs: Long = 3_000): QueryResult<RecordResponse>
    fun stopRecording(kind: RecordKind = RecordKind.NORMAL, timeoutMs: Long = 3_000): QueryResult<RecordResponse>

    // —— 逃生口：通用低阶指令下发 ——
    fun sendWear(type: Int, id: Int, payload: ByteArray): SendResult

    // —— 生命周期退出 ——
    fun close()         // 异步立即返回
    fun closeAndWait()  // 同步等待后台资源全部释放
}
```

### 7.2 Kotlin 协程与 Flow 支持 (`:ktx`)

引入可选模块 `com.miaiglasses:sdk-ktx` 后，可通过协程惯用语法消费事件：

```kotlin
// 监听完整事件流
session.events().collect { event ->
    when (event) {
        is GlassesEvent.WearChanged -> println("佩戴状态: ${event.status}")
        is GlassesEvent.Image -> handlePreviewImage(event.jpeg)
        is GlassesEvent.Error -> println("错误 [${event.code}]: ${event.message}")
        else -> Unit
    }
}

// 订阅音频裸流
session.audio().collect { pcmOrMsbcChunk ->
    audioPlayer.write(pcmOrMsbcChunk)
}

// 挂起等待会话 Ready
session.awaitReady(timeoutMs = 15_000)
```

---

## 8. 测试与质量保证

1. **黄金向量回归（Golden Vectors）**：
   - 使用固化的黄金测试向量（`tests/golden/vectors.txt`）覆盖 L1 帧解包、CRC 验证与 HMAC 签名算法，确保纯 JVM 实现与硬件协议完全咬合。
2. **纯 JVM 仿真单测**：
   - 包含 300 余项单元测试（导出树实测 349 个 `@Test`），通过 `FakeClock` 仿真时间流逝与超时重传，无须连硬件即可验证完整的滑动窗口拥塞控制。
3. **隔离性编译验证**：
   - 持续集成阶段配置针对 `:sample` 应用的隔离性检查，强制验证接入方无法直接引用 `:core` 内部实现包，确保 API 契约不漂移。
