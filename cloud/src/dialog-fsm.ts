/**
 * 一次语音会话的对话状态机。
 *
 * <h2>为什么要有它</h2>
 *
 * 早先这里是五个互相牵制的布尔量：`speaking` / `playing` / `bargeIn` /
 * `queueGen` / `endRequested`。它们的合法组合从来没被写下来过，于是每修一个
 * bug 就多一个特判，又引出下一个 bug：
 *
 * - `playing` 只在 TTS 生成的那 1 s 内为真，播放期 20 s 里插话不触发打断
 *   → 加一个「播放等待也要置 playing」的特判
 * - 打断只停声音不清队列，用户问到第三句时 agent 还在答第一句
 *   → 再加一个 `queueGen`
 * - `bargeIn` 需要在每轮开头手工复位，漏一次就整轮被当成已打断
 *
 * 这些都不是独立缺陷，是**没有状态定义**的症状。
 *
 * <h2>状态与转移</h2>
 *
 * ```text
 *   listening ──句子定稿──▶ thinking ──首块音频写出──▶ speaking
 *        ▲                    │                          │
 *        │                    │ audioDone(t)             │ audioDone(t)
 *        │                    │ （无音频 / 出错）         │ endAfterSpeech=false
 *        └────────────────────┴──────────────────────────┘
 *
 *   speaking  ──audioDone(t) 且 endAfterSpeech=true──▶ ending ──收尾完成──▶ closed
 *   listening ──requestEnd()（不在播，没有告别语要等）──▶ ending
 *   speaking  ──bargeIn()────────────────────────────▶ listening（代次 +1）
 *   任意状态  ──断连 / 致命错误─────────────────────▶ closed
 *
 *   listening ──说「看清细节」──▶ awaiting_shot ──拿到高清图──▶ thinking
 *                                     │  ▲
 *                                     │  └── 手机回报「相册就绪」（顺带播提示语，状态不变）
 *                                     └───── 用户改主意 / 收尾 ──▶ listening / ending
 * ```
 *
 * `audioDone` 是**事件**不是状态：它在 `thinking` 或 `speaking` 内部完成判定，
 * 落点只取决于「本轮请求过结束没有」。
 *
 * 关键约束（都是真机日志换来的）：
 *
 * 1. **只有 `speaking` 能被打断**。`thinking` 期间用户说话不算插话 ——
 *    否则 TTS 首块到达前那 ~1 s 里的一句正常话会打断掉还没出声的回复
 *    （实测：回放 0 字节被中断）。
 * 2. **`speaking` 覆盖整段播放**，直到音频真的播完才离开。不是「TTS 生成结束」
 *    就结束（实测：13 轮只成功打断 1 次）。
 * 3. **插话即作废排队轮次**。一轮 = dsh 2–5 s + 播放 5–20 s，用户在此期间说的
 *    每句都会排队；实测积压到 40 s，用户已经问到「退出」，agent 还在回答
 *    36 秒前的「拖一下」。
 *
 * <h2>票据（ticket）</h2>
 *
 * 票据是 `{no, gen}` 两段，各解决一个独立问题：
 *
 * - `gen`（代次）：**插话**时 +1，用来作废排队中还没开跑的轮次。
 * - `no`（轮次号）：每开一轮 +1，用来**丢弃上一轮迟到的回调**。
 *
 * 为何不能只用一个：相邻两轮之间若没发生插话，代次相同，那么上一轮迟到的
 * `audioDone` 就会把新一轮从 `speaking` 提前切回 `listening`。所以
 * `audioStarted` / `audioDone` 都要求 `no` 等于当前轮次。
 *
 * 这两道都是**运行时单点判定**，不是类型系统的保证。若要类型级保证，
 * 得把状态做成可辨识联合、让 `bargeIn` 只存在于 Speaking 类型上；
 * 本轮没这么改。
 *
 * <h2>明确定义的行为（以前没写下来，容易各读一套）</h2>
 *
 * - **`thinking` 期间用户说话**：不算插话。这句话走正常的句子合并，成为
 *   **排在当前轮之后的新一轮**。此时回复还没出声，打断它等于丢掉一个已经
 *   算好的回答；而直接丢掉用户刚说的话更糟。
 * - **告别语可被打断**：`requestEnd()` 只置标志，状态保持 `speaking` 直到播完，
 *   所以告别语期间插话仍然生效（取消结束、回 `listening`）。
 * - **`audioDone` 是服务端估算**（下发字节数 ÷ 采样率 + 余量），不是客户端回执。
 *   所以 `speaking` 结束得比真实出声略晚，插话窗口也略宽 —— 偏保守方向。
 * - **插话不取消上游 dsh 请求**（无法中断），只是停止消费其输出；
 *   对话历史里那条助手回复仍是完整文本，不截断。已知限制。
 *
 * <h2>`awaiting_shot`：等用户按快门</h2>
 *
 * 高清照必须由**人按下快门**才会落盘（程序化快门不写相册，见
 * `实测`），所以「等一张照片」是一段真实存在的、几十秒的对话时间。
 * 把它塞进 `thinking` 会得到三个后果，都实测发生过：用户在等的时候说话被排队、
 * 收尾命令取消不了它、指示灯一直停在「思考」。
 *
 * 规矩（和第 1 条约束一致：**能不能打断由状态定，不由特判定**）：
 *
 * - 它属于 **listening 一族**：用户说话天经地义，所以不参与 `bargeIn`
 * - 用户在 `awaiting_shot` 里说话 = **改主意**：取消拍照，新句子重新判意图
 * - 取消**不靠票据**：这期间代次不会变，`isStale` 永远是 false。取消只走
 *   `utterance` / `requestEnd` / `closed` 三条边 —— 别在这里加 stale 判定
 * - 提示语（"请按一下快门"）不改状态：它是 `awaiting_shot` 内部的旁白
 */

