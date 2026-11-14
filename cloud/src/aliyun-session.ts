/**
 * 一条连接的**编排**：把几个各管一段的模块接起来，本身不含任何判定逻辑。
 *
 * ```text
 * TCP ──transport──┬─ Audio ──▶ UplinkPipeline（ffmpeg + 能量门）──▶ SttSession
 *                  ├─ Image ──▶ PhotoInbox ──┬─ 有人在等：交图
 *                  │                         └─ 没人等：SessionCore 起一轮识图
 *                  └─ Wake  ──▶ 诊断日志
 *
 * 工具控制口 ──▶ PhotoInbox.request() / SessionCore.endRequested
 * 一轮定稿 ──▶ SessionCore ──effects──▶ TurnRunner ──▶ connection.sendAudio/sendControl
 * ```
 *
 * 每一格都能单独测：判定在 `session-core.ts`（纯决策），解码与判停在
 * `uplink-pipeline.ts`，协议在 `transport.ts`，这里只剩接线。
 */
import type { TurnTicket } from './dialog-fsm.ts';
import { SessionArchive } from './archive.ts';
import type { AnnounceStore } from './announce-store.ts';
import { UpstreamFrameType } from './frame.ts';
import { DEFAULT_HQ_ARM_TIMEOUT_MS, DEFAULT_HQ_SHUTTER_TIMEOUT_MS, PhotoInbox } from './photo-inbox.ts';
import type { AgentProvider, SttProvider, SttSession, TtsProvider } from './providers.ts';
import { SessionCore, type CoreEffect } from './session-core.ts';
import type { ImageAttachment } from './session.ts';
import { SmartTurn } from './smart-turn.ts';
import { startToolControl, type ToolControl } from './tool-control.ts';
import type { PhotoFailReason } from './photo-inbox.ts';
import { runTransport, type Connection, type HelloInfo } from './transport.ts';
import { TurnRunner } from './turn-runner.ts';
import { UplinkPipeline } from './uplink-pipeline.ts';
import { UtteranceAssembler } from './utterance-assembler.ts';

/** 上行采样率：mSBC 解码后固定 16 kHz */
export const UPSTREAM_SAMPLE_RATE = 16_000;

/**
 * 没有判停器（SmartTurn 关掉）时的固定合并窗口。
 *
 * 旧行为：收到一句 ASR 就再等 600 ms 看有没有下文，没有就定稿。
 */
const ASSEMBLER_FALLBACK_WINDOW_MS = 600;

/**
 * 送给判停器的「最近音频」上限（秒）。
 *
 * 判停器要听到整句话的尾巴才能判「说完了」，8 s 足够覆盖常见的一句话，
 * 又不至于把整段对话都留在内存里。
 */
const ASSEMBLER_RECENT_SECONDS = 8;

