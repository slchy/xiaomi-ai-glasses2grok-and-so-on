# 云端语音后端架构设计与实现

> 本文描述 `cloud/`（手机 ↔ 大模型的自建后端）的分层、类职责、并发模型、状态机、
> 数据流、错误模型与扩展点。接线细节与部署见 [README.md](README.md)；
> 本文只讲**实现怎么摆**。
>
> **对账基准**：`cloud/src` 当前代码 + `node --test tests/*.test.ts` 全绿。
> 文档与代码冲突时以代码为准。

---

## 1. 分层与模块

```text
┌─────────────────────────────────────────────────────────────────────┐
│ main.ts          装配层：CLI / 环境变量 → provider → runTransport    │
├─────────────────────────────────────────────────────────────────────┤
│ aliyun-session   一条连接的**编排**：把下面各层接起来，自身不含判定   │
├──────────────┬──────────────┬───────────────┬───────────────────────┤
│ 传输与协议    │ 上行处理      │ 决策（纯逻辑） │ 执行                   │
│ transport    │ uplink-…     │ session-core  │ turn-runner           │
│ frame        │ energy-gate  │ dialog-fsm    │ agent / session(dsh)  │
│              │ assembler    │               │                       │
├──────────────┴──────────────┴───────────────┴───────────────────────┤
│ 旁路：archive · announce-store · photo-inbox · tool-control          │
├─────────────────────────────────────────────────────────────────────┤
│ 可替换实现：aliyun-asr（STT）· mimo（ASR/TTS）· dsh（Agent）          │
│             smart-turn（判停侧车）· silero-vad（可选 VAD）            │
└─────────────────────────────────────────────────────────────────────┘
```

### 1.1 依赖与隔离

```text
main.ts ──▶ aliyun-session ──┬──▶ transport ──▶ frame
                             ├──▶ uplink-pipeline ──┬──▶ energy-gate
                             │                      └──▶ utterance-assembler ──▶ smart-turn
                             ├──▶ session-core ◀──▶ dialog-fsm
                             ├──▶ turn-runner ──▶ providers ◀── aliyun-asr / mimo / agent
                             ├──▶ photo-inbox
                             ├──▶ archive / announce-store
                             └──▶ tool-control
```

四条硬边界，都是"能单测"换来的：

| 边界 | 规则 | 为什么 |
|---|---|---|
| `providers.ts` | **只放接口**，不 import 任何具体实现 | 换 STT / TTS / Agent 不改编排代码 |
| `session-core.ts` · `dialog-fsm.ts` · `emotion.ts` · `hq-intent.ts` · `wake-word.ts` · `backchannel.ts` | 纯函数 / 纯状态机，**不碰 socket、定时器、子进程** | 最容易出错的判断（插话算不算数、这句要不要成轮）用单测钉住，不必起连接 |
| `transport.ts` · `frame.ts` | 不认识任何业务语义 | 改心跳不该动对话代码 |
| `pcm.ts` · `util.ts` | 无依赖的纯函数 | 谁都能引，不产生环 |

`aliyun-session.ts` 是唯一"什么都摸"的地方：它持有连接、定时器、子进程和工具口，
但**不做判断** —— 判断都在决策层，它只把 `CoreEffect` 翻译成动作。

---

## 2. 模块与类职责

### 2.1 传输与协议

| 文件 | 职责 | 关键导出 |
|---|---|---|
| `frame.ts` | 分帧 `[type:u8][len:u32 LE][payload]`，头 5 B，单帧上限 32 MB；只做编解码，I/O 由调用方给 | `FrameDecoder` / `FrameWriter` / `UpstreamFrameType` / `DownstreamFrameType` |
| `transport.ts` | accept、首帧 `Hello` 鉴权、心跳应答、**空闲看门狗（默认 45 s）**、下行三件套 | `runTransport` / `Connection` / `DownlinkSink` / `parseHello` |

`DownlinkSink` 只有三个方法（`sendAudio` / `sendControl` / `close`），所以
`turn-runner`、`tool-control` 这些上层模块不需要知道对端是 socket 还是别的传输。

