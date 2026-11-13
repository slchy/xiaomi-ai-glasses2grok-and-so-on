/**
 * 语音会话的**原始档案**：一条连接一个目录，把"事后能查清"需要的东西全留下。
 *
 * <h2>为什么需要它</h2>
 *
 * 2026-11-14 出过一次「用户说了半分钟，LLM 一声不响」。事后查发现 ASR 一句都没定稿，
 * 但**没法再往下查**：当时的落盘只在播放期记 `up/vdown_<turn>.pcm`，而这段恰好
 * 发生在播放之前 —— 原始音频没留下，只能靠日志里几行峰值猜。
 *
 * 更糟的是旧命名是按"轮次"，`down_1.pcm` 在下一条连接会被覆盖。
 *
 * <h2>目录结构</h2>
 *
 * ```text
 * <root>/20261114-015500-203.0.113.20:2421/
 *   meta.json      配置快照（增益、门限、采样率、provider、模型）—— 档案必须自解释
 *   events.jsonl   时间线，每行都带三条流当时的字节偏移（可按事件切音频）
 *   uplink.sbc     手机发来的原始 mSBC，**未解码**（可重新解码、换增益重测）
 *   uplink.pcm     解码后的 PCM16 16 kHz，**放大前**（可任意重算增益/门限）
 *   downlink.pcm   下行 TTS PCM16 24 kHz
 * ```
 *
 * <h2>为什么要留"放大前"的 PCM</h2>
 *
 * 「小声说话识别不出来」这类问题，必须能在事后重放不同增益/门限下判断，
 * 而不是拿已经放大的波形去猜原始电平。
 *
 * <h2>容量</h2>
 *
 * 上行 mSBC ≈ 7.6 kB/s、上行 PCM ≈ 32 kB/s、下行 ≈ 48 kB/s（仅播报时）。
 * 到 `maxBytesPerSegment` 就**另起一段**（`…--2`、`…--3`），不是停录 ——
 * 丢掉的恰好可能是出问题的那一段。老目录按 `keepSessions` 轮转删除。
 */
import { closeSync, mkdirSync, openSync, readdirSync, rmSync, writeFileSync, writeSync } from 'node:fs';
import { join } from 'node:path';

export interface ArchiveOptions {
  /** 根目录；空串 = 关闭 */
  readonly rootDir: string;
  /** 进目录名，便于人肉辨认（设备 id 或 remote 地址） */
  readonly tag: string;
  /** 单段上限（字节）。到顶另起一段 */
  readonly maxBytesPerSegment: number;
  /** 保留多少个会话目录（按名字排序，多余的从旧到新删） */
  readonly keepSessions: number;
  readonly log: (line: string) => void;
}

/** 写进 `meta.json` 的运行配置。字段名用短名，方便 `jq` 看。 */
export type ArchiveMeta = Record<string, string | number | boolean | null>;

/**
 * 一段档案。**用文件描述符同步写**，不用 `WriteStream`。
 *
 * 理由：单连接模式下 `onClose()` 之后紧跟着就是 `process.exit()`，异步流里
 * 还没落盘的字节会被直接丢掉 —— 而"这次对话出了问题"的那几秒恰好就在末尾。
 * 数据量很小（mSBC 7.6 kB/s、PCM 32 kB/s），同步写完全承受得住。
 */
interface Segment {
  readonly events: number;
  readonly sbc: number;
  readonly pcm: number;
  readonly down: number;
  readonly sizes: { sbc: number; pcm: number; down: number; events: number };
}

export class SessionArchive {
  private readonly options: ArchiveOptions;
  private readonly startedAt = Date.now();
  private readonly baseName: string;
  private readonly item: ArchiveMeta;

  private segment: Segment | null = null;
  private segmentNo = 1;
  private closed = false;
  /** 累计事件条数，用于 stats */
  private eventCount = 0;

  /** 打不开就返回 null（存档失败绝不能影响通话） */
  static open(options: ArchiveOptions, meta: ArchiveMeta): SessionArchive | null {
    if (!options.rootDir) {
      return null;
    }
    try {
      mkdirSync(options.rootDir, { recursive: true });
      const archive = new SessionArchive(options, meta);
      archive.prune();
      archive.rotate();
      return archive;
    } catch (error) {
      options.log(`  ✗ 档案目录创建失败（不影响通话）：${(error as Error).message}`);
      return null;
    }
  }

