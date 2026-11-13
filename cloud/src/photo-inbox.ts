/**
 * 拍照收件箱：把「请设备拍一张」变成一次可等待、可超时、可取消的请求。
 *
 * 两类使用者：
 * - 工具口 `POST /take_photo` / `POST /take_photo_hq`：等图，返回 {@link PhotoOutcome}
 * - 设备自己按快门（SPP 低清直出）：没人等就落到 {@link deliver} 返回 false，
 *   由调用方当成一轮识图
 *
 * <h2>为什么要分两段超时</h2>
 *
 * 高清照多了「人」这一环：程序化快门不写相册（`实测`），只能请用户
 * 按一下。于是时间分成两段，各有各的失败语义：
 *
 * ```text
 * request('hq') ──发 take_photo_hq──▶ ARMING（等 hq_armed，15 s）
 *   hq_armed ─────────────────────▶ WAITING（等用户按快门，25 s）
 *   hq_failed / 超时 ──────────────▶ 立刻失败，别让云端干等到自己的总超时
 * ```
 *
 * 手机自己在 20 s 就放弃并上报 `no_shutter`，这边的 25 s 只是「手机也没消息」的兜底。
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

export type PhotoKind = 'spp' | 'hq';

/** 失败原因。`cancelled` 表示用户自己取消了，调用方不该再说话。 */
export type PhotoFailReason =
  | 'busy'
  | 'no_arm'
  | 'no_shutter'
  | 'p2p'
  | 'join'
  | 'connect'
  | 'arm'
  | 'download'
  | 'decode'
  | 'send'
  | 'cancelled'
  | 'timeout';

export type PhotoOutcome =
  | { readonly ok: true; readonly jpeg: Buffer }
  | { readonly ok: false; readonly reason: PhotoFailReason };

/**
 * 高清照两段超时的**默认值**（毫秒），单一来源。
 *
 * - 手机侧建组+入组+连相册实测 5.4 s，留三倍余量
 * - 就绪后等用户按快门：手机自己 20 s 就放弃，这里留 5 s 兜底
 *
 * `main.ts` 读环境变量时用它当默认值，`aliyun-session.ts` 兜底时也用它 ——
 * 以前这两处各写一个 `15_000` / `25_000`，改一个就会不一致。
 */
export const DEFAULT_HQ_ARM_TIMEOUT_MS = 15_000;
export const DEFAULT_HQ_SHUTTER_TIMEOUT_MS = 25_000;

export interface PhotoInboxOptions {
  /** 落盘目录；空串不落盘（默认） */
  readonly dir: string;
  /** `spp` 的总超时（低清直出，1–3 s 就有） */
  readonly timeoutMs: number;
  /** `hq`：发指令到手机回报「相册已通」的上限（实测建组+入组+连接约 5.4 s） */
  readonly armTimeoutMs: number;
  /** `hq`：就绪之后等用户按快门的上限（手机侧 20 s 放弃，这边留 5 s 余量） */
  readonly shutterTimeoutMs: number;
  readonly log: (line: string) => void;
  /** 真正下发 `take_photo` / `take_photo_hq` 的地方（连接层动作） */
  readonly sendTakePhoto: (kind: PhotoKind) => void;
  /** 下发 `hq_cancel`：用户改主意了，别继续开着 P2P 等 */
  readonly sendCancel?: () => void;
}

interface Waiter {
  readonly kind: PhotoKind;
  resolve: (outcome: PhotoOutcome) => void;
  timer: NodeJS.Timeout;
  /** `hq` 已经回报过就绪（第一段超时已换成第二段） */
  armed: boolean;
}

export class PhotoInbox {
  private readonly options: PhotoInboxOptions;
  private waiter: Waiter | null = null;

  constructor(options: PhotoInboxOptions) {
    this.options = options;
  }

  get isWaiting(): boolean {
    return this.waiter !== null;
  }

  /** 正在等哪一类图；没人等返回 null */
  get waitingKind(): PhotoKind | null {
    return this.waiter?.kind ?? null;
  }

