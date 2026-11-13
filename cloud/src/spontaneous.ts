/**
 * 识别**没人催的轮次**。
 *
 * dsh 的后台作业（`bash {run_in_background:true}`）结算后，会**自己开一轮**把结果说出来
 * —— 没有 `session/prompt`、没有调用方在等（实测：job 2.8 s 登记，turn 3.6 s 结束，
 * job 42.9 s 结算后 `status=running` + `turn/start` 又重新开始）。
 *
 * 我们的 SDK 封装只在 prompt 期间挂 `onIdle`，所以这种自发轮次以前会被**静默丢掉**：
 * 文本写进了 `assistantText`，却没有等待者来取。这个类就是补那一步判定。
 *
 * 判定规则只有两条：
 * - `running` 且**没有** prompt 等待者 → 自发轮次开始（清空上一轮的残留文本）
 * - `idle` 且正处于自发轮次 → 结算，把文本交出去
 *
 * 为什么不能用"有没有 `onIdle`"直接推断：prompt 期间 `running`/`idle` 也会来，
 * 而且自发轮次可能在 prompt 之后紧接着发生，必须显式记住"当前这一轮是谁的"。
 */
export class SpontaneousTurn {
  private active = false;

  /**
   * 喂一条 `session.status`。
   *
   * @param status 原始状态串（`running` / `idle` / 其它）
   * @param hasPromptWaiter 当前是否有 `session/prompt` 的等待者
   * @returns `'begin'` = 自发轮次开始（调用方应清空累积文本）；
   *          `'settle'` = 自发轮次结束（调用方应取走文本）；
   *          `null` = 与自发轮次无关
   */
  onStatus(status: string, hasPromptWaiter: boolean): 'begin' | 'settle' | null {
    if (status === 'running') {
      if (hasPromptWaiter) {
        // 这是我们自己催的那一轮，不是自发的
        this.active = false;
        return null;
      }
      this.active = true;
      return 'begin';
    }
    if (status === 'idle' && this.active) {
      this.active = false;
      return 'settle';
    }
    return null;
  }

  get isActive(): boolean {
    return this.active;
  }

  /** 自己发 prompt 前调用：接下来那一轮的 idle 归 prompt 等待者，不算自发。 */
  reset(): void {
    this.active = false;
  }
}