export interface AliyunSessionOptions {
  /** 监听端口 */
  readonly port: number;
  /**
   * 监听地址。缺省 `127.0.0.1` —— 本地开发默认不暴露；
   * 上公网时由部署方显式给 `0.0.0.0`（或具体网卡地址）。
   */
  readonly host?: string;
  readonly ffmpegPath: string;
  /** Agent（LLM）。注入而不是 import 具体实现 —— 换模型不用动编排层。 */
  readonly agent: AgentProvider;
  /** STT。每次连接由 provider 开一个新会话；换参考实现同理。 */
  readonly stt: SttProvider;
  /** TTS。采样率由 provider 自己声明，这里不再单独配。 */
  readonly tts: TtsProvider;
  /** Smart Turn 判停器；缺任一项则不启用，退回固定合并窗口 */
  readonly smartTurn?: {
    readonly pythonPath: string;
    readonly scriptPath: string;
    readonly quietMs: number;
    readonly extendMs: number;
    readonly maxExtensions: number;
    readonly threshold: number;
  };
  /** 上行增益（喂给 STT 前放大；眼镜 mic 电平偏低） */
  readonly micGain?: number;
  /** 电平表周期（毫秒）；> 0 时每这么久打一行上行峰值，调增益用 */
  readonly levelLogMs?: number;
  /** 能量门 */
  readonly gate: {
    readonly thresholdDbfs: number;
    readonly holdMs: number;
    readonly prerollMs: number;
  };
  /** 播完后额外等待的余量 */
  readonly playbackTailMs: number;
  /** 下行语音增益（默认 1）；MiMo 输出偏小，实机用 2–3 更合适 */
  readonly ttsGain?: number;
  /** 落盘目录，空串表示不落盘 */
  readonly archiveDir: string;
  /** 单段档案上限（MB），到顶另起一段 */
  readonly archiveMaxMb: number;
  /** 保留多少个会话档案目录 */
  readonly archiveKeep: number;
  /** 照片落盘目录；空串表示不落盘（默认） */
  readonly photoDir: string;
  /** 控制口端口与超时 */
  readonly controlPort: number;
  /** `spp` 低清照的总超时 */
  readonly photoTimeoutMs: number;
  /**
   * `hq` 高清照的两段超时（默认 15 s / 25 s）。
   *
   * 高清照需要用户按快门（程序化快门不写相册），所以「等手机就绪」和
   * 「等用户按下去」必须分开计 —— 见 `photo-inbox.ts` 的说明。
   */
  readonly hqArmTimeoutMs?: number;
  readonly hqShutterTimeoutMs?: number;
  /** 默认的识图提问 */
  readonly imageQuestion: string;
  /** 离线时的后台汇报队列（null / 不给 = 不持久化） */
  readonly announceStore?: AnnounceStore | null;

  /**
   * TLS。**不给就是明文** —— 本地开发不必先造证书；上公网时由部署方注入。
   * 证书路径/内容属于部署细节，不进源码（见 （内部待办））。
   */
  readonly tls?: {
    readonly key: Buffer;
    readonly cert: Buffer;
  };

  /**
   * 首帧鉴权。**不给就不要求 `Hello`**，任何客户端能直接发 Audio。
   *
   * 给了就必须先收到合法 `Hello` 才放行后续帧；返回 false 会回一条
   * `Control: auth_failed` 再断开。
   */
  readonly authenticate?: (hello: HelloInfo) => boolean;

  /** 心跳/空闲上限：这么久没收到任何字节就主动断开（默认 45 s）。 */
  readonly idleTimeoutMs?: number;

  /**
   * 常驻模式：一条连接结束后**继续等**下一条（默认 false = 单连接就退出）。
   *
   * 调试时客户端会反复重连（改端口、换证书、拉断重试），每次都要手动重启服务
   * 很烦；`--serve` 就是给它准备的。
   */
  readonly serve?: boolean;
}

/**
 * 启动监听，按连接串行处理。返回进程退出码。
 *
 * 监听、鉴权、心跳、TLS 全在 `transport.ts`；这里只决定「一条连接里谁来干活」。
 */
export async function runAliyunServer(options: AliyunSessionOptions): Promise<number> {
  return await runTransport({
    port: options.port,
    host: options.host,
    tls: options.tls,
    authenticate: options.authenticate,
    idleTimeoutMs: options.idleTimeoutMs,
    singleConnection: options.serve !== true,
    log: (line) => { console.log(line); },
    onConnection: (conn) => { new AliyunConnection(conn, options).start(); },
  });
}

/** 一条连接的完整状态：只负责把各模块接起来。 */
class AliyunConnection {
  private readonly conn: Connection;
  private readonly options: AliyunSessionOptions;

  private readonly core: SessionCore;
  private readonly uplink: UplinkPipeline;
  private readonly photos: PhotoInbox;
  private readonly smartTurn: SmartTurn | null;
  private readonly assembler: UtteranceAssembler;
  private readonly runner: TurnRunner;
  private readonly stt: SttSession;

  private control: ToolControl | null = null;
  private serialChain: Promise<void> = Promise.resolve();

  /** 原始档案（`MIAI_ARCHIVE_DIR` 为空时是 null） */
  private readonly archive: SessionArchive | null;