### 2.2 上行处理

| 文件 | 职责 | 关键点 |
|---|---|---|
| `uplink-pipeline.ts` | mSBC（子进程 `ffmpeg` 解码）→ 放大 → 能量门 → 有声段交 STT；另有**原始 PCM 旁路**与**电平表** | `onRawPcm` 给的是**放大前**的字节（档案要能重算增益） |
| `energy-gate.ts` | 「这块音频要不要推给 STT」的状态机 + 预卷缓冲 | 没声音就**不建 STT 连接**：ASR 网关对"没听到有效语音"的连接 10 s 就判空闲超时 |
| `utterance-assembler.ts` | 句子 → 一轮话语：按 Smart Turn 概率或固定窗口判停 | ASR 的句号是**句子**边界，不是**轮次**边界；问号结尾不等于说完了 |
| `smart-turn.ts` | 常驻 Python 侧车的客户端：`[u32 len][int16 PCM]` 问、`[f64 概率]` 答 | 进程常驻、模型只加载一次；不可用时退回固定窗口 |
| `vad.ts` | silero-vad v5 封装（可选判停后端） | 输入是 `[1,576]`（512 + 64 点上下文）、值域 `[-1,1]`，阈值 0.4 |
| `wake-word.ts` · `backchannel.ts` · `hq-intent.ts` | 三条**领域规则**：唤醒词 / 纯应答噪声 / 要看细节的措辞 | 都是"已知的机械事实"，交给模型判断反而更贵更不准 |

### 2.3 识别与合成（可替换实现）

| 文件 | 职责 | 约束 |
|---|---|---|
| `aliyun-asr.ts` | 阿里云 NLS 实时识别客户端（WebSocket） | 消息 id / task id 必须是 32 位纯十六进制；连接由能量门按需拉起 |
| `mimo.ts` | MiMo 的 ASR 与 TTS（SSE 流式） | 上行 16 kHz、下行 **24 kHz**；TTS 是"聊天式"：风格进 `user`、正文进 `assistant` |
| `agent.ts` | `VoiceAgent`：人设提示词 → 模型 → 剥情绪标签 → 清洗成可朗读文本 → 播报出口 | 清洗是必须的：TTS 会把 markdown 原样念出来。`cleanForSpeech` 还负责两类**看不见**的脏东西：零宽字符及其 HTML 实体（`&#8203;` 字面送进去会被念成符号）、以及两个中日韩字符之间的空格（那不是停顿标记，只会让合成器塞一个假停顿；英文 / 数字 / 代码段两侧的空格有语义，不动） |
| `session.ts` | `DshSession`：把文本交给 agent 进程（NDJSON），续会话、取 usage、接住**自发轮次** | 单轮等待上限 180 s；超出必须抛错**并说出来**，不许静默 |

### 2.4 决策层（纯逻辑）

| 文件 | 职责 | 关键导出 |
|---|---|---|
| `dialog-fsm.ts` | 一轮对话的相位机（见），用**票据**（轮次号 + 代次）判"这一轮还算不算" | `DialogFsm` / `TurnTicket` / `DialogState` |
| `session-core.ts` | 事件 → 效果：收听 / 打断 / 识图 / 自助结束 / 挂断都在这里判 | `SessionCore` / `CoreEvent` / `CoreEffect` |
| `emotion.ts` | 情绪标签 → 规范化 key → TTS 风格串 | `EMOTION_LABELS` 是单一事实来源 |
| `providers.ts` | `SttProvider` / `TtsProvider` / `AgentProvider` 三个契约 + 测试替身 | `cannedStt` / `silentTts` / `echoAgent` |

`SessionCore` 的 16 种输入事件产出 9 种效果，效果由编排层执行：

