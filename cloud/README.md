# cloud — 眼镜 ⇄ 云端的语音通道

一部手机一条 TCP 连接，上行 mSBC 语音、下行 PCM 语音 + 控制串。STT / TTS / Agent
三件都是**可替换件**，换参考实现不用改这里任何代码。

> 状态：可独立运行。分层、状态机与扩展点见
> [CLOUD-ARCHITECTURE](../docs/CLOUD-ARCHITECTURE.md)。

## 跑起来

```bash
cd cloud
npm install

# 1) 什么都不接，纯离线自测（不需要任何密钥，不落盘）
node src/main.ts --listen 8766 --stt canned --tts silent --agent echo

# 2) 另一个终端：最小客户端，发一段 mSBC
node tools/fake-phone.ts --port 8766 --sbc ../tmp/voice.sbc
#    ← Audio ×1  Control=[]  Pong=true

# 3) 生产
export ALIYUN_APPKEY=...            # 阿里云 NLS 实时识别（另有 ALIYUN_AK_ID / ALIYUN_AK_SECRET）
export MIMO_API_KEY=...             # MiMo TTS
node src/main.ts --listen 8766      # 默认 --stt aliyun --tts mimo --agent dsh
```

手机侧先 `adb reverse tcp:8766 tcp:8766`（只监听 `127.0.0.1` 时日志会提示这一行）。

## 三个可替换件

| 开关 | 取值 | 说明 |
|---|---|---|
| `--stt` | `aliyun` `noop` `canned` `<模块路径>` | 上行识别，每次连接开一个会话 |
| `--tts` | `mimo` `silent` `<模块路径>` | 下行合成，采样率由实现自己声明 |
| `--agent` | `dsh` `echo` `none` `<模块路径>` | 裸 `--agent` 等价 `dsh` |

`<模块路径>` 指向一个导出工厂的文件，例如：

### MiMo 的音色与**风格提示词**

MiMo 的 TTS 是聊天式的：**风格提示词进 `user` 消息，要念的文本进 `assistant`**，
模型按"这个助手该用什么语气说这句话"合成 —— 所以语调一半由提示词决定。

```bash
MIMO_TTS_VOICE=Chloe                    # 音色
MIMO_TTS_STYLE='用自然的中文口语说话，像朋友聊天：语气温暖、有起伏，句末自然落下。'
```

同一句话、两种风格的实测（`ab-tts.mjs`，24 kHz PCM）：

| 风格 | 时长 | 峰值 | 基频 σ |
|---|---|---|---|
| `Calm, natural, conversational.`（旧默认） | 3.20 s | −0.6 dBFS | 52.6 Hz |
| 中文口语（新默认） | 3.84 s | −2.3 dBFS | 60.9 Hz |

提示词确实在改变输出：语速慢了 20%、基频起伏大了 16%、峰值更低（给后续增益留了余量）。
"听起来好不好"只能靠耳朵，但**它有可调项**这件事本身是确定的。


```ts
// my-stt.ts
import type { SttProvider } from '../src/providers.ts';
export function stt(): SttProvider {
  return { name: 'my-stt', open: (h) => ({ feed: h.onSentence, hangup() {}, close() {} }) };
}
```

```bash
node src/main.ts --stt ./my-stt.ts --agent ./my-agent.ts
```

契约在 `src/providers.ts`（三个接口 + 四个调试实现，是抄写范例）。

## 数据流

```
TCP ──transport──┬─ Audio ──▶ UplinkPipeline（ffmpeg→PCM→能量门）──▶ SttSession
                 ├─ Image ──▶ PhotoInbox ──┬─ 有人在等：交图
                 │                         └─ 没人等：当成一轮识图
                 └─ Wake  ──▶ 诊断日志

 SttSession.onSentence ──▶ SessionCore ──effects──▶ UtteranceAssembler（判停成句）
                              ▲                          │
                              │                    定稿一句话
                    工具控制口 / 拍照                    ▼
                                         SessionCore ──▶ TurnRunner ──▶ TtsProvider
                                                              │
                                                              └─▶ connection.sendAudio
```

| 文件 | 只做一件事 |
|---|---|
| `src/transport.ts` | accept、帧编解码、Hello 鉴权、Ping/Pong、空闲看门狗、TLS |
| `src/providers.ts` | STT / TTS / Agent 的接口与内置实现 |
| `src/uplink-pipeline.ts` | mSBC → PCM（ffmpeg）→ 能量门 → 有声段 |
| `src/session-core.ts` | 对话决策：唤醒词/旁听反馈/插话/识图轮 → 产出动作（纯，可单测） |
| `src/photo-inbox.ts` | 拍照请求的等待、超时与落盘 |
| `src/aliyun-session.ts` | 一条连接的接线（不含判定逻辑） |
| `src/turn-runner.ts` | 一轮对话：agent 流式出字 → TTS 流式出音 → 下发 |
| `src/dialog-fsm.ts` | 对话状态机（listening/thinking/speaking/ending + 插话作废） |
| `src/main.ts` | 只做装配与 CLI |