  /** 收到 `hq_image` 公告：下一个 Image 帧算高清图 */
  private expectHqImage = false;

  constructor(conn: Connection, options: AliyunSessionOptions) {
    this.conn = conn;
    this.options = options;

    const log = (line: string): void => { console.log(line); };
    this.core = new SessionCore({ imageQuestion: options.imageQuestion, log });

    // 档案先开：后面每一段音频/每个事件都要往里写
    this.archive = SessionArchive.open(
      {
        rootDir: options.archiveDir,
        tag: conn.remote,
        maxBytesPerSegment: options.archiveMaxMb * 1024 * 1024,
        keepSessions: options.archiveKeep,
        log,
      },
      {
        remote: conn.remote,
        micGain: options.micGain ?? 1,
        ttsGain: options.ttsGain ?? 1,
        gateDbfs: options.gate.thresholdDbfs,
        gateHoldMs: options.gate.holdMs,
        gatePrerollMs: options.gate.prerollMs,
        uplinkRate: UPSTREAM_SAMPLE_RATE,
        downlinkRate: options.tts.sampleRate,
        stt: options.stt.name,
        tts: options.tts.name,
        agent: options.agent.name,
        levelLogMs: options.levelLogMs ?? 0,
      },
    );

    const smartTurnOptions = options.smartTurn;
    this.smartTurn = smartTurnOptions
      ? new SmartTurn({
        python: smartTurnOptions.pythonPath,
        script: smartTurnOptions.scriptPath,
        log: (line) => { console.log(`  ${line}`); },
      })
      : null;

    this.assembler = new UtteranceAssembler({
      quietMs: smartTurnOptions?.quietMs ?? 0,
      extendMs: smartTurnOptions?.extendMs ?? 0,
      maxExtensions: smartTurnOptions?.maxExtensions ?? 0,
      fallbackWindowMs: ASSEMBLER_FALLBACK_WINDOW_MS,
      threshold: smartTurnOptions?.threshold ?? 1,
      recentMaxBytes: UPSTREAM_SAMPLE_RATE * 2 * ASSEMBLER_RECENT_SECONDS,
      predict: this.smartTurn
        ? (pcm: Buffer) => this.smartTurn!.predict(pcm)
        : undefined,
      onUtterance: (text) => {
        console.log('\n--- 本轮 ---');
        console.log(`  文本    ${JSON.stringify(text)}`);
        this.execute(this.core.dispatch({ type: 'utterance', text }));
      },
      log,
    });

    this.stt = options.stt.open({
      onPartial: (text) => { this.onPartial(text); },
      onSentence: (text, index) => { this.onSentence(text, index); },
      onError: (error) => { this.onAsrError(error); },
    });

    this.photos = new PhotoInbox({
      dir: options.photoDir,
      timeoutMs: options.photoTimeoutMs,
      armTimeoutMs: options.hqArmTimeoutMs ?? DEFAULT_HQ_ARM_TIMEOUT_MS,
      shutterTimeoutMs: options.hqShutterTimeoutMs ?? DEFAULT_HQ_SHUTTER_TIMEOUT_MS,
      log,
      sendTakePhoto: (kind) => {
        this.sendControl(kind === 'hq' ? 'take_photo_hq' : 'take_photo');
      },
      sendCancel: () => { this.sendControl('hq_cancel'); },
    });

    this.uplink = new UplinkPipeline({
      ffmpegPath: options.ffmpegPath,
      sampleRate: UPSTREAM_SAMPLE_RATE,
      gate: options.gate,
      gain: options.micGain,
      levelLogMs: options.levelLogMs,
      log,
      onError: (line) => { console.error(`[ffmpeg] ${line}`); },
      onRawPcm: (raw) => { this.archive?.pcmRaw(raw); },
      onPcm: (pcm) => {
        // 判停器要看音频本身，不只是文本
        this.assembler.addAudio(pcm);
      },
      onSpeechOpen: (peakDbfs, preroll, prerollMs) => {
        this.archive?.event('gate.open', { peakDbfs: Number(peakDbfs.toFixed(1)), prerollMs });
        for (const part of preroll) {
          this.stt.feed(part);
        }
      },
      onSpeech: (pcm) => { this.stt.feed(pcm); },
      onSpeechClose: () => {
        this.archive?.event('gate.close');
        this.stt.hangup();
      },
    });

    this.runner = new TurnRunner({
      agent: options.agent,
      tts: options.tts,
      downlink: conn,
      fsm: this.core.fsm,
      playbackTailMs: options.playbackTailMs,
      ttsGain: options.ttsGain,
      onDownlinkChunk: (chunk) => { this.archive?.downlink(chunk); },
      onEvent: (name, fields) => { this.archive?.event(name, fields); },
      onTurnFinished: () => {
        this.execute(this.core.dispatch({ type: 'turnFinished' }));
        // 本轮结束，后台任务的播报可以接着放
        void this.flushAnnounce();
      },
      log,
    });
  }

