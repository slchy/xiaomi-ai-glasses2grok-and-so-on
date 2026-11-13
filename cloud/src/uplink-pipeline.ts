/**
 * 上行管道：mSBC → PCM（ffmpeg）→ 能量门 → 交给 STT 的「有声段」。
 *
 * 抽出来的理由：这段逻辑原本埋在 `aliyun-session.ts` 的 `bindUpstream()` 里，
 * 和 socket 回调混在一起，改一次判停就要动编排代码。
 */
import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';

import { EnergyGate } from './energy-gate.ts';
import { pcm16Gain, pcm16PeakDbfs } from './pcm.ts';

export interface UplinkGateConfig {
  readonly thresholdDbfs: number;
  readonly holdMs: number;
  readonly prerollMs: number;
}

export interface UplinkPipelineOptions {
  readonly ffmpegPath: string;
  /** 解码后的采样率（mSBC 固定 16 kHz） */
  readonly sampleRate: number;
  /**
   * 能量门配置。
   *
   * `thresholdDbfs` 是**放大后**（也就是实际喂给 ASR）的电平门限 —— 所以调大
   * [gain] 会同时让门更容易被推开。这是有意的：眼镜 mic 电平本来就低，小声说话
   * 时原始峰值只有 −65 dBFS 左右，若门限按放大前的电平算，增益再大也唤不醒 VAD。
   */
  readonly gate: UplinkGateConfig;
  /**
   * 上行增益（线性倍数，默认 1）。眼镜 mic 采进来的电平偏低，实测 ASR 识别率
   * 明显受它影响；放大后再喂 STT。
   *
   * ⚠️ 它同时决定门的灵敏度（见 [gate]）：增益越大，同样大的小声越容易被判为有声，
   * 噪声底也跟着抬高。`VOICE_PEAK_DBFS` 要按**放大后**的电平去设。
   */
  readonly gain?: number;
  readonly log: (line: string) => void;
  readonly onError: (line: string) => void;
  /**
   * 电平表周期（毫秒）。> 0 时每这么久打一行「上行电平峰值 -XX dBFS」。
   *
   * 调增益时唯一可靠的依据：只看"开门那一瞬"的峰值会误判 —— 门一开就记录，
   * 记到的往往是**起音**（最轻的那一下），而不是这段话真正的响度。实测就是这么
   * 看错了一次：以为 mic 只有 −57 dBFS，其实那只是跨过门限的第一块。
   *
   * 默认关（0）：正常运行时每 2 s 一行太吵。调音时开 `MIAI_LEVEL_LOG=2000`。
   */
  readonly levelLogMs?: number;
  /** 每块解码后的 PCM：判停器、落盘都要它 */
  readonly onPcm: (pcm: Buffer) => void;
  /**
   * 每块解码后、**放大前**的 PCM。档案用它 —— 存档必须是原始电平，
   * 否则事后没法重算"换个增益会不会识别出来"。
   */
  readonly onRawPcm?: (pcm: Buffer) => void;
  /** 有声开始：先把 preroll 喂进 STT，再继续喂后续 */
  readonly onSpeechOpen: (peakDbfs: number, preroll: readonly Buffer[], prerollMs: number) => void;
  readonly onSpeech: (pcm: Buffer) => void;
  /** 持续静音：请 STT 出定稿并挂断这一路 */
  readonly onSpeechClose: () => void;
}

export class UplinkPipeline {
  private readonly options: UplinkPipelineOptions;
  private readonly gate: EnergyGate;
  private readonly ffmpeg: ChildProcessWithoutNullStreams;
  private received = false;
  private closed = false;

  /** 电平表：窗口内峰值与窗口起点 */
  private meterPeak = -Infinity;
  private meterAt = 0;