```text
效果含义
assemble        把文本交给一轮（含旁听反馈 / 唤醒词过滤之后）
turn            开跑一轮（携带票据）
awaitShot       进"等快门"相位并播一句旁白
cancel_shot     取消等快门
stop            立刻停播（打断）并让本轮作废
stop_capture    下发"停采集"（Voice id=5 六字段全 0）
end             说完了就挂断
drop            丢弃这句话（纯噪声）
```

### 2.5 执行层

| 文件 | 职责 | 关键点 |
|---|---|---|
| `turn-runner.ts` | 跑完一轮：agent → TTS → 下行 → **等播放结束**（按 PCM 时长 + 余量估算） | 旁白与本轮回答共用一条下行，靠 `speechChain` 串行；被打断就停止下发 |
| `photo-inbox.ts` | 「请设备拍一张」变成可等待 / 可超时 / 可取消的请求（见） | 两种使用者：工具口等人取图；设备自己按快门时没人等就转成一轮识图 |
| `spontaneous.ts` | 判定**没人催的轮次**（后台作业结算后自己开的一轮） | 有等待者的轮次不算；只认 `running → idle` 且没有 prompt 在飞 |

### 2.6 旁路与运维

| 文件 | 职责 | 语义 |
|---|---|---|
| `archive.ts` | 一条连接一个目录：`meta.json` / `events.jsonl` / `uplink.sbc` / `uplink.pcm` / `downlink.pcm` | 事件带三路字节偏移；`writeSync` 落盘，进程被中断也不丢已收字节 |
| `announce-store.ts` | 离线待补播队列（JSONL，上限 20） | **至少一次**：`drain()` 即取走，崩溃在播放中途会重播一句（重复比丢好） |
| `tool-control.ts` | 本机 HTTP 控制口（`127.0.0.1`），给 MCP 工具进程回调 | 端口固定；`close()` 必须 `closeAllConnections()`，否则旧连接会继续占端口并把请求路由到已关闭的会话 |
| `calibrate.ts` · `legacy-pipeline.ts` | 阈值标定脚本、旧的回放链路 | 调试用，不在生产路径上 |
| `latency-report.ts` | **纯函数**：`events.jsonl` → 逐轮「用户说完 → 听到第一个字」分解（①判停+ASR / ②排队 / ③agent+TTS / ④播放） | 分段口径写在文件头；`null` 表示缺仪表而**不是 0**，且逐轮记下缺哪个时刻（`missing`）——缺 `tts.first` 多半是旧档案，缺 `gate.close` 多半是主动播报 |

延迟分解依赖两个时间线事件：`turn-runner` 在拿到首块音频时写 `tts.first`，一轮收尾时写
`turn.end`（失败也写，带 `failed: true`）。它们经 `TurnRunnerOptions.onEvent` 注入，
`aliyun-session` 把它接到 `archive.event()` —— 传输层与 `TurnRunner` 都不认识档案。

跑法：

```bash
cd cloud
node scripts/latency-report.ts <会话目录>
node scripts/latency-report.ts <档案根目录> --all
```

---

## 3. 并发与生命周期

```text
Node 事件循环（单线程）
 ├─ 每条连接
 │    ├─ transport.Connection      帧解码/编码 + 空闲看门狗
 │    └─ AliyunConnection          编排对象，随连接生灭（含控制口、档案、回合票据）
 ├─ 子进程
 │    ├─ ffmpeg            每连接一个：mSBC → PCM16 16 kHz
 │    ├─ smart-turn(.py)   进程级常驻：判停概率
 │    └─ dsh               会话级常驻：NDJSON over stdin/stdout
 └─ 本机 HTTP：控制口 127.0.0.1:<port>（MCP 工具进程回调）
```

1. **单线程判定**：所有业务判断在事件循环里同步执行，没有锁，也没有跨线程共享状态；
   异步只出现在 I/O 边界（`await` TTS 流、`await` 一轮结束、`await` 子进程输出）。
2. **每连接一套对象**：换连接 = 换 `Connection` / `AliyunConnection` / 档案目录 / 回合票据，
   上一连接的尾巴不会混进下一连接（控制口是唯一的全局资源，见下）。
