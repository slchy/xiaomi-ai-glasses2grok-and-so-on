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
  // 那个空格是「为了写 markdown 才加的」：反引号一剥，中文之间不该留缝，
  // 否则合成器会在缝里塞一个停顿（`加粗和代码` 听起来是「加粗和、代码」）
  assert.equal(cleanForSpeech('**加粗**和 `代码`'), '加粗和代码');
  // 模型偶尔把零宽空格写成实体 —— 字面送进 TTS 会被念成符号，必须在文本层清掉
  assert.equal(cleanForSpeech('**&#8203;加粗**和 `代码`'), '加粗和代码');
  assert.equal(cleanForSpeech('**\u200b加粗**和 `代码`'), '加粗和代码');
  assert.equal(cleanForSpeech('加粗&nbsp;和代码'), '加粗和代码');
  // 反面：英文 / 代码段两侧的空格有语义，不能被「中文去空格」一起吃掉
  assert.equal(cleanForSpeech('看这个 `npm run build` 命令'), '看这个 npm run build 命令');
  assert.equal(cleanForSpeech('用 A &amp; B 连接'), '用 A & B 连接');
  // emoji 照旧清掉；它退场后留下的空格也被「中文之间不留缝」一并收掉
  assert.equal(cleanForSpeech('好的😀收到'), '好的收到');
  assert.equal(cleanForSpeech('好的 😀 收到'), '好的收到');
});