  /**
   * 每 `levelLogMs` 打一行窗口峰值。
   *
   * 峰值取的是**放大后**的电平（和喂给 ASR 的一致），这样读数和门限是同一个坐标系。
   */
  private meter(pcm: Buffer): void {
    const period = this.options.levelLogMs ?? 0;
    if (period <= 0) {
      return;
    }
    const now = Date.now();
    if (this.meterAt === 0) {
      this.meterAt = now;
      this.meterPeak = -Infinity;
    }
    const peak = pcm16PeakDbfs(pcm);
    if (peak > this.meterPeak) {
      this.meterPeak = peak;
    }
    if (now - this.meterAt < period) {
      return;
    }
    const frames = Number.isFinite(this.meterPeak) ? this.meterPeak.toFixed(1) : '—';
    this.options.log(`  电平峰值 ${frames} dBFS（${((now - this.meterAt) / 1000).toFixed(1)} s）`);
    this.meterAt = now;
    this.meterPeak = -Infinity;
  }

  constructor(options: UplinkPipelineOptions) {
    this.options = options;

    // 门限作用在**放大后**的波形上：增益越大，小声越容易被判为有声。
    // 早先这里按 +20log10(gain) 同步抬高门限（"保持选择性不变"），结果是
    // 增益加得再多，小声说话依然推不开门 —— 用户的原话是「小声说话无法触发 VAD」。
    this.gate = new EnergyGate({
      thresholdDbfs: options.gate.thresholdDbfs,
      holdMs: options.gate.holdMs,
      prerollBytes: Math.round((options.gate.prerollMs / 1000) * options.sampleRate * 2),
      sampleRate: options.sampleRate,
    });

    this.ffmpeg = spawn(options.ffmpegPath, [
      '-hide_banner', '-loglevel', 'error',
      '-f', 'sbc', '-i', 'pipe:0',
      '-f', 's16le', '-ar', String(options.sampleRate), '-ac', '1', 'pipe:1',
    ], { stdio: ['pipe', 'pipe', 'pipe'] }) as ChildProcessWithoutNullStreams;

    this.ffmpeg.stderr.on('data', (data: Buffer) => {
      if (this.closed) {
        return; // 收尾阶段的抱怨不值得看（见 end()）
      }
      const text = data.toString().trim();
      if (text) {
        options.onError(text);
      }
    });

    this.ffmpeg.stdout.on('data', (pcm: Buffer) => { this.onPcm(pcm); });
  }

  /** 送一段 mSBC 压缩帧。 */
  push(sbc: Buffer): void {
    if (!this.ffmpeg.stdin.destroyed) {
      this.received = true;
      this.ffmpeg.stdin.write(sbc);
    }
  }

  /** 关管道。ffmpeg 偶尔不肯立刻退，兜底一秒后 kill。 */
  end(): void {
    this.closed = true;
    if (!this.received) {
      // 这段连接没收到任何音频：优雅关闭反而会让 ffmpeg 报
      // 「Output file does not contain any stream」，直接杀掉更干净
      try {
        this.ffmpeg.kill();
      } catch {
        // 已退出
      }
      return;
    }
    if (!this.ffmpeg.stdin.destroyed) {
      this.ffmpeg.stdin.end();
    }
    setTimeout(() => {
      try {
        this.ffmpeg.kill();
      } catch {
        // 已退出
      }
    }, 1000).unref?.();
  }

  private onPcm(raw: Buffer): void {
    const { onPcm, onSpeechOpen, onSpeech, onSpeechClose, onRawPcm, log } = this.options;

    onRawPcm?.(raw);

    const pcm = pcm16Gain(raw, this.options.gain ?? 1);
    onPcm(pcm);

    this.meter(pcm);

    const decision = this.gate.process(pcm);
    if (decision.opened) {
      log(
        `  上行有声 ${decision.opened.peakDbfs.toFixed(1)} dBFS → 开始推送`
        + `（补 ${decision.opened.preroll.length} 块 / ${decision.opened.prerollMs} ms）`,
      );
      onSpeechOpen(decision.opened.peakDbfs, decision.opened.preroll, decision.opened.prerollMs);
    }
    if (decision.forward) {
      onSpeech(pcm);
    }
    if (decision.closed) {
      log('  上行持续静音 → 停推并挂断（避免 IDLE_TIMEOUT）');
      onSpeechClose();
    }
  }
}