3. **一条下行**：`TurnRunner.speechChain` 把"旁白"和"本轮回答"排队，避免两段 PCM 交织。
4. **故意同步的写**：`archive.ts` 用 `writeSync`。进程可能在任意时刻被 Ctrl-C 或
   被 OOM 杀掉，异步写会丢掉最后几秒 —— 而正是那几秒最需要。
5. **控制口端口固定**：同一进程内**同时只服务一条连接的"工具面"**。第二条连接音频照常，
   只是它的控制口绑不上（日志会打 `✗ 控制口 <port>`），`POST /say`、MCP 拍照这些会失效。
   这对"一家人一台眼镜"的使用方式够用，但不支持多租户。
6. **子进程失败不重试**：`ffmpeg` 死了只影响这条连接的上行；实例在下一条连接重建。
   `smart-turn` 不可用时判停退回固定窗口；`dsh` 失败则这一轮报错（见）。

---

## 4. 状态机

### 4.1 对话相位（`DialogFsm`）

```text
                 ┌──────────────▶ listening ◀──────────────┐
                 │                    │                    │
        一轮结束 / 被打断放弃            │ utterance           │ 等快门超时 / 拍完
                 │                    ▼                    │
                 │                thinking ──▶ speaking ────┤
                 │                    │             │      │
                 │          要看细节   │             │ 说完了│
                 │                    ▼             ▼      │
                 └──────────────── awaiting_shot ──▶ ending ┘
                                      │
                                 close() ──▶ closed（终态）
```

- 相位只有 6 个：`listening / awaiting_shot / thinking / speaking / ending / closed`。
- 每轮发一张票据 `TurnTicket{no, gen}`：`no` 判"还是不是这一轮"，`gen` 判"是否已被插话作废"。
  打断只是**作废票据**，不靠布尔量四处透传 —— 这是把「五个互相牵制的布尔量」换成一个状态机的直接原因。
- `awaiting_shot` 是高清照独有的相位：这期间既不算"在听"也不算"在说"，
  所以插话、超时、取消各有明确落点。

### 4.2 上行闸门（`EnergyGate`）

```text
closed ──峰值越门──▶ open（推流 + 预卷补发 + hold 计时）
  ▲                    │
  └──hold 到期且静音────┘
```

三种事实（开着 / 该关 / 有预卷）必须同时正确，所以做成状态机而不是三个布尔量：
漏同步一次的表现是"门开着但没喂音频"，不崩，只是 ASR 一直不出字。

门限作用在**放大后**的波形上（`--gain` 与门限是同一个坐标系）。这条曾经踩过坑：
早期按放大前的电平算门限，增益开得越大、门限被同步抬高，小声说话照样推不开。

### 4.3 拍照（`PhotoInbox`）

```text
                request('spp')                      deliver(image) / 超时
   idle ─────────────────────▶ WAITING ◀──────────────────────▶ settle
                                  ▲
        request('hq') ──发 take_photo_hq──▶ ARMING ──hq_armed──┘
                                            │
                                    15 s 没就绪 / hq_failed
                                            ▼
                                         fail(no_arm | reason)
```

两段超时的由来：高清照多了「人」这一环 —— 程序化快门**不写相册**，只能请用户按一下。
于是「等手机建好 P2P 并打开相册」和「等用户按下快门」各有各的失败语义
（`no_arm` vs `no_shutter`），手机自己先放弃并上报 `hq_failed` 时云端**立刻返回**，
不必干等到自己的总超时。

---

## 5. 数据流

### 5.1 线协议

```text
[type:u8][length:u32 LE][payload]
```

| 上行（手机 → 云端） | 值 | payload |
|---|---|---|
| Audio | 1 | mSBC 原始字节（不做二次编码） |
| Image | 2 | JPEG |
| Wake | 3 | 唤醒事件的 ID（诊断用） |
| Ping | 4 | 空；服务端回 Pong |
| Status | 5 | UTF-8 JSON 状态事件（`hq_armed` / `hq_image` / `hq_failed`） |
| Hello | 6 | 首帧身份：`{"proto":1,"deviceId":…,"token":…}` |