改这些地方的判定不用碰网络：`npm test` 跑 `tests/session-core.test.ts`（纯事件 → 断言动作）。

## 调试口（没有麦克风也能联调）

连接建立后，云端在 `--listen + 2` 端口开一个本机控制口（`PHOTO_CTL_PORT` 可覆盖）：

| 路由 | 作用 |
|---|---|
| `POST /say`（body = 一句话） | 当作「用户说了这句」，走正常轮次 → agent → TTS → 下发 |
| `POST /take_photo` | 让设备拍一张，返回 JPEG |
| `POST /end_session` | 按「说完这句就挂断」处理 |

```bash
curl -X POST --data '你好，介绍一下你自己' http://127.0.0.1:8770/say
```

这条口子只绑本机，是给联调与客户端作者用的；生产部署请别把它暴露出去。

## 接公网

| 环境变量 | 作用 |
|---|---|
| `MIAI_TLS_KEY` / `MIAI_TLS_CERT` | 给了就走 TLS（另一条路径是明文 TCP，同一份代码） |
| `MIAI_TOKENS` | 指向 `token` 或 `token:deviceId` 一行的文件；配了就要求首帧 `Hello` |
| `--host` | 缺省 `127.0.0.1`，上公网由部署方显式给 `0.0.0.0` |
| `DSH_BIN` | agent 可执行文件；Windows 上指向 npm 的 `dsh.cmd` 即可（内部用 `cmd /c` 启动 shim） |

自签证书实测：

```bash
openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 1 \
  -subj '/CN=miai-local' -addext 'subjectAltName=IP:127.0.0.1'
MIAI_TLS_KEY=key.pem MIAI_TLS_CERT=cert.pem node src/main.ts --listen 8768
node tools/fake-phone.ts --port 8768 --sbc ../tmp/voice.sbc --tls --ca cert.pem
```

客户端把 CA 打进包再严格校验，就等价于证书 pinning —— 不需要自研应用层加密。

## 后台任务与完成播报

长任务**不需要我们自己在外面造任务队列** —— dsh 自带：

```text
模型调 bash {run_in_background: true}
  → job/registered，工具立刻返回        （实测 2.8 s）
  → 模型回一句「我让后台去办了」→ turn 结束（实测 3.6 s，主对话就自由了）
  → 若干秒后 job/settled
  → dsh **自己开一轮**把结果说出来        （没有 session/prompt）
```

我们的 SDK 封装以前会把这一轮**静默丢掉**（只在 prompt 期间挂 `onIdle`，自发轮次没有
等待者）。现在 `session.ts` 用 `spontaneous.ts` 识别它，交给 `VoiceAgent.onAnnounce`
清洗成可朗读文本，再由 `AliyunSession` 走旁白通道播出来。

实测（2026-11-14，`POST /say`）：

```text
03:46:50  agent   "已经派到后台了，我这就回来，不等它跑完。"   2.12 s
03:46:58  状态    speaking → listening
03:47:19  [sdk]   后台任务结算 → agent 自发开了一轮（不是我们催的）
03:47:21  后台任务完成 → 播报 "后台那条已经跑完了，输出 bg30-done，一切正常。"
03:47:23  旁白    ... 5.12 s
```

三个前提：

- **提示词要点明**：不提醒的话模型会自己调 `job_output{wait:true}` 干等（实测等满 20 s）。
  `agent.ts` 的 `VOICE_SYSTEM` 里已经写明「派出去、立刻回、不要等」。
- **播报不占轮次**：走 `speakCanned` 的旁白通道，用户正在说话/听播报时排队，回到
  `listening` 再放 —— 抢话比晚两秒更烦人。
- **断线不丢**：任务完成时如果没连接，那句话会落到 `~/.voice-agent/announce.jsonl`
  （`MIAI_ANNOUNCE_FILE` 可改，上限 `MIAI_ANNOUNCE_MAX` 默认 20 条），
  下次连上第一条就补播。语义是**至少一次**：崩溃在播放中途会重复播一句，比丢一句好。

离线补播实测（2026-11-14，用假手机模拟"派完就断、重连再听"）：

