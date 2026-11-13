/**
 * 旧的 silero-vad + MiMo 链路（调试/回放用，生产走 main.ts 的阿里云路径）。
 *
 * 从 main.ts 原样搬出：ReplyMode / AudioSink / 双阈值判停常量 /
 * looksComplete / dbSink / speak / Pipeline。
 * **只做搬移，未改行为。**
 */
import { spawn, execFileSync, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

import type { AgentProvider } from './providers.ts';
import { asr, pcmToWav, ttsStream, TTS_SAMPLE_RATE } from './mimo.ts';
import { sleep, peakDbfs } from './util.ts';
import { SileroVad, sliceFrames, SAMPLE_RATE, WINDOW } from './vad.ts';

/**
 * 判停之后拿识别文本做什么。
 * - `none`  只识别，不回复（冒烟用）
 * - `echo`  原样念回去（验证下行通路）
 * - `agent` 交给 dsh，念它给的回复
 */
export type ReplyMode = 'none' | 'echo' | 'agent';

/**
 * 下行音频的出口。
 *
 * - 本机调试：先收完再用 adb push 给手机播（adb 没有流式播放）
 * - 上手机：直接写回同一条 TCP，手机边收边播
 */
export interface AudioSink {
  write(chunk: Buffer): void | Promise<void>;
  end?(): void | Promise<void>;
}

/**
 * 双阈值判停。
 *
 * 单一阈值解决不了「正常提问该快」和「思考停顿不该被切断」的矛盾：
 * 4 秒太慢（业界对话助手普遍 0.5–1 s），1 秒又会切断实测到的 3.6 s 停顿。
 *
 * - 短阈值 1.0 s（32 ms/帧 × 31）：先做一次「预判 ASR」，文本完整就进入确认窗口
 * - 长阈值 2.5 s（× 78）：文本不完整时的兼底，届时重新识别完整音频
 * - 确认窗口 0.5 s（× 16）：预判出「说完了」后再静听 0.5 s，
 *   这期间又出声就把预判作废。句号不等于说完了 —— 实测用户会在
 *   「音乐场景。」后面接着说「下一个是谁」，没有这个窗口就只拿到前半句。
 *
 * 短阈值取 1.0 s：0.6 s 实测会把「嗯，你觉得呢？」这种句子中间的短停顿
 * 当成说完，直接切断。1.0 s 接近业界对话助手的上限，仍然比原来的 4 s 快。
 *
 * 预判是异步的，不阻塞 VAD 主循环；期间继续累积音频，用户又开口就作废重来。
 */
const SHORT_SILENCE_FRAMES = 31;
const LONG_SILENCE_FRAMES = 78;
/** 预判命中后的确认窗口：再安静这么久才算真的说完 */
const CONFIRM_SILENCE_FRAMES = 16;
/** 标定值，见 src/vad.ts 顶部注释 */
const VAD_THRESHOLD = 0.4;
/** 单轮上限，防止设备一直推时无限累积 */
const MAX_UTTERANCE_SEC = 60;
const FRAME_BYTES = WINDOW * 2;

/** adb 目标设备，可用 ADB_SERIAL 覆盖 */
const ADB_SERIAL = process.env.ADB_SERIAL ?? '192.0.2.25:5555';
const REMOTE_JAR = '/data/local/tmp/miai.jar';
const REMOTE_PCM = '/data/local/tmp/reply.pcm';
const LOCAL_TMP = join(process.cwd(), '..', 'tmp');

/** 句末标点。MiMo ASR 会带标点，所以优先看标点。 */
const SENTENCE_END = /[。！？!?…]$/;
/** 没有标点时退化为语气词判断 */
const MODAL_END = /[吗呢吧啊呀嘛哦哈嘛]$/;

/**
 * 这句看起来说完了吗。
 *
 * 宁可偏向「没说完」：漏判的代价是多等 1.5 秒，误判的代价是把话切断。
 * 所以短到「嗯」「啊」这种一律不算，必须有实质内容。
 */
function looksComplete(text: string): boolean {
  const t = text.trim();
  if (!t) return false;
  const bare = t.replace(/[\s，,、。.!?！？…]/g, '');
  if (bare.length < 2) return false;
  return SENTENCE_END.test(t) || MODAL_END.test(t);
}


/**
 * 把 PCM 推回手机并从眼镜播出。
 *
 * TTS 输出是 **24 kHz**，不是上行的 16 kHz —— 搞错会 1.5 倍速升调。
 * 这条路依赖 adb，所以只在 harness 与手机同机（开发期 adb reverse）时可用；
 * 将来 harness 上 VPS 后要换成手机主动拉流。
 */
/**
 * 本机调试用的 sink：攒齐整段 PCM，再 adb push 给手机播。
 * adb 没有流式播放能力，所以本机路径天然是「收完再播」。
 */
export function adbSink(): AudioSink {
  const parts: Buffer[] = [];
  return {
    write: (chunk: Buffer) => { parts.push(chunk); },
    end: async () => { await speak(Buffer.concat(parts)); },
  };
}

export async function speak(pcm: Buffer): Promise<void> {
  // 服务器上没有 adb，也不该从服务器扬声器出声。上 VPS 后这里会换成
  // 「把 PCM 写回同一条 TCP」；在那之前用 --no-play 只验到 TTS 产出为止。
  if (process.argv.includes('--no-play')) {
    console.log(`  播放跳过（--no-play）`);
    return;
  }
  mkdirSync(LOCAL_TMP, { recursive: true });
  const local = join(LOCAL_TMP, `reply_${Date.now()}.pcm`);
  writeFileSync(local, pcm);

  execFileSync('adb', ['-s', ADB_SERIAL, 'push', local, REMOTE_PCM], { stdio: 'pipe' });

  const shell = `app_process -Djava.class.path=${REMOTE_JAR} /system/bin `
    + `com.miaiglasses.MainPlay ${REMOTE_PCM} ${TTS_SAMPLE_RATE}`;
  const out = execFileSync('adb', ['-s', ADB_SERIAL, 'shell', shell], {
    timeout: 120_000,
    encoding: 'utf8',
  });

  const passed = out.includes('MainPlay PASSED');
  const written = /written=\d+/.exec(out)?.[0] ?? 'written=?';
  console.log(`  播放    ${passed ? '✓' : '✗'} ${written}`);
}

export class Pipeline {
  private ff: ChildProcessWithoutNullStreams;
  private vad = new SileroVad();
  private pending: Buffer = Buffer.alloc(0);        // 尚未凑满一帧的 PCM
  private utterance: Buffer = Buffer.alloc(0);      // 本轮累积的 PCM（判停后送 ASR）
  private queue: Float32Array[] = [];
  private processing = false;
  private silenceFrames = 0;
  private hasSpoken = false;
  /** 本轮静音是否已发起过语义预判（重新出现语音就重置） */
  private prefetching = false;
  /** 预判还在飞行中。长阈值到时不能自己判停，否则预判结果白做。 */
  private prefetchPending = false;
  /** 预判说「完整」但还在确认窗口里，尚未判停 */
  private pendingTrigger: { text: string; pcm: Buffer } | null = null;
  /** 确认窗口已静听的帧数 */
  private confirmSilence = 0;
  private frames = 0;
  private speechFrames = 0;
  private finished = false;
  private finishedResolve: (() => void) | null = null;
  private finishedPromise: Promise<void>;
  /** 本轮 TTS 写出的字节数，用来估算播放时长 */
  private replyBytes = 0;
  private readonly startedAt = Date.now();
  private readonly mode: ReplyMode;
  private readonly agent: AgentProvider | null;
  private sink: AudioSink;

  constructor(ffmpeg: string, mode: ReplyMode, agent: AgentProvider | null = null, sink?: AudioSink) {
    this.mode = mode;
    this.agent = agent;
    this.sink = sink ?? adbSink();
    this.finishedPromise = new Promise((r) => { this.finishedResolve = r; });

    this.ff = spawn(ffmpeg, [
      '-hide_banner', '-loglevel', 'error',
      '-f', 'sbc', '-i', 'pipe:0',
      '-f', 's16le', '-ar', String(SAMPLE_RATE), '-ac', '1', 'pipe:1',
    ], { stdio: ['pipe', 'pipe', 'pipe'] }) as ChildProcessWithoutNullStreams;

    this.ff.stderr.on('data', (d: Buffer) => {
      const s = d.toString().trim();
      if (s) console.error('[ffmpeg] ' + s);
    });

    this.ff.stdout.on('data', (chunk: Buffer) => {
      this.pending = Buffer.concat([this.pending, chunk]);
      const { frames, rest } = sliceFrames(this.pending);
      this.pending = rest;
      if (frames.length > 0) {
        this.queue.push(...frames);
        void this.drain();
      }
    });

    void this.vad.init().catch((e: unknown) => {
      console.error('VAD 初始化失败:', (e as Error).message);
      process.exit(1);
    });
  }

  get isFinished(): boolean {
    return this.finished;
  }

  /** 切换下行音频出口。listen 模式在连接建立时指向 socket。 */
  setSink(sink: AudioSink): void {
    this.sink = sink;
  }

  feed(msbc: Buffer): void {
    if (this.finished) return;
    if (!this.ff.stdin.destroyed) this.ff.stdin.write(msbc);
  }

  closeInput(): void {
    if (!this.ff.stdin.destroyed) this.ff.stdin.end();
  }

  wait(): Promise<void> {
    return this.finishedPromise;
  }

  /** 本轮回复的音频时长（毫秒）。多轮时用来等播放结束再恢复收音。 */
  get replyDurationMs(): number {
    return (this.replyBytes / (TTS_SAMPLE_RATE * 2)) * 1000;
  }

  /**
   * 重置到「等待新一轮」的状态。
   *
   * ffmpeg 进程不复用也不重建：mSBC 是自包含帧，解码器可以一直跑，
   * 这里只重置**判停相关**的状态（VAD 的 LSTM 隐状态、累积音频、计数）。
   * VAD 必须 reset：上一轮的语音上下文留着会让新一轮开头就误判成语音。
   */
  reset(): void {
    this.vad.reset();
    this.pending = Buffer.alloc(0);
    this.utterance = Buffer.alloc(0);
    this.queue = [];
    this.silenceFrames = 0;
    this.hasSpoken = false;
    this.prefetching = false;
    this.prefetchPending = false;
    this.pendingTrigger = null;
    this.confirmSilence = 0;
    this.frames = 0;
    this.speechFrames = 0;
    this.replyBytes = 0;
    this.finished = false;
    this.finishedResolve = null;
    this.finishedPromise = new Promise<void>((r) => { this.finishedResolve = r; });
  }

  // ---------------------------------------------------------------- 内部
  private async drain(): Promise<void> {
    if (this.processing) return;
    this.processing = true;
    try {
      while (this.queue.length > 0 && !this.finished) {
        const f = this.queue.shift();
        if (!f) break;
        await this.onFrame(f);
      }
    } catch (e) {
      console.error('[drain] ' + (e as Error).message);
    } finally {
      this.processing = false;
    }
  }

  private async onFrame(frame: Float32Array): Promise<void> {
    // 整段累积，不裁前后静音：实测裁掉 1.15 s 静音后 ASR 结果一字未变，
    // 说明 MiMo 内部已在处理静音。裁剪只会多出「削掉开口弱音」
    // 和「切掉轻声结尾」两个风险，没有收益。
    const buf = Buffer.alloc(FRAME_BYTES);
    for (let i = 0; i < WINDOW; i += 1) {
      const v = Math.max(-1, Math.min(1, frame[i] ?? 0));
      buf.writeInt16LE(Math.round(v * 32767), i * 2);
    }
    this.utterance = Buffer.concat([this.utterance, buf]);

    const p = await this.vad.process(frame);
    this.frames += 1;

    if (p > VAD_THRESHOLD) {
      this.hasSpoken = true;
      this.speechFrames += 1;
      this.silenceFrames = 0;
      this.prefetching = false;   // 又说话了，上一轮预判作废
      // 确认窗口里又出声：用户没说完，预判作废，继续听
      this.pendingTrigger = null;
      this.confirmSilence = 0;
    } else {
      this.silenceFrames += 1;
    }

    if (!this.hasSpoken) return;

    if (this.utterance.length >= MAX_UTTERANCE_SEC * SAMPLE_RATE * 2) {
      this.trigger('达到上限');
      return;
    }

    // 确认窗口：预判已说「完整」，再静听 0.5 s 才真判停
    if (this.pendingTrigger) {
      this.confirmSilence += 1;
      if (this.confirmSilence >= CONFIRM_SILENCE_FRAMES) {
        const p = this.pendingTrigger;
        this.pendingTrigger = null;
        this.trigger('语义判停', p.text, p.pcm);
        return;
      }
    }

    // 长阈值：预判还在跑就等它；已经有 pending 就直接用它，不重复识别
    if (this.silenceFrames >= LONG_SILENCE_FRAMES && !this.prefetchPending) {
      const p = this.pendingTrigger;
      this.pendingTrigger = null;
      this.trigger('静音 2.5 s', p?.text ?? null, p?.pcm ?? null);
      return;
    }

    // 短阈值：发起一次语义预判（已有 pending 就不用再猜）
    if (this.silenceFrames >= SHORT_SILENCE_FRAMES && !this.prefetching && !this.pendingTrigger) {
      this.prefetching = true;
      void this.prefetch();
    }
  }

  /**
   * 语义预判：静音刚满 1 秒时先识别一次，看这句是不是说完了。
   *
   * 异步执行，不堵塞 VAD。返回时如果用户又开口了（silenceFrames 被清零），
   * 这次预判直接作废——音频已经变了，结论不再成立。
   */
  private async prefetch(): Promise<void> {
    if (this.finished) return;
    const pcm = this.utterance;
    if (pcm.length === 0) return;

    this.prefetchPending = true;
    const t0 = Date.now();
    try {
      const { text } = await asr(pcmToWav(pcm));
      if (this.finished) return;

      const ms = ((Date.now() - t0) / 1000).toFixed(2);

      // 识别期间用户又开口了：这段音频已经不代表当前句子，作废
      if (this.silenceFrames < SHORT_SILENCE_FRAMES) {
        console.log(`  预判    ${ms} s  已被新语音打断，丢弃`);
        return;
      }

      if (looksComplete(text)) {
        // 不直接判停：先进确认窗口，防止「句号说完、但其实还在说」
        console.log(`\n  预判    ${ms} s  ${JSON.stringify(text)}  → 看起来说完了，再确认 0.5 s`);
        this.pendingTrigger = { text, pcm };
        this.confirmSilence = 0;
        return;
      }

      console.log(`\n  预判    ${ms} s  ${JSON.stringify(text)}  → 没说完，继续等`);
      // 已经过了长阈值：用完整音频重新识别一次（少了尾部会丢内容）
      if (this.silenceFrames >= LONG_SILENCE_FRAMES) {
        this.trigger('静音 2.5 s（预判不完整）');
      }
    } catch (e) {
      // 预判只是加速手段，失败就退化成纯长阈值，不影响本轮
      console.log(`  预判失败: ${(e as Error).message}`);
      if (this.silenceFrames >= LONG_SILENCE_FRAMES) this.trigger('静音 2.5 s');
    } finally {
      this.prefetchPending = false;
    }
  }

  private trigger(
    reason: string,
    presetText: string | null = null,
    presetPcm: Buffer | null = null,
  ): void {
    if (this.finished) return;
    this.finished = true;

    // 预判命中时音频必须用同一快照：否则日志里的时长和文本对不上，
    // 多出来的是预判之后的静音尾巴。
    const pcm = presetPcm ?? this.utterance;
    this.utterance = Buffer.alloc(0);
    const elapsed = (Date.now() - this.startedAt) / 1000;

    void this.recognize(pcm, reason, elapsed, presetText);
  }

  private async recognize(
    pcm: Buffer,
    reason: string,
    elapsed: number,
    presetText: string | null = null,
  ): Promise<void> {
    const sec = pcm.length / (SAMPLE_RATE * 2);
    console.log(`\n--- 判停（${reason}）---`);
    console.log(`  音频    ${pcm.length} 字节  ${sec.toFixed(2)} s`);
    console.log(`  判停于  ${elapsed.toFixed(2)} s（流开始起算）`);
    console.log(`  帧统计  ${this.speechFrames}/${this.frames} 帧含语音`);

    if (pcm.length === 0) {
      console.log('  ✗ 无音频');
      this.finishedResolve?.();
      return;
    }

    const t0 = Date.now();
    try {
      let text = presetText ?? '';
      if (presetText === null) {
        const r = await asr(pcmToWav(pcm));
        text = r.text;
        console.log(`  ASR     ${((Date.now() - t0) / 1000).toFixed(2)} s  chunks=${r.chunks}`);
      } else {
        // 语义预判已经识别过同一段音频，不再重复调用
        console.log(`  ASR     跳过（预判已识别）`);
      }
      console.log(`  文本    ${JSON.stringify(text)}`);
      console.log(`\nASR_TEXT=${text}`);

      if (this.mode !== 'none' && text) {
        let speech = text;

        if (this.mode === 'agent' && this.agent) {
          const t0a = Date.now();
          try {
            const r = await this.agent.reply(text);
            speech = r.speech;
            console.log(`  agent   ${((Date.now() - t0a) / 1000).toFixed(2)} s  `
              + `${JSON.stringify(r.text.slice(0, 160))}`);
          } catch (e) {
            // agent 挂了不该吞掉本轮：退回原样念回去，至少链路是通的
            console.log(`  ✗ agent 失败: ${(e as Error).message}（退回回声）`);
          }
        }

        if (speech) {
          const t1 = Date.now();
          let bytes = 0;
          let chunks = 0;
          let firstAt = 0;
          for await (const pcm of ttsStream(speech)) {
            if (firstAt === 0) {
              firstAt = Date.now();
              console.log(`  TTS首块 ${((firstAt - t1) / 1000).toFixed(2)} s  ${pcm.length} 字节`);
            }
            bytes += pcm.length;
            chunks += 1;
            await this.sink.write(pcm);
          }
          this.replyBytes = bytes;
          console.log(`  TTS     ${((Date.now() - t1) / 1000).toFixed(2)} s  chunks=${chunks}`);
          console.log(`  回放    ${bytes} 字节  ${(bytes / (TTS_SAMPLE_RATE * 2)).toFixed(2)} s`);
          await this.sink.end?.();
        }
      }
    } catch (e) {
      console.log(`  ✗ ASR/TTS 失败: ${(e as Error).message}`);
    } finally {
      this.finishedResolve?.();
    }
  }
}
