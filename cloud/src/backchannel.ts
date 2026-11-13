/**
 * 旁听反馈（backchannel）识别：判断一句话是不是纯粹的「嗯 / 知道了」这类
 * 应答噪声，从而不单独开一轮对话。
 *
 * <h2>为什么需要</h2>
 *
 * 实测 11 轮会话里出现过：
 *
 * ```text
 * 句 1  "嗯嗯，我知道了我知道了。"
 * agent "行，那有需要再喊我。"
 * ```
 *
 * 白烧一次 agent 调用，而且回了句**像逐客令**的话 —— 用户下一句立刻是
 * 「哎，别别别别退。」。这类词不是提问，回它比不回更糟。
 *
 * <h2>为什么用词表而不是交给 agent</h2>
 *
 * 这不是「理解」，是**已知的机械事实**：一串语气词不承载请求。
 * 交给 agent 判断的代价是每轮一次 1–5 s 的往返，而收益为零。
 * 与 `wake-word.ts` 同类：只过滤已知噪声，不做任何语义推断。
 *
 * <h2>刻意保守</h2>
 *
 * 只把**整句都是**语气词/应答词才算旁听反馈。以下都**不**算，照常进 agent：
 *
 * - 「好的」—— 可能是对上一个提问的确认，丢了会破坏确认流程
 * - 「嗯，拍照」—— 语气词后面还有实义内容
 * - 「明白了，那第二点呢」—— 有后续请求
 */
const PUNCTUATION = /[\s。.,，!！?？、~～…\-—]/g;

/**
 * 纯语气词 / 应答词。
 *
 * 只收**没有独立语义**的：语气词 + 「我明白了」这类纯回执。
 * 「好」「行」「对」不收 —— 它们可能是在应答上一个确认请求。
 */
const ACK_MARKERS: readonly string[] = [
  // 语气词（可叠用：嗯嗯嗯、啊啊）
  '嗯', '哦', '喔', '噢', '啊', '呃', '额', '唉', '诶', '哎',
  // 纯回执
  '我知道了', '知道了', '我明白了', '明白了', '懂了', '收到',
];

/**
 * 该句是否只是旁听反馈。
 *
 * 做法：去掉标点与空白后，反复剔除应答词，若什么都不剩就是旁听反馈。
 */
export function isBackchannelOnly(text: string): boolean {
  let rest = text.replace(PUNCTUATION, '');
  if (!rest) {
    return false;
  }

  // 反复剔除，直到不再变化（处理「嗯嗯」「知道了知道了」这类叠用）
  let changed = true;
  while (changed) {
    changed = false;
    for (const marker of ACK_MARKERS) {
      if (rest.includes(marker)) {
        rest = rest.split(marker).join('');
        changed = true;
      }
    }
  }
  return rest.length === 0;
}