```text
03:53:03  agent   "派到后台了，我这就回来。"            4.38 s，turn 结束
03:53:22  [sdk]   后台任务结算 → agent 自发开了一轮
03:53:25  后台没有连接，存入待补播（积压 1 条）        ← 连接 A 已断
03:53:36  补播离线期间攒了 1 条后台汇报               ← 连接 B 连上
03:53:37  旁白    "二十秒那条也跑完了，输出 bg20-done。" 3.20 s
```

## 事后排障：原始档案

出问题之后不能靠猜。`MIAI_ARCHIVE_DIR` 一开，**每条连接**都会留一份完整档案：

```text
<root>/20261114-031530-203.0.113.20_2421/
  meta.json      当时的增益、门限、采样率、provider、模型（档案必须自解释）
  events.jsonl   时间线，每条都带三条流当时的字节偏移
  uplink.sbc     手机发来的原始 mSBC，未解码（可换增益重新解码）
  uplink.pcm     解码后 PCM16 16 kHz，**放大前**（可任意重算增益/门限）
  downlink.pcm   下行 TTS PCM16 24 kHz
```

```bash
MIAI_ARCHIVE_DIR=/var/lib/miai/archive   # 空 = 关（默认）
MIAI_ARCHIVE_MAX_MB=80                   # 单段上限，到顶另起 `…--2`
MIAI_ARCHIVE_KEEP=20                     # 保留多少个会话目录
```

看一份档案：

```bash
python scripts/analyze_session.py ~/miai-archive/20261114-031530-203.0.113.20_2421
```

它给三样东西：事件时间线（门开关 / ASR 中间结果 / 成句 / 下发控制 / 唤醒）、
**放大前**的上行电平曲线、以及自动诊断（"开了门但一句话都没定稿"这类结论）。

为什么一定要留"放大前"的 PCM：像「小声说话识别不出来」这种问题，事后必须能在
不同增益/门限下重放判断，而不是拿已经放大的波形去猜原始电平。

旧的 `DUMP_DIR`（`up/down_<turn>.pcm`）已被它取代 —— 那套只覆盖**播放期**，恰好
漏掉"用户说话但没成句"，而且同名文件会被下一条连接覆盖。

## 协议（最小实现见 `tools/fake-phone.ts`）

```
[type:u8][len:u32 LE][payload]

上行  Audio=1 Image=2 Wake=3 Ping=4 Hello=6
下行  Audio=1 Control=2 Pong=5
```

`Hello` 是 JSON（`{proto, deviceId, token}`）；没配鉴权时客户端可以不发。

## 部署

现网跑在一台公网 VPS（`user@203.0.113.10`），systemd 单元 `miai-harness`：

```bash
python scripts/deploy_cloud.py        # 同步 src/tools/tests → 跑单测 → 重启
```

| 位置 | 内容 | 谁维护 |
|---|---|---|
| `~/miai-cloud/src` | 源码（Node 直接跑 `.ts`，无需构建） | `deploy_cloud.py` 推送 |
| `~/miai-cloud/.env` | 阿里云 / MiMo / dsh 的真实凭据 | **只在服务器上**，不从本地覆盖 |
| `~/miai-cloud/tls` | 证书 + 私钥 | `scripts/gen_local_ca.py` 后手工 scp |
| `systemd miai-harness` | `--listen 63993 --host 0.0.0.0 --stt aliyun --tts mimo --agent dsh --dsh-sdk` | 一次性写好 |

`.env` 刻意不进同步范围：本地那份是调试用的，推上去会盖掉服务器上的真实凭据。

### 为什么是 63993，不是 8765

`8765` 从公网连不通：TCP 能建立，TLS 一发数据就被断开，服务器上 `tcpdump`
抓不到任何到达的包 —— 卡在云参考实现的安全组，不在主机（`iptables` 全 ACCEPT、
`ufw` inactive）。实测 63993 可直达，于是改用它。

| 项 | 值 |
|---|---|
| 监听 | `0.0.0.0:63993`（TLS） |
| 手机填的地址 | `203.0.113.10:63993`，勾 TLS，token 与 `tokens.txt` 一致 |
| 控制口 | `63995`（= listen + 2，只绑 127.0.0.1） |
| 鉴权 | `MIAI_TOKENS=<deploy-dir>/tokens.txt`，每行 `token` 或 `token:deviceId` |

**公网可及就必须要鉴权**：没有 `MIAI_TOKENS` 等于谁都能往云上推音频。加了之后
客户端 `Hello` 里不带 token 会收到 `Control: auth_required` 并被断开。