  /**
   * 等一张图。
   *
   * 已经有请求在等时立刻返回 `busy`（不排队，避免两次请求抢一张图）。
   *
   * @param kind 走哪条采集路径；`hq` 要手机开一次 P2P 拉原图，且需要用户按快门
   */
  request(kind: PhotoKind = 'spp'): Promise<PhotoOutcome> {
    if (this.waiter) {
      this.options.log('  拍照    ⚠ 已有请求在等图，本次拒绝（busy）');
      return Promise.resolve({ ok: false, reason: 'busy' });
    }
    this.options.sendTakePhoto(kind);
    this.options.log(
      kind === 'hq'
        ? '  拍照已下发高清指令（P2P 拉原图），等手机回报就绪…'
        : '  拍照已下发指令（spp），等设备回图…',
    );

    return new Promise<PhotoOutcome>((resolve) => {
      const timer = setTimeout(() => {
        if (this.waiter?.timer !== timer) {
          return;
        }
        const waiting = this.waiter;
        this.waiter = null;
        const reason: PhotoFailReason = waiting.kind === 'hq'
          ? (waiting.armed ? 'no_shutter' : 'no_arm')
          : 'timeout';
        this.options.log(`  拍照    ✗ ${reason}（本地超时）`);
        resolve({ ok: false, reason });
      }, kind === 'hq' ? this.options.armTimeoutMs : this.options.timeoutMs);

      this.waiter = { kind, resolve, timer, armed: kind !== 'hq' };
    });
  }

  /**
   * 手机回报「P2P 与相册都通了」。
   *
   * @param fresh 相机里此刻**已经**有一张基线外的新图（用户提前按了快门）
   * @returns 是否有人正在等高清图 —— 调用方据此决定要不要播「请按快门」
   */
  armed(fresh: boolean): boolean {
    const waiter = this.waiter;
    if (!waiter || waiter.kind !== 'hq') {
      this.options.log(`  拍照    （无人等待，忽略 hq_armed fresh=${fresh}）`);
      return false;
    }
    if (!waiter.armed) {
      clearTimeout(waiter.timer);
      waiter.armed = true;
      waiter.timer = setTimeout(() => {
        if (this.waiter?.timer !== waiter.timer) {
          return;
        }
        this.waiter = null;
        this.options.log('  拍照    ✗ 就绪后一直没有新照片（本地超时）');
        waiter.resolve({ ok: false, reason: 'no_shutter' });
      }, this.options.shutterTimeoutMs);
    }
    this.options.log(`  拍照手机就绪（fresh=${fresh}），等用户按快门…`);
    return !fresh;
  }

  /** 手机上报失败：立刻收尾，别让调用方干等到超时 */
  failed(reason: PhotoFailReason): boolean {
    this.options.log(`  拍照    ✗ 手机上报失败：${reason}`);
    return this.settle({ ok: false, reason });
  }

  /** 取消（用户改主意 / 会话收尾）：下发 `hq_cancel`，让手机把 P2P 解散 */
  cancel(why: string): void {
    if (this.waiter === null) {
      return;
    }
    this.options.log(`  拍照取消（${why}）`);
    this.options.sendCancel?.();
    this.settle({ ok: false, reason: 'cancelled' });
  }

  /**
   * 收到一张图。
   *
   * @param kind 这张图从哪条路径来（手机在 `hq_image` 公告之后发的才算高清）
   * @returns true 表示已消化（交付或有意丢弃）；false 表示没人等、也不是高清图，
   *          调用方可以当成「看一眼」起一轮
   */
  deliver(kind: PhotoKind, jpeg: Buffer): boolean {
    const waiter = this.waiter;
    if (!waiter) {
      if (kind === 'hq') {
        // 迟到的清图：取消之后才到的原图，起一轮识图只会让用户莫名其妙
        this.options.log('  拍照    （无人等待，丢弃迟到的高清图）');
        return true;
      }
      this.persist(kind, jpeg);
      return false;
    }
    if (waiter.kind !== kind) {
      // 高清等待期里 SPP 的低清回图：交付它就等于用 17 KB 的糊图冒充原图（实测发生过）
      this.options.log(`  拍照    （等 ${waiter.kind} 时收到 ${kind} 图 ${jpeg.length} B，丢弃）`);
      return true;
    }
    this.persist(kind, jpeg);
    this.settle({ ok: true, jpeg });
    return true;
  }

  /** 连接结束时清干净：否则定时器会拖住进程 */
  dispose(): void {
    this.settle({ ok: false, reason: 'cancelled' });
  }

  private settle(outcome: PhotoOutcome): boolean {
    const waiter = this.waiter;
    if (!waiter) {
      return false;
    }
    this.waiter = null;
    clearTimeout(waiter.timer);
    waiter.resolve(outcome);
    return true;
  }

  private persist(kind: PhotoKind, jpeg: Buffer): void {
    const { dir, log } = this.options;
    if (!dir) {
      return;
    }
    try {
      mkdirSync(dir, { recursive: true });
      const file = join(dir, `photo_${kind}_${Date.now()}.jpg`);
      writeFileSync(file, jpeg);
      log(`  图片    ${jpeg.length} B  ${file}`);
    } catch (error) {
      log(`  ✗ 图片落盘失败: ${(error as Error).message}`);
    }
  }
}
