/**
 * 对话层的**决策**：给定事件，产出「接下来做什么」。
 *
 * 这里没有 socket、没有 ffmpeg、没有定时器 —— 副作用都以 `CoreEffect` 返回，
 * 由宿主执行。抽出来的理由：最容易出错的是判断本身（旁听反馈要不要成轮、
 * 插话算不算数、识图轮从哪来），而这些用单测就能钉住，不必起连接。
 */
import { isBackchannelOnly } from './backchannel.ts';
import { DialogFsm, type DialogState, type TurnTicket } from './dialog-fsm.ts';
import { wantsHighRes } from './hq-intent.ts';
import type { ImageAttachment } from './session.ts';
import { isWakeWordOnly } from './wake-word.ts';

export type CoreEvent =
  /** STT 出了一句（还没合并） */
  | { readonly type: 'sentence'; readonly text: string }
  /** 一句话合并定稿（判停窗口已关） */
  | { readonly type: 'utterance'; readonly text: string }
  /** STT 的中间结果（插话判据） */
  | { readonly type: 'partial'; readonly text: string }
  /** 设备回了一张图，且没有拍照请求在等它 */
  | { readonly type: 'image'; readonly jpeg: Buffer }
  /**
   * 高清照有了结果（成功或失败都算）。
   *
   * 由宿主在 `photos.request('hq')` resolve 之后派发 —— 状态机在 `awaiting_shot`
   * 期间什么都做不了，必须靠这个事件才能回到 `thinking`。
   */
  | { readonly type: 'shotResult'; readonly ok: boolean; readonly jpeg?: Buffer; readonly reason?: string }
  /** 一轮跑完（音频下发结束） */
  | { readonly type: 'turnFinished' }
  /** 外部（工具口 / 模型）要求收尾 */
  | { readonly type: 'endRequested' }
  | { readonly type: 'closed' };

export type CoreEffect =
  /** 交给人声合并器 */
  | { readonly type: 'assemble'; readonly text: string }
  | {
    readonly type: 'turn';
    readonly text: string;
    readonly image?: ImageAttachment;
    readonly ticket: TurnTicket;
  }
  /**
   * 去要一张高清照：`listening → awaiting_shot`。
   *
   * 宿主该做的：`photos.request('hq')`，拿到结果后派发 `shotResult`。
   */
  | { readonly type: 'awaitShot'; readonly text: string; readonly ticket: TurnTicket }
  /** 取消正在等的高清照（下发 `Control: hq_cancel`，让手机把 P2P 解散） */
  | { readonly type: 'cancel_shot' }
  /** 下发 `Control: stop`，停止播放 */
  | { readonly type: 'stop' }
  /** 下发 `Control: stop_capture`，让眼镜立刻停止采集（模型调了退出工具） */
  | { readonly type: 'stop_capture' }
  /** 挂断 */
  | { readonly type: 'end' }
  /** 有意丢弃（唤醒词 / 旁听反馈），只为可观测 */
  | { readonly type: 'drop'; readonly why: string };

/**
 * 没拿到高清照时追加给模型的话。
 *
 * `no_shutter` 是最常见的一种，而且**不是故障**：程序化快门不写相册，只能人按
 * （`实测`）。所以不能让模型瞎猜画面内容，得明确告诉它去要。
 */
function shotFailureHint(reason: string): string {
  switch (reason) {
    case 'no_shutter':
      return '没能拿到高清图：相册里没有新照片。请让用户按一下眼镜上的快门，然后再说一次；'
        + '不要凭空描述画质或内容。';
    case 'no_arm':
      return '没能拿到高清图：手机没有响应拍照请求（可能没连上云端）。'
        + '请如实说明拿不到照片，不要凭空描述。';
    case 'p2p':
    case 'join':
    case 'connect':
    case 'arm':
      return '没能拿到高清图：手机和眼镜的相册通道没建起来（P2P/入组/连接失败或超时）。'
        + '请让用户稍后再试，不要凭空描述。';
    case 'download':
    case 'decode':
      return '照片拿到了但读不出来（下载或解码失败）。请让用户再说一次，不要凭空描述。';
    case 'send':
      return '照片拍到了但没能传上来。请让用户再说一次，不要凭空描述。';
    default:
      return '没能拿到高清图。请如实说明，不要凭空描述画面内容。';
  }
}

export interface SessionCoreOptions {
  /** 没人等图时的默认提问 */
  readonly imageQuestion: string;
  readonly log: (line: string) => void;
}

export class SessionCore {
  /** 传给 TurnRunner：一轮的生命周期仍由它驱动 */
  readonly fsm: DialogFsm;

  private readonly options: SessionCoreOptions;

  /**
   * 正在等高清照的那句话。
   *
   * 放在这里而不是宿主里：它是**状态**（`awaiting_shot` 期间有效），要随
   * `abortAwaitShot` / `closed` 一起清掉，放宿主一定会漏。
   */
  private pendingShot: { text: string; ticket: TurnTicket } | null = null;

  constructor(options: SessionCoreOptions) {
    this.options = options;
    this.fsm = new DialogFsm(options.log);
  }

  get phase(): DialogState {
    return this.fsm.state;
  }

  get turn(): number {
    return this.fsm.turn;
  }

