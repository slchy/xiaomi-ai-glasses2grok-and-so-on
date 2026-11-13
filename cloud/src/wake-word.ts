/**
 * 唤醒词过滤。
 *
 * 唤醒词烧在固件固件里改不了，但它产生的**事件**我们能接住。
 * ASR 会把「小爱同学。」本身也识别成一个完整句子，Smart Turn 对它给 0.957
 * —— 那确实是一句完整的话，模型治不了，只能靠这条领域规则。
 *
 * 不属于「理解」范畴，所以留在本地做定量匹配，不交给 agent 判断：
 * 它只是把已知的机械事实过滤掉，不产生任何语义推断。
 */

/** 归一化（去标点与空白）后整句匹配 */
const WAKE_WORDS: ReadonlySet<string> = new Set([
  '小爱同学',
  '小爱',
  '小爱小爱',
]);

const PUNCTUATION = /[\s。.,，!！?？、~～]/g;

/** 该句是否只是唤醒词（不算一轮对话）。 */
export function isWakeWordOnly(text: string): boolean {
  return WAKE_WORDS.has(text.replace(PUNCTUATION, ''));
}