  start(): void {
    console.log(`已连接 ${this.conn.remote}\n`);

    // 后台任务完成后的播报落在这条连接上
    this.options.agent.setAnnouncer?.((speech) => { this.onAnnounce(speech); });

    // 上次离线期间攒下的汇报：连上就补播（旧的先播）
    const backlog = this.options.announceStore?.drain() ?? [];
    if (backlog.length > 0) {
      this.archive?.event('announce.replay', { count: backlog.length });
      console.log(`  补播离线期间攒了 ${backlog.length} 条后台汇报`);
      for (const speech of backlog) {
        this.announceQueue.push(speech);
      }
      void this.flushAnnounce();
    }

    // 业务只认「帧」和「关闭」：Hello、Ping/Pong、空闲看门狗、TLS 都在连接层
    this.conn.bind({
      onFrame: (type, payload) => { this.onFrame(type, payload); },
      onClose: () => { this.onClose(); },
    });

    this.control = startToolControl({
      port: this.options.controlPort,
      photoTimeoutMs: this.options.photoTimeoutMs,
      // 高清要等手机建 P2P 再等用户按快门，20 s 远远不够
      hqTimeoutMs: (this.options.hqArmTimeoutMs ?? DEFAULT_HQ_ARM_TIMEOUT_MS)
        + (this.options.hqShutterTimeoutMs ?? DEFAULT_HQ_SHUTTER_TIMEOUT_MS)
        + 5_000,
      handlers: {
        takePhoto: () => this.photos.request('spp'),
        takePhotoHq: () => this.photos.request('hq'),
        endSession: () => { this.execute(this.core.dispatch({ type: 'endRequested' })); },
        say: (text) => {
          // 调试口：和「用户说完一句」走同一条路，方便没有麦克风时联调
          this.execute(this.core.dispatch({ type: 'utterance', text }));
        },
        control: (text) => { this.conn.sendControl(text); },
        log: (line) => { console.log(line); },
      },
    });
  }

  // ------------------------------------------------------------------ 接线

  private onFrame(type: number, payload: Buffer): void {
    switch (type) {
      case UpstreamFrameType.Audio:
        this.archive?.sbc(payload);
        this.uplink.push(payload);
        return;
      case UpstreamFrameType.Image: {
        // 高清图会在 `hq_image` 公告之后到来；没有公告的就是 SPP 低清直出
        const kind = this.expectHqImage ? 'hq' : 'spp';
        this.expectHqImage = false;
        // 有拍照请求在等就交付；否则当成「看一眼」，起一轮识图
        if (!this.photos.deliver(kind, payload)) {
          this.execute(this.core.dispatch({ type: 'image', jpeg: payload }));
        }
        return;
      }
      case UpstreamFrameType.Status:
        this.onStatus(payload);
        return;
      case UpstreamFrameType.Wake:
        this.onWake(payload);
        return;
      default:
        console.error(`  ✗ 未知帧类型 ${type}（${payload.length} B），已丢弃`);
    }
  }