| 下行（云端 → 手机） | 值 | payload |
|---|---|---|
| Audio | 1 | PCM16 24 kHz 单声道 |
| Control | 2 | UTF-8 控制串：`take_photo` / `take_photo_hq` / `hq_cancel` / `stop` / `stop_capture` / `auth_*` |
| Pong | 5 | 空 |

- **首帧必须是 `Hello`**：鉴权不通过就发一条 `auth_required` / `auth_failed` 控制串再断开。
- 空闲看门狗 45 s：这么久没收到**任何字节**就断开（手机侧有心跳，正常不会触发）。
- 单帧上限 32 MB，超了直接断：高清图约 1 MB，留两个数量级余量。

### 5.2 一次完整对话

```text
手机云端
 │  Audio ──────────────▶│ frame ─▶ ffmpeg ─▶ 放大 ─▶ 能量门
 │                       │                                  │ 越门
 │                       │                                  ▼
 │                       │            utterance-assembler（Smart Turn 判停）
 │                       │                                  │ 成轮
 │                       │                                  ▼
 │                       │        SessionCore → turn(票据) → TurnRunner
 │                       │                                  │
 │                       │        agent（dsh 会话，常驻）◀──┘
 │                       │            │ 回复文本 + 情绪标签
 │                       │            ▼
 │                       │        清洗 → TTS（流式）
 │  ◀──────── Audio ─────│        逐块下发，边收边放
 │                       │            │
 │  （播完）              │        等播放结束 → 释放票据 → listening
```

实测（一次真实问答，`--stt aliyun --tts mimo --agent dsh`）：

```text
agent    3.28 s   续会话  tokens=205/52  cache=23168
TTS首块  1.04 s
回放     230400 字节  4.80 s
```

首块延迟是端到端体感的关键：TTS 边收边下发，所以"开始出声"只等首块，
不用等整段合成完。

### 5.3 打断与旁路

| 场景 | 路径 |
|---|---|
| 用户插话 | `SessionCore` 判有效 → `stop` 效果 → 下发 `stop` + 本端 `player.flush()` + 票据 `gen++` 作废 |
| 系统旁白（"请按一下快门"） | `TurnRunner.speakCanned` —— **不属于任何一轮**，不改状态机，只借同一条下行 |
| 后台任务完成 | agent 自发轮次 → `SpontaneousTurn` 判定 → 有连接就播，没连接进 `announce-store` |
| 离线补播 | 下一条连接 `start()` 时 `drain()`，播完即取走 |
| 原始档案 | 上行原始字节 / 放大前 PCM / 下行 PCM / 事件时间线同时写入，事后可完整重放 |

---

## 6. 错误模型

| 级别 | 例子 | 处理 |
|---|---|---|
| 连接级 | 鉴权失败、空闲 45 s、单帧超 32 MB | 发控制串后断开；不影响其它连接 |
| 回合级 | agent 一轮超过 180 s、清洗后可朗读内容为空 | 抛错并由编排层**说出来**（"我这轮没跑完"），不许静默 |
| 能力级 | 拍照失败（`PhotoFailReason` 12 个值）、STT 无结果 | 变成一句人话或一次明确的工具错误码 |
| 实现级 | TTS HTTP 非 2xx、SSE 断流、`ffmpeg` 退出 | 记日志；该段能力本轮失效，连接继续 |

三条硬规则：

1. **超时要说出来**。早期实现里超时只打一行日志，用户听到的是彻底安静 ——
   和"没听见"无法区分。现在超时必须产出一句话。
2. **状态帧坏掉不能断连接**。`Status` 帧 JSON 解析失败只记日志（否则一次脏字节会踢掉整条会话）。
3. **重试只在一处**。连接重试由手机侧负责；云端不做重连，一条连接就是一条连接。

---

## 7. 对外三个面

### 7.1 命令行与环境变量