  private constructor(options: ArchiveOptions, meta: ArchiveMeta) {
    this.options = options;
    this.item = meta;
    const stamp = new Date().toISOString().replace(/[-:]/g, '').replace('T', '-').slice(0, 15);
    // `tag` 通常是 `ip:port` —— 冒号在 Windows 上不能进文件名（本地开发同样要能跑）
    const safeTag = options.tag.replace(/[^\w.-]/g, '_');
    this.baseName = `${stamp}-${safeTag}`;
  }

  /** 原始 mSBC（手机 → 云端，未解码） */
  sbc(chunk: Buffer): void {
    this.write('sbc', chunk);
  }

  /** 解码后、**放大前**的 PCM16 16 kHz */
  pcmRaw(chunk: Buffer): void {
    this.write('pcm', chunk);
  }

  /** 下行 TTS PCM16 24 kHz */
  downlink(chunk: Buffer): void {
    this.write('down', chunk);
  }

  /** 时间线事件。自动带相对时间与三条流的当前偏移。 */
  event(name: string, fields?: Record<string, unknown>): void {
    const seg = this.segment;
    if (!seg || this.closed) {
      return;
    }
    const line = `${JSON.stringify({
      t: Date.now() - this.startedAt,
      ev: name,
      ...fields,
      at: { sbc: seg.sizes.sbc, pcm: seg.sizes.pcm, down: seg.sizes.down },
    })}\n`;
    try {
      writeSync(seg.events, line);
      seg.sizes.events += line.length;
      this.eventCount += 1;
    } catch {
      // 写不进去就算了，档案不该影响通话
    }
  }

  close(): void {
    if (this.closed) {
      return;
    }
    this.event('close', {
      sec: Math.round((Date.now() - this.startedAt) / 1000),
      events: this.eventCount,
      segments: this.segmentNo,
    });
    this.closed = true;
    const seg = this.segment;
    this.segment = null;
    if (seg) {
      for (const fd of [seg.events, seg.sbc, seg.pcm, seg.down]) {
        try {
          closeSync(fd);
        } catch {
          // 已关
        }
      }
    }
    this.options.log(`  档案    ${this.baseName}（${this.eventCount} 条事件，${this.segmentNo} 段）`);
  }

  // ------------------------------------------------------------------ 内部

  private write(kind: 'sbc' | 'pcm' | 'down', chunk: Buffer): void {
    let seg = this.segment;
    if (!seg || this.closed || chunk.length === 0) {
      return;
    }
    if (seg.sizes[kind] + chunk.length > this.options.maxBytesPerSegment) {
      this.segmentNo += 1;
      this.rotate();
      seg = this.segment;
      if (!seg) {
        return;
      }
    }
    try {
      writeSync(seg[kind], chunk);
      seg.sizes[kind] += chunk.length;
    } catch {
      // 同上：档案失败不影响通话
    }
  }

  /** 新开一段目录 */
  private rotate(): void {
    const suffix = this.segmentNo === 1 ? '' : `--${this.segmentNo}`;
    const dir = join(this.options.rootDir, `${this.baseName}${suffix}`);
    mkdirSync(dir, { recursive: true });
    const meta = {
      ...this.item,
      segment: this.segmentNo,
      startedAt: new Date(this.startedAt).toISOString(),
      root: this.options.rootDir,
      maxBytesPerSegment: this.options.maxBytesPerSegment,
    };
    writeFileSync(join(dir, 'meta.json'), `${JSON.stringify(meta, null, 2)}\n`);

    const size = { sbc: 0, pcm: 0, down: 0, events: 0 };
    this.segment = {
      sizes: size,
      events: openSync(join(dir, 'events.jsonl'), 'a'),
      sbc: openSync(join(dir, 'uplink.sbc'), 'a'),
      pcm: openSync(join(dir, 'uplink.pcm'), 'a'),
      down: openSync(join(dir, 'downlink.pcm'), 'a'),
    };
    this.event('open', { segment: this.segmentNo });
  }

  /** 只保留最近 `keepSessions` 个会话目录 */
  private prune(): void {
    const keep = this.options.keepSessions;
    if (keep <= 0) {
      return;
    }
    try {
      const dirs = readdirSync(this.options.rootDir, { withFileTypes: true })
        .filter((d) => d.isDirectory())
        .map((d) => d.name)
        .sort();
      const extra = dirs.length - keep;
      for (let i = 0; i < extra; i += 1) {
        const name = dirs[i];
        if (name !== undefined) {
          rmSync(join(this.options.rootDir, name), { recursive: true, force: true });
        }
      }
    } catch {
      // 删不掉不影响新档案
    }
  }
}