  /**
   * 手机上报的状态事件（高清照流程）。
   *
   * 以前没有这条通道：手机 30 s 后早就放弃了，云端却要自己干等到总超时才知道
   * 「用户没按快门」。现在两端各自说得清楚。
   */
  private onStatus(payload: Buffer): void {
    let event: Record<string, unknown>;
    try {
      event = JSON.parse(payload.toString('utf8')) as Record<string, unknown>;
    } catch {
      console.error(`  ✗ 状态帧不是合法 JSON：${payload.toString('utf8').slice(0, 120)}`);
      return;
    }
    const name = typeof event['ev'] === 'string' ? event['ev'] : '';
    this.archive?.event('status.recv', { name, raw: JSON.stringify(event).slice(0, 200) });
    switch (name) {
      case 'hq_armed': {
        const fresh = event['fresh'] === true || event['fresh'] === 'true';
        const wantPrompt = this.photos.armed(fresh);
        if (wantPrompt) {
          // 手机已经连上相册，现在才轮到人干活 —— 这句旁白就是让他去按快门
          void this.runner.speakCanned('好，请按一下眼镜上的快门，我等着。');
        }
        return;
      }
      case 'hq_image':
        // 下一个 Image 帧属于高清流程
        this.expectHqImage = true;
        return;
      case 'hq_failed': {
        const reason = typeof event['reason'] === 'string' ? event['reason'] : 'timeout';
        this.photos.failed(reason as PhotoFailReason);
        return;
      }
      default:
        console.log(`  ✗ 未知状态事件 ${JSON.stringify(name)}，已忽略`);
    }
  }

  /** 执行 core 给出的动作。**所有副作用只在这里发生。** */
  private execute(effects: readonly CoreEffect[]): void {
    for (const effect of effects) {
      switch (effect.type) {
        case 'assemble':
          this.assembler.addSentence(effect.text);
          break;
        case 'turn':
          this.enqueueTurn(effect.text, effect.image, effect.ticket);
          break;
        case 'awaitShot':
          this.enqueueShot(effect.text, effect.ticket);
          break;
        case 'cancel_shot':
          this.runner.cancelCanned();
          this.photos.cancel('用户改主意或收尾');
          break;
        case 'stop':
          this.sendControl('stop');
          break;
        case 'stop_capture':
          this.sendControl('stop_capture');
          break;
        case 'end':
          this.conn.end();
          break;
        case 'drop':
          break;
      }
    }
  }

  /** 下发控制串并记进档案：每次下发都能在时间线上对到当时的音频位置 */
  private sendControl(text: string): void {
    this.archive?.event('ctl.send', { text });
    this.conn.sendControl(text);
  }

  /** 后台任务完成后的播报排队：用户正在说/正在听的时候不插嘴 */
  private readonly announceQueue: string[] = [];
  private announcing = false;

  /**
   * 后台任务完成 → 播报。
   *
   * 这条**不属于任何一轮**（`speakCanned` 的旁白通道），所以不占轮次票据、不改状态机。
   * 用户正在说话或正在播报时排队，等回到 `listening` 再放出来 —— 抢话比晚两秒更烦人。
   */
  private onAnnounce(speech: string): void {
    this.archive?.event('announce', { len: speech.length });
    this.announceQueue.push(speech);
    void this.flushAnnounce();
  }

  private async flushAnnounce(): Promise<void> {
    if (this.announcing || this.announceQueue.length === 0) {
      return;
    }
    if (this.core.phase !== 'listening') {
      // 等本轮结束（`onTurnFinished` 会再叫一次）
      return;
    }
    this.announcing = true;
    try {
      while (this.announceQueue.length > 0) {
        const speech = this.announceQueue.shift();
        if (speech === undefined) {
          break;
        }
        await this.runner.speakCanned(speech);
        if (this.core.phase !== 'listening') {
          // 播报期间用户说话了，剩下的一条留到下一轮结束
          break;
        }
      }
    } finally {
      this.announcing = false;
    }
  }

