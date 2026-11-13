/**
 * 人设提示词与 TTS 语调提示词的**边界**。
 *
 * 这两段是给两个不同模型看的，混用会立刻表现为"人设对了但语调怪"或反过来：
 *
 * - `agent.ts` 的 `VOICE_SYSTEM` → **DeepSeek**。管身份、性格、措辞、场景、标签。
 *   它产出的正文会被念出来，所以这里只放"怎么说人话"。
 * - `emotion.ts` 的 `BASE_STYLE` / `EMOTIONS` → **TTS**。管音色、语速、情绪起伏。
 *   这里写的任何一句都不会进模型上下文，只影响合成。
 *
 * 唯一的接口是**标签**：DeepSeek 在开头写 `[开心]`，`emotion.ts` 把它翻成风格。
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';

import { cleanForSpeech } from '../src/agent.ts';
import { BASE_STYLE, EMOTIONS } from '../src/emotion.ts';

test('TTS 侧的风格串里不能出现给人看的格式（markdown / 标签）', () => {
  for (const text of [BASE_STYLE, ...Object.values(EMOTIONS)]) {
    assert.ok(!/[*#`\[\]]/.test(text), `TTS 提示词里混进了格式字符：${text}`);
  }
});

test('漏在句中的情绪标签不会被念出来', () => {
  assert.equal(cleanForSpeech('好的[开心]我知道'), '好的我知道');
  assert.equal(cleanForSpeech('【担心】你没事吧'), '你没事吧');
  // 正常的方括号内容不该被吃掉
  assert.equal(cleanForSpeech('我买了[两]个'), '我买了[两]个');
});

test('markdown 与表情仍然按老规矩清掉', () => {
  assert.equal(cleanForSpeech('**加粗**和 `代码`'), '加粗和代码');
});