证书里带 `SAN=IP:203.0.113.10`，手机按 IP 直连也能过严格校验（`scripts/gen_local_ca.py --ip 203.0.113.10`）。
换端口/换 IP 记得同时重发证书与 APK 里的 `assets/local-ca.pem`。

### 换端口要一起改的三处

控制口跟着 `--listen + 2` 走，但 `mcp/` 里那两个工具脚本是**写死 URL 的**，
换了端口不改它们，模型就会说「挂断的工具现在不可用了」：

```ini
# cloud/.env（服务器上那份）
PHOTO_CTL_PORT=63995                                    # harness 监听哪个控制口
PHOTO_CTL_URL=http://127.0.0.1:63995/take_photo          # mcp-camera.mjs 读这个
SESSION_CTL_URL=http://127.0.0.1:63995/end_session       # mcp-session.mjs 读这个
```

### agent 必须是 `--dsh-sdk`

headless 模式（不带 `--dsh-sdk`）的 `dsh headless` 不接 `--patch`，MCP 工具根本
没注册，模型只能回「工具不可用」。带 `--dsh-sdk` 才会用 `DSH_PATCH` 里的
`mcp/camera.yml` 挂上 `take_photo` / `end_session`，顺带省掉每次重启进程的开销。

## 已知限制

- 单设备：一条连接结束后进程退出；多设备要 `runTransport({singleConnection: false})`
- 图片落盘默认关闭（`PHOTO_DIR` 为空），识图轮次仍可用

### 下行控制串（`Control=2` 的 payload）

| 串 | 什么时候发 | 客户端该做什么 |
|---|---|---|
| `stop` | 用户插话，云端判定这一轮作废 | 立刻停止播放并清空播放队列 |
| `stop_capture` | 模型调了「退出」工具 | **立刻停止麦克风采集**（SDK：`endVoiceSession()`），但别断开蓝牙会话 —— 告别语还要靠它播完 |
| `take_photo` | 模型调了拍照工具 | 让眼镜拍一张并回 `Image` 帧（SPP，1 s，576×432） |
| `take_photo_hq` | 会话里有人要看细节 | 走协作式高清：建 P2P → 回 `hq_armed` → **等用户按快门** → 回 `hq_image` + `Image` |
| `hq_cancel` | 云端不再需要那张高清图 | 立刻解散 P2P（别占着），回 `hq_failed`（`reason=cancelled`） |
| `end_session` | 云端认为会话该结束了 | 收尾并断开 |
| `auth_required` / `auth_failed` | 鉴权失败 | 检查 token，别重试同一份凭据 |

`stop_capture` 是 2026-11-13 补的：之前模型一调退出工具，眼镜会继续采集到整段告别语
播完（实测多采了 2–5 秒），既费电又容易把周围对话再喂进 ASR。

### 上行状态帧（`Status=5` 的 payload，UTF-8 JSON）

高清照必须由**人按下快门**才落盘（程序化快门不写相册，见
私有文档`），所以这条链路上有几十秒是「等用户」。
没有状态帧时，手机早就放弃了云端还在干等总超时。四个事件：

| 事件 | 字段 | 云端动作 |
|---|---|---|
| `hq_armed` | `fresh`（`"true"` 表示相机里已经有新图） | 结束「等就绪」，开始「等快门」；`fresh=false` 时播一句「请按一下眼镜上的快门」 |
| `hq_image` | — | 紧接着的 `Image` 帧算**高清原图**（不是 SPP 低清回图） |
| `hq_failed` | `reason`：`no_shutter` / `p2p` / `join` / `connect` / `arm` / `download` / `decode` / `send` / `cancelled` | 立刻结束等待，把原因翻成一句可执行的话给模型 |

**为什么必须有 `hq_image` 公告**：等高清图的时候，用户按快门会让相机同时回一张 SPP
低清图，没有公告就只能按到达顺序猜 —— 实测把 17 KB 的糊图当成原图交付过。

### 高清照的时间预算

| 段 | 典型 | 兜底（手机侧 / 云端侧） |
|---|---|---|
| 建组 + 入组 + 连相册 | 5.4 s | 15 s / — |
| 提示语 | 1.5 s | — |
| 等用户按快门 + LLHDR + 落盘 | 5–9 s | 20 s / 25 s |
| 下载 8 MB + 缩图 + 上行 1.1 MB | 2 s | 20 + 10 + 5 s / — |

可调：`MIAI_HQ_ARM_TIMEOUT_MS`（默认 15000）、`MIAI_HQ_SHUTTER_TIMEOUT_MS`
（默认 25000）。手机侧放弃得更早（20 s）并主动上报，所以正常情况下永远是**手机的
原因先到**，云端那 25 s 只是「手机也没消息」的兜底。