export type DialogState = 'listening' | 'awaiting_shot' | 'thinking' | 'speaking' | 'ending' | 'closed';

/**
 * 一轮的票据。
 *
 * - `no`：轮次号，判断「这还是不是当前这一轮」
 * - `gen`：代次，判断「这一轮是否已被插话作废」
 */
export interface TurnTicket {
  readonly no: number;
  readonly gen: number;
}

export class DialogFsm {
  private _state: DialogState = 'listening';
  private _turn = 0;
  private _gen = 0;
  /** 模型已经要求结束：等这段音频播完再走 `ending` */
  private _endAfterSpeech = false;
  /**
   * 日志出口。
   *
   * 注意：**不能**写成构造函数参数属性 `constructor(private readonly log: ...)`。
   * Node 22 的类型擦除（strip-types）不支持参数属性，会抛
   * `ERR_UNSUPPORTED_TYPESCRIPT_SYNTAX` —— 编译期 tsc 发现不了，
   * 只有真正跑起来才炸。
   */
  private readonly log: (line: string) => void;

  constructor(log: (line: string) => void) {
    this.log = log;
  }

  get state(): DialogState {
    return this._state;
  }

  /** 本轮编号，从 1 开始 */
  get turn(): number {
    return this._turn;
  }

  get gen(): number {
    return this._gen;
  }

  /**
   * 本轮是否已被标记为「播完就结束」。
   *
   * 只读观测点，供形式化验证与诊断使用 —— 它不参与任何外部判定，
   * 暴露它是为了让验证脚本能识别「同状态但标志不同」的配置，
   * 否则会把两个行为不同的配置误当成同一个。
   */
  get endAfterSpeech(): boolean {
    return this._endAfterSpeech;
  }

  /** 当前代次的票据。入队任务应当持有它，出队时用 {@link isStale} 校验。 */
  ticket(): TurnTicket {
    return { no: this._turn, gen: this._gen };
  }

  /** 该票据的轮次是否已被插话作废（代次变了） */
  isStale(t: TurnTicket): boolean {
    return t.gen !== this._gen;
  }

  /** 该票据是否仍属于当前这一轮（排除上一轮迟到的回调） */
  isCurrent(t: TurnTicket): boolean {
    return t.no === this._turn;
  }

