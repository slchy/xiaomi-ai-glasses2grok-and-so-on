/**
 * 情绪标签：agent 在回复开头写一个 `[开心]` 之类的标签，TTS 据此换语调。
 *
 * 为什么要这一层：MiMo 的 TTS 是聊天式的（风格进 `user` 消息），一个固定提示词
 * 只能给出一种语气。让**模型自己**给每句标情绪，再把标签翻成风格提示词，语调就
 * 跟着内容走了 —— 而不是永远"平静"。
 *
 * 分工：
 * - 本模块只做**纯解析与拼装**（标签 → 规范化情绪 key → 风格串），不碰网络
 * - agent 负责产出标签，TTS provider 负责用风格串合成
 *
 * 标签缺失 / 不认识时一律回落 `calm`：宁可语调普通，也不要因为模型漏写标签就报错。
 */

/** 五个情绪 + 各自的默认风格（可被 `MIMO_TTS_STYLE_*` 覆盖） */
export const EMOTIONS = {
  happy: '语调上扬，节奏轻快，带笑意。',
  proud: '尾音拖长一点，带小骄傲，像在等着被夸。',
  calm: '语速放稳，干脆利落。',
  worried: '语速放慢，音量放低，声音柔一点。',
  sulk: '音调略低，带一点鼻音和拖腔，但不哭腔。',
} as const;

export type Emotion = keyof typeof EMOTIONS;

/**
 * 情绪 key → 中文标签。
 *
 * **单一事实来源**：这份表同时被三处消费，别在别处再抄一遍
 * 1. 人设提示词里"从这五个里选一个"的枚举（`agent.ts`）
 * 2. 句中残留标签的清理正则（`cleanForSpeech`）
 * 3. 解析模型输出时的反查（`asEmotion` / `parseEmotion`）
 *
 * 以前这三处各写了一份中文标签，改一个名字就会有两处悄悄失效。
 */
export const EMOTION_LABELS: Record<Emotion, string> = {
  happy: '开心',
  proud: '得意',
  calm: '平静',
  worried: '担心',
  sulk: '委屈',
};

/** 标签 → key（由 {@link EMOTION_LABELS} 反推，不单独维护） */
const LABELS: Record<string, Emotion> = Object.fromEntries(
  Object.entries(EMOTION_LABELS).map(([key, label]) => [label, key as Emotion]),
);

/** 提示词里用的枚举串：`[开心] [得意] [平静] [担心] [委屈]` */
export function emotionTagList(): string {
  return Object.values(EMOTION_LABELS).map((label) => `[${label}]`).join(' ');
}

/**
 * 清掉文本里**任何位置**的情绪标签。
 *
 * 只给"兜底"用：正常情况下 @{link parseEmotion} 已经剥掉开头那个；模型偶尔会在
 * 句中再写一个，那些会被原样念出来（「方括号开心」）。
 */
export function stripEmotionTags(text: string): string {
  const labels = Object.values(EMOTION_LABELS).join('|');
  return text.replace(new RegExp(`[\\[【]\\s*(?:${labels})\\s*[\\]】]`, 'g'), '');
}

export const DEFAULT_EMOTION: Emotion = 'calm';

/**
 * 把外部传来的情绪字符串规范成 key。
 *
 * provider 拿到的是 `AgentReplyView.emotion`（可能来自别的 agent 实现、可能是
 * 中文标签、也可能根本不传），所以这里必须容错：认不出就当 `calm`。
 */
export function asEmotion(value: unknown): Emotion {
  if (typeof value !== 'string') {
    return DEFAULT_EMOTION;
  }
  const key = value.trim();
  if (key in EMOTIONS) {
    return key as Emotion;
  }
  return LABELS[key] ?? DEFAULT_EMOTION;
}

/**
 * 基础音色风格。**报数字和地名要放慢、咬字清楚**是有意的：语音助手念错的代价
 * 比念慢的代价高得多。
 */
export const BASE_STYLE =
  '十七八岁的少女音，明亮清甜，带一点奶气但不做作，语速中等偏快；'
  + '报数字和地名时放慢、咬字清楚，长时间听不累。';

const TAG = /^\s*[\[【]\s*([^\]】]{1,4})\s*[\]】]\s*/;

/**
 * 从回复开头取出情绪标签。
 *
 * 只认**开头**那一个：模型可能在正文里也写方括号，但那不该影响语调。
 * 解析不到就把原文原样返回、情绪用默认值 —— 绝不因为格式问题丢内容。
 */
export function parseEmotion(text: string): { emotion: Emotion; rest: string } {  const m = TAG.exec(text);
  if (!m) {
    return { emotion: DEFAULT_EMOTION, rest: text };
  }
  const label = m[1] ?? '';
  const emotion = LABELS[label] ?? DEFAULT_EMOTION;
  return { emotion, rest: text.slice(m[0].length) };
}

/**
 * 组装最终风格提示词：基础音色 + 情绪变体。
 *
 * @param overrides 按情绪覆盖（来自环境变量），给了就用它替换该情绪的默认描述
 */
export function styleFor(
  emotion: Emotion,
  base: string = BASE_STYLE,
  overrides: Partial<Record<Emotion, string>> = {},
): string {
  const mood = overrides[emotion] ?? EMOTIONS[emotion];
  return `${base}${mood}`;
}
