/**
 * 句子 → 一轮话语（utterance）的合并与判停。
 *
 * <h2>职责</h2>
 *
 * ASR 的 `SentenceEnd` 是**句子**边界，不是**轮次**边界：
 * 「小爱同学。」和「我眼前的是什么？」会各发一次，照单全收就是两轮问答
 * （白烧一次 agent 调用）。本模块负责把若干句子合并成一轮，并决定
 * 「这一轮是不是真的说完了」。
 *
 * <h2>判停策略</h2>
 *
 * **不用**「问号结尾就直接收下」。ASR 的 `？` 是按语调加的，不代表语义完整 ——
 * 实测它把「你在运行什么？」（句中停顿）判成句尾，一句话被拆成三轮
 * （「你在运行什么？」→「平台上。」）。
 *
 * 所以一律先等 {@link UtteranceAssemblerOptions.quietMs}，再交给
 * {@link UtteranceAssemblerOptions.predict}（Smart Turn）看**音频本身**。
 * 它说没说完就再等一段，最多 {@link UtteranceAssemblerOptions.maxExtensions} 次，
 * 之后强制收下，避免模型不确定时永远不定稿。
 *
 * 本模块**不做 I/O**：判停器、日志、时钟全部注入。
 */
import { pcm16DurationMs } from './pcm.ts';

export interface UtteranceAssemblerOptions {
  /** 句子结束后先静候这么久再判定 */
  readonly quietMs: number;
  /** 判为「没说完」时再多等这么久 */
  readonly extendMs: number;
  /** 最多延长几次 */
  readonly maxExtensions: number;
  /** 没有判停器时的固定合并窗口（旧行为） */
  readonly fallbackWindowMs: number;
  /** 概率门限 */
  readonly threshold: number;
  /** 送给判停器的最近音频上限（字节） */
  readonly recentMaxBytes: number;
  /** 判停器。返回 <0 表示不可用 */
  readonly predict?: (pcm: Buffer) => Promise<number>;
  /** 一轮定稿 */
  readonly onUtterance: (text: string) => void;
  readonly log: (line: string) => void;
  /** 可注入时钟，测试用 */
  readonly now?: () => number;
}

/** 判定一次的结果，便于日志与测试断言 */
export type EndpointDecision = 'accept' | 'extend' | 'predictor-unavailable';

export class UtteranceAssembler {
  private readonly options: UtteranceAssemblerOptions;
  private readonly now: () => number;
  private readonly recentChunks: Buffer[] = [];
  private recentBytes = 0;
  private pendingText = '';
  private pendingTimer: NodeJS.Timeout | null = null;
  private extensions = 0;
  private disposed = false;

  constructor(options: UtteranceAssemblerOptions) {
    this.options = options;
    this.now = options.now ?? Date.now;
  }

  get pending(): string {
    return this.pendingText;
  }

  /** 每块上行 PCM 都要喂进来，判停器需要「最近的音频」而不只是文本。 */
  addAudio(chunk: Buffer): void {
    this.recentChunks.push(Buffer.from(chunk));
    this.recentBytes += chunk.length;
    while (this.recentBytes > this.options.recentMaxBytes && this.recentChunks.length > 1) {
      const dropped = this.recentChunks.shift();
      this.recentBytes -= dropped?.length ?? 0;
    }
  }

  /** 一句定稿的识别结果。 */
  addSentence(text: string): void {
    if (this.disposed) {
      return;
    }
    this.pendingText = this.pendingText ? this.pendingText + text : text;
    this.cancelTimer();
    const delay = this.options.predict
      ? this.options.quietMs
      : this.options.fallbackWindowMs;
    this.pendingTimer = setTimeout(() => { void this.evaluate(); }, delay);
  }

  /** 立即定稿（连接结束时兜底，避免最后一句被丢弃）。 */
  flush(): void {
    if (this.disposed) {
      return;
    }
    this.cancelTimer();
    this.extensions = 0;
    const text = this.pendingText.trim();
    this.pendingText = '';
    if (!text) {
      return;
    }
    this.options.onUtterance(text);
  }

  dispose(): void {
    this.disposed = true;
    this.cancelTimer();
    this.pendingText = '';
    this.recentChunks.length = 0;
    this.recentBytes = 0;
  }

  /** 判定当前这一轮是否定稿。 */
  private async evaluate(): Promise<void> {
    this.pendingTimer = null;
    if (this.disposed || !this.pendingText.trim()) {
      return;
    }

    const predict = this.options.predict;
    if (!predict) {
      this.flush();
      return;
    }

    const probability = await predict(Buffer.concat(this.recentChunks));
    const shown = probability < 0 ? '故障' : probability.toFixed(3);

    if (probability < 0) {
      // 判停器出问题就退回保守策略：宁可多合成一轮，也别把用户的话吞掉
      this.options.log(`  判停    ${shown} → 判停器不可用，直接结束本轮`);
      this.flush();
      return;
    }

    if (
      probability >= this.options.threshold
      || this.extensions >= this.options.maxExtensions
    ) {
      this.options.log(`  判停    ${shown} → 收下这一轮`);
      this.flush();
      return;
    }

    this.extensions += 1;
    this.options.log(
      `  判停    ${shown} → 没说完，再等 ${this.options.extendMs} ms`,
    );
    this.pendingTimer = setTimeout(() => { void this.evaluate(); }, this.options.extendMs);
  }

  private cancelTimer(): void {
    if (this.pendingTimer) {
      clearTimeout(this.pendingTimer);
      this.pendingTimer = null;
    }
  }

  /** 便于日志：最近音频的时长 */
  get recentDurationMs(): number {
    return pcm16DurationMs(this.recentBytes, 16_000);
  }

  /** 便于测试：当前时间戳 */
  get timestampMs(): number {
    return this.now();
  }
}