  dispatch(event: CoreEvent): CoreEffect[] {
    switch (event.type) {
      case 'sentence':
        if (isWakeWordOnly(event.text)) {
          this.options.log('  → 唤醒词，丢弃（不算一轮）');
          return [{ type: 'drop', why: 'wake-word' }];
        }
        return [{ type: 'assemble', text: event.text }];

      case 'utterance': {
        /** 等快门期间用户又说话 = 改主意：先取消拍照，再按新句子重新判 */
        const effects: CoreEffect[] = [];
        if (this.fsm.state === 'awaiting_shot') {
          this.options.log('  （等快门时用户又说话 → 取消拍照，新句子重新判）');
          this.pendingShot = null;
          this.fsm.abortAwaitShot('用户改主意');
          // `stop` 是把已经在播的旁白清掉（手机侧 flush）；`cancel_shot` 是解散 P2P
          effects.push({ type: 'cancel_shot' }, { type: 'stop' });
        }
        // 旁听反馈不单独成轮。实测：「嗯嗯，我知道了我知道了。」曾触发一次完整
        // agent 调用，回了句「行，那有需要再喊我」—— 像逐客令，用户下一句就是
        // 「哎，别别别别退。」。
        if (isBackchannelOnly(event.text)) {
          this.options.log('  （旁听反馈，不单独成轮）');
          return [...effects, { type: 'drop', why: 'backchannel' }];
        }
        // 已经决定收尾（模型调了退出工具）：麦克风虽已停，但排队中的识别结果
        // 还会到。此时再开一轮会和「告别语播完就挂断」打架。
        if (this.fsm.endAfterSpeech) {
          this.options.log('  （丢弃：已在收尾，不再开新一轮）');
          return [...effects, { type: 'drop', why: 'ending' }];
        }
        // 用户**说**要看清细节时，不等模型自己选工具：直接把高清图拍好塞给它。
        // 实测 deepseek-flash 不会稳定地按工具描述选 `take_photo_hq`（同一句话
        // 三次里两次挑了低清），所以「要不要高清」由**用户的话**决定。
        if (wantsHighRes(event.text)) {
          const shotTicket = this.fsm.beginAwaitShot();
          if (shotTicket !== null) {
            this.pendingShot = { text: event.text, ticket: shotTicket };
            this.options.log('  拍照用户要求看清细节 → 先拍高清（P2P，需要按快门）');
            return [...effects, { type: 'awaitShot', text: event.text, ticket: shotTicket }];
          }
        }
        return [...effects, { type: 'turn', text: event.text, ticket: this.fsm.ticket() }];
      }

      case 'shotResult': {
        const pending = this.pendingShot;
        this.pendingShot = null;
        if (pending === null) {
          this.options.log('  （拍照结果已经不需要了，丢弃）');
          return [{ type: 'drop', why: 'shot-stale' }];
        }
        if (event.ok && event.jpeg !== undefined) {
          return [{
            type: 'turn',
            text: `${pending.text}\n（已附一张高清晰度照片，直接根据它回答，不要再调用拍照工具。）`,
            image: { mimeType: 'image/jpeg', data: event.jpeg.toString('base64') },
            ticket: this.fsm.ticket(),
          }];
        }
        const reason = event.reason ?? 'timeout';
        if (reason === 'cancelled') {
          // 用户自己取消的：不要凭空生成一轮回答
          return [{ type: 'drop', why: 'shot-cancelled' }];
        }
        this.options.log(`  拍照    ✗ 没拿到高清图（${reason}），本轮按文本继续`);
        return [{
          type: 'turn',
          text: `${pending.text}\n（${shotFailureHint(reason)}）`,
          ticket: this.fsm.ticket(),
        }];
      }

      case 'partial': {
        // 插话判据用「服务端真的解出了字」，不用能量峰值 ——
        // 噪声能骗过峰值，骗不过识别器。能不能打断由状态机定（只有 speaking 算）。
        const bare = event.text.replace(/[\s。.,，!！?？、~～]/g, '');
        if (bare.length < 2) {
          return [];
        }
        if (!this.fsm.bargeIn(event.text)) {
          return [];
        }
        return [{ type: 'stop' }];
      }

      case 'image':
        // 等高清图的时候收到 SPP 低清回图（用户按快门时相机顺带回的）：那不是我们要的
        // 那张，别用它另起一轮 —— 否则「等快门」和「识图轮」会同时活着。
        if (this.fsm.state === 'awaiting_shot') {
          this.options.log('  （等高清图期间收到低清回图，丢弃）');
          return [{ type: 'drop', why: 'spp-during-shot' }];
        }
        return [{
          type: 'turn',
          text: this.options.imageQuestion,
          image: { mimeType: 'image/jpeg', data: event.jpeg.toString('base64') },
          ticket: this.fsm.ticket(),
        }];

      case 'turnFinished':
        if (this.fsm.state === 'ending') {
          this.options.log('\n--- 会话结束（模型判定）---');
          return [{ type: 'end' }];
        }
        return [];

      case 'endRequested': {
        // 收尾时如果还在等快门，得把这个请求一起取消 —— 不然手机侧 P2P 会挂到
        // 自己的 20 s 超时才散，用户已经听过告别语了灯还亮着。
        const cancelling = this.fsm.state === 'awaiting_shot';
        if (cancelling) {
          this.pendingShot = null;
          this.options.log('  拍照收尾 → 取消还没到手的高清照');
        }
        this.fsm.requestEnd();
        // 模型决定收尾的那一刻，眼镜就该停止采集 —— 不能等告别语播完。
        // `endVoiceSession()`（Voice 六字段全 0）实测能让设备立刻停流（）。
        this.options.log('  采集模型要求结束 → 通知设备停止采集');
        const effects: CoreEffect[] = cancelling
          ? [{ type: 'cancel_shot' }, { type: 'stop' }]
          : [];
        effects.push({ type: 'stop_capture' });
        if (this.fsm.state === 'ending') {
          effects.push({ type: 'end' });
        }
        return effects;
      }

      case 'closed':
        this.pendingShot = null;
        this.fsm.close();
        return [];
    }
  }
}