  private onClose(): void {
    console.log(`\n连接结束，${this.core.turn} 轮`);
    // 连接没了，后台任务的播报就没有出口了（结果仍会在 dsh 会话里，下次连上可追问）
    this.options.agent.setAnnouncer?.(null);
    this.announceQueue.length = 0;
    this.execute(this.core.dispatch({ type: 'closed' }));
    // 收件箱里还有等待者的话，定时器会拖住进程（不必再下发 hq_cancel：连接都断了）
    this.photos.dispose();
    this.assembler.dispose();
    this.smartTurn?.stop();
    this.control?.close();
    this.archive?.close();
    this.stt.close();
    this.uplink.end();
  }

  // ------------------------------------------------------------------ 事件

  private onPartial(text: string): void {
    console.log(`  … ${text}`);
    this.archive?.event('asr.partial', { text });
    const effects = this.core.dispatch({ type: 'partial', text });
    if (effects.some((effect) => effect.type === 'stop')) {
      console.log('  打断听到识别结果 → 停止播放，已排队的轮次作废');
    }
    this.execute(effects);
  }

  private onSentence(text: string, index: number): void {
    console.log(`  句 ${index}  ${JSON.stringify(text)}`);
    this.archive?.event('asr.sentence', { text, index });
    this.execute(this.core.dispatch({ type: 'sentence', text }));
  }

  private onAsrError(error: Error): void {
    this.archive?.event('asr.error', { message: error.message.slice(0, 200) });
    // 空闲挂断是预期行为（网关对「没听到有效语音」的连接 10 s 就踢），
    // 不是故障，不要用 ✗ 污染日志。
    if (error.message.includes('IDLE_TIMEOUT')) {
      console.log('  [asr] 空闲挂断（无有效语音，正常）');
      return;
    }
    console.error(`  ✗ ASR: ${error.message}`);
  }

  private onWake(payload: Buffer): void {
    const [transactionId = '', requestId = ''] = payload.toString('utf8').split('\n');
    this.archive?.event('wake', { transactionId, requestId: requestId.slice(0, 8) });
    console.log(
      `  唤醒    transactionId=${transactionId}  requestId=${requestId.slice(0, 8)}…`,
    );
  }

  // ------------------------------------------------------------------ 一轮

  private enqueueTurn(text: string, image: ImageAttachment | undefined, ticket: TurnTicket): void {
    this.serialChain = this.serialChain.then(async () => {
      if (this.core.fsm.isStale(ticket)) {
        console.log('  （丢弃：用户已插话，这一轮过时了）');
        return;
      }
      this.archive?.event('turn.start', { text_len: text.length, image: image !== undefined });
      // 高清图由 `awaitShot` 那一步拿好并挂在 text/image 里，这里不再自己拍
      return this.runner.run(text, image).then(() => undefined);
    }).catch((error: Error) => {
      console.error(`  ✗ 队列任务失败（不影响后续）: ${error.message}`);
    });
  }

  /**
   * 去要一张高清照，拿到结果再交给状态机。
   *
   * 走串行队列：等图的这几十秒里不能开别的一轮（用户改主意时会先 `cancel_shot`
   * 让这里立刻返回，所以不会把后面的句子卡住）。
   */
  private enqueueShot(text: string, ticket: TurnTicket): void {
    this.serialChain = this.serialChain.then(async () => {
      if (this.core.fsm.isStale(ticket)) {
        console.log('  （丢弃：拍照请求已过时）');
        return;
      }
      const outcome = await this.photos.request('hq');
      this.execute(this.core.dispatch(
        outcome.ok
          ? { type: 'shotResult', ok: true, jpeg: outcome.jpeg }
          : { type: 'shotResult', ok: false, reason: outcome.reason },
      ));
    }).catch((error: Error) => {
      console.error(`  ✗ 拍照任务失败（不影响后续）: ${error.message}`);
    });
  }

  // ------------------------------------------------------------------ 档案

  /**
   * 档案已经在 `close()` 里收尾（`archive.close()`），这里不再单独落盘 ——
   * 旧的按轮次 `down_<turn>.pcm` 落盘已删掉：它只覆盖播放期，恰好漏掉
   * 「用户说话但没成句」这种最需要事后查的场景，而且同名文件会被下一条连接覆盖。
   */
}
