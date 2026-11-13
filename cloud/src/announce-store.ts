/**
 * 待补播队列：后台任务完成时**没有连接**，就把那句话存下来，等下次连上再播。
 *
 * <h2>为什么需要它</h2>
 *
 * dsh 的后台作业结算后会自己开一轮汇报（见 `spontaneous.ts`）。但如果用户已经把手机
 * 放下、连接断了，那一轮就没有出口 —— 以前是直接丢弃。任务本身还在 dsh 会话里
 * （下次追问能得到），但"主动告诉用户一声"这件事就丢了。
 *
 * <h2>语义</h2>
 *
 * - **至少一次**：`drain()` 会把文件删掉，崩溃在播放中途会导致重复播一次 ——
 *   对语音提示来说可以接受（重复一句比丢一句好）。
 * - **有上限**：只保留最近 `maxItems` 条，防止长时间离线堆成山。
 * - **同步写**：连接断开时进程可能马上退出，异步写会丢。
 */
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

export interface AnnounceStoreOptions {
  /** 队列文件路径；空串 = 不持久化（回到"丢了就丢了"） */
  readonly file: string;
  /** 上限条数 */
  readonly maxItems?: number;
  readonly log?: (line: string) => void;
}

/**
 * 默认上限条数。
 *
 * 导出而不是各写一份：`main.ts` 读环境变量时要用同一个默认值，
 * 否则「不配环境变量」和「显式配 20」会走到两个不同的数字上。
 */
export const DEFAULT_ANNOUNCE_MAX = 20;

interface Entry {
  readonly t: number;
  readonly text: string;
}

export class AnnounceStore {
  private readonly file: string;
  private readonly maxItems: number;
  private readonly log: (line: string) => void;

  /** 路径为空时返回 null（调用方按"没有补播能力"处理） */
  static open(options: AnnounceStoreOptions): AnnounceStore | null {
    if (!options.file) {
      return null;
    }
    try {
      mkdirSync(dirname(options.file), { recursive: true });
    } catch {
      // 目录建不出来不影响主流程：push 时会再报
    }
    return new AnnounceStore(options);
  }

  private constructor(options: AnnounceStoreOptions) {
    this.file = options.file;
    this.maxItems = options.maxItems ?? DEFAULT_ANNOUNCE_MAX;
    this.log = options.log ?? (() => {});
  }

  /** 存一条；返回当前积压条数。写失败只记日志，不影响通话。 */
  push(text: string): number {
    const items = this.read();
    items.push({ t: Date.now(), text });
    while (items.length > this.maxItems) {
      items.shift();
    }
    try {
      writeFileSync(this.file, items.map((e) => JSON.stringify(e)).join('\n') + '\n');
    } catch (error) {
      this.log(`  ✗ 待补播写入失败：${(error as Error).message}`);
      return items.length;
    }
    return items.length;
  }

  /** 取走全部并清空（至少一次：清不掉就下次再播一次）。 */
  drain(): string[] {
    const items = this.read();
    if (items.length === 0) {
      return [];
    }
    try {
      rmSync(this.file, { force: true });
    } catch (error) {
      this.log(`  ✗ 待补播清理失败（下次可能重复播）：${(error as Error).message}`);
    }
    return items.map((e) => e.text);
  }

  /** 当前积压条数（只读） */
  get size(): number {
    return this.read().length;
  }

  private read(): Entry[] {
    if (!existsSync(this.file)) {
      return [];
    }
    const out: Entry[] = [];
    try {
      for (const line of readFileSync(this.file, 'utf8').split('\n')) {
        const s = line.trim();
        if (!s) {
          continue;
        }
        try {
          const e = JSON.parse(s) as Partial<Entry>;
          if (typeof e.text === 'string' && e.text) {
            out.push({ t: typeof e.t === 'number' ? e.t : 0, text: e.text });
          }
        } catch {
          // 坏行直接跳过：一句提示不值得让整个队列失效
        }
      }
    } catch (error) {
      this.log(`  ✗ 待补播读取失败：${(error as Error).message}`);
    }
    return out;
  }
}