  /**
   * 开一轮：`listening | awaiting_shot → thinking`。
   *
   * `awaiting_shot` 是合法前驱：高清照到手或明确失败之后，那一轮才真正开跑。
   *
   * @returns 该轮票据；已在收尾/已关闭时返回 `null`，调用方应直接放弃这一轮
   */
  beginTurn(): TurnTicket | null {
    if (this._state === 'ending' || this._state === 'closed') {
      this.log(`  状态    ✗ 拒绝开新一轮（当前 ${this._state}）`);
      return null;
    }
    if (this._state !== 'listening' && this._state !== 'awaiting_shot') {
      // 串行队列保证不会到这里；真到了就是调度 bug，必须看见
      this.log(`  状态    ⚠ 在 ${this._state} 时开新一轮，按 thinking 处理`);
    }
    this._turn += 1;
    this._endAfterSpeech = false;
    this.transition('thinking', `第 ${this._turn} 轮`);
    return this.ticket();
  }

  /**
   * 进去等一张高清照：`listening → awaiting_shot`。
   *
   * @returns 票据；不在 `listening` 时返回 `null`（调用方按普通轮次处理）
   */
  beginAwaitShot(): TurnTicket | null {
    if (this._state !== 'listening') {
      this.log(`  状态    ⚠ 在 ${this._state} 时想等高清照，忽略`);
      return null;
    }
    this.transition('awaiting_shot', '等一张高清照');
    return this.ticket();
  }

  /** 不拍了（用户改主意）：`awaiting_shot → listening`。代次不变。 */
  abortAwaitShot(why: string): void {
    if (this._state !== 'awaiting_shot') {
      return;
    }
    this.transition('listening', `放弃拍照（${why}）`);
  }

  /** 首块音频真正写出：`thinking → speaking` */
  audioStarted(t: TurnTicket): void {
    if (this.isStale(t) || !this.isCurrent(t)) {
      return;
    }
    if (this._state === 'thinking') {
      this.transition('speaking', '首块音频已下发');
    }
  }

  /**
   * 用户插话。**只有 `speaking` 成立**。
   *
   * 成立时代次 +1，从而作废所有还在排队的轮次。
   *
   * @returns 是否真的构成插话
   */
  bargeIn(why: string): boolean {
    if (this._state !== 'speaking') {
      return false;
    }
    this._gen += 1;
    this._endAfterSpeech = false;
    this.transition('listening', `插话「${why}」→ 代次 ${this._gen}`);
    return true;
  }

  /**
   * 本轮音频结束（自然播完、无音频、或出错）。
   *
   * 这是**事件**：从 `thinking` 或 `speaking` 出发，落点由 `endAfterSpeech` 决定。
   * 上一轮迟到的调用会被 `no` 挡掉。
   */
  audioDone(t: TurnTicket): void {
    if (this.isStale(t) || !this.isCurrent(t)) {
      return;
    }
    this.transition(this._endAfterSpeech ? 'ending' : 'listening', '本轮音频结束');
  }

  /**
   * 模型要求结束。
   *
   * - 在 `speaking`：只置标志，等这段播完再进 `ending`（告别语不截断）
   * - 在 `thinking`：同样只置标志，等本轮音频结束（可能还有告别语）
   * - 在 `listening` / `awaiting_shot`：没有音频要等，直接进 `ending`
   *   （`awaiting_shot` 的拍照请求由调用方在收到 `ending` 时一并取消）
   */
  requestEnd(): void {
    this._endAfterSpeech = true;
    if (this._state === 'listening' || this._state === 'awaiting_shot') {
      this.transition('ending', '模型要求结束（当前无音频）');
      return;
    }
    this.log(`  状态    ${this._state}（本轮音频结束后再收尾）`);
  }

  /** 连接收尾。任何状态都可以直接进 `closed`。 */
  close(): void {
    this.transition('closed', '连接结束');
  }

  private transition(to: DialogState, why: string): void {
    if (this._state === to) {
      return;
    }
    // closed 是吸收态：连接已经断了，任何迟到的回调都不得把状态拉回去。
    // 反例（由 tests/fsm-verify.ts 的 P2 抓到）：close 之后本轮 finally 里
    // 的 audioDone 会把 closed 拉回 listening。
    if (this._state === 'closed') {
      this.log(`  状态    ⚠ 已 closed，忽略转移 ${to}（${why}）`);
      return;
    }
    this.log(`  状态    ${this._state} → ${to}   (${why})`);
    this._state = to;
  }
}