```bash
node src/main.ts --listen 8766 --stt aliyun --tts mimo --agent dsh
node src/main.ts --replay ../tmp/voice.sbc          # 离线回放一段 mSBC
```

| 参数 | 作用 |
|---|---|
| `--listen` / `--host` / `--serve` | 监听端口 / 绑定地址（默认 `127.0.0.1`）/ 常驻模式 |
| `--stt` | `aliyun` / `canned`（回放固定一句）/ `none` |
| `--tts` | `mimo` / `silent`（只算时长，不出声） |
| `--agent` | `dsh` / `echo` / `none`；`--dsh-sdk` 走 SDK 会话而不是 headless |
| `--replay` | 离线回放，不监听端口 |
| `--ffmpeg` / `--gap-ms` · `--sentence-silence` | 解码器路径 / 解帧间隔 / 判停静音阈值 |
| `--work-dir` · `--ask-image` | agent 工作目录 · 单图提问 |

调优类几乎都有对应环境变量（`MIAI_*`：增益、电平表、档案、补播、TTS 音色与风格、
两段超时、空闲上限、鉴权 token 表）。默认值只有一处，集中在 `main.ts` 与各模块导出的常量。

### 7.2 控制口（HTTP，仅本机）

| 路由 | 用途 |
|---|---|
| `POST /say` | 把一段文本当成用户输入（没有麦克风也能验证整条链） |
| `POST /control` | 原样下发一条控制串（调试用） |
| `POST /take_photo` / `POST /take_photo_hq` | 请求拍照并等图（低清 / 协作式高清） |
| `POST /end_session` | 说完这句就挂断 |

并发保护：同一时刻只允许一个拍照请求在途（第二个返回 409），请求带自己的超时。

### 7.3 MCP 工具（给 agent 用）

`mcp/` 下两个桥接进程把上面两个面包装成 agent 工具：`take_photo` / `take_photo_hq`
（相机）与 `end_session`（会话）。它们是 dsh spawn 的独立进程，只能通过控制口的 URL
与 harness 通信 —— 这也是控制口必须存在的原因。

---

## 8. 测试与持续集成

| 层 | 手段 |
|---|---|
| 纯逻辑（决策、判停、情绪、补播队列、档案） | `node --test tests/*.test.ts`，不起连接、不需要凭据 |
| 端到端（无真机） | `tools/fake-phone.ts`：假手机，可发 mSBC、收下行、按剧本触发高清照三条分支 |
| 类型安全 | `tsc --noEmit` + **strip-only 语法检查**：Node 的类型擦除不支持参数属性这类写法，`tsc` 查不出，必须静态挑一遍 |
| 启动自检 | 无参数跑 `node src/main.ts` 应当打印用法并以 2 退出 —— 用它当"整张依赖图能加载"的冒烟 |
| 跨端常量 | 控制串 / 状态帧事件 / 失败原因 / 帧类型 / 下行采样率都与手机侧做**双方源码比对**，改一边忘另一边会红 |
| 导出 | 导出树再跑一次构建与加载，且敏感值扫描必须零命中 |

---

## 9. 明确没做的

1. **多租户**：控制口端口固定，同时只服务一条连接的工具面；没有 token 之外的租户隔离。
2. **云端重连**：断开就是断开，重连是手机侧的事。
3. **会话状态持久化**：只有 agent 自己的 session id 与待补播队列落盘，对话相位不落盘 ——
   进程重启后是"一条新连接"，不是"接着上次"。
4. **鉴权热更新**：token 表在启动时读一次，改动需要重启。
5. **上行只有 mSBC**：不做重采样 / 转码，手机必须原样转发设备的字节。
6. **判停依赖侧车**：想用 Smart Turn 就得有 Python 与模型文件；不想装就用固定窗口，
   代价是判停更钝。
7. **不支持多路并发下行**：一条连接一条音频流；两台手机同时连会互相抢控制口（见）。
8. **没有端到端加密之外的私有协议**：就是 TLS/TCP + 明文帧，安全边界完全靠 TLS 与 token。
