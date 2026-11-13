/**
 * 情绪标签解析 + 风格组装。纯函数，直接断言。
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';

import { BASE_STYLE, EMOTIONS, parseEmotion, styleFor } from '../src/emotion.ts';

test('五个标签都能认出来，并剥掉标签', () => {
  const cases: Array<[string, string]> = [
    ['[开心] 是只橘猫哥哥！', 'happy'],
    ['[得意] 嘿嘿，那当然啦', 'proud'],
    ['[平静] 三点二十。', 'calm'],
    ['[担心] 哥哥，都一点了', 'worried'],
    ['[委屈] 好嘛，那我安静一点', 'sulk'],
  ];
  for (const [text, emotion] of cases) {
    const r = parseEmotion(text);
    assert.equal(r.emotion, emotion, text);
    assert.ok(!r.rest.includes('['), `标签没剥干净：${r.rest}`);
  }
});

test('标签前有空格 / 全角方括号也能认', () => {
  assert.equal(parseEmotion('  [开心]好').emotion, 'happy');
  assert.equal(parseEmotion('【担心】哥哥').emotion, 'worried');
});

test('没有标签：默认平静，原文一个字都不改', () => {
  const text = '明天晴，零下二度到十一度。';
  const r = parseEmotion(text);
  assert.equal(r.emotion, 'calm');
  assert.equal(r.rest, text);
});

test('不认识的标签：默认平静，但标签仍然剥掉（别念出方括号）', () => {
  const r = parseEmotion('[生气] 哼');
  assert.equal(r.emotion, 'calm');
  assert.equal(r.rest, '哼');
});

test('只认开头那一个：正文里的方括号不动', () => {
  const r = parseEmotion('[开心] 我买了[两]个');
  assert.equal(r.emotion, 'happy');
  assert.equal(r.rest, '我买了[两]个');
});

test('风格串 = 基础音色 + 该情绪的描述，且可被覆盖', () => {
  assert.equal(styleFor('worried'), `${BASE_STYLE}${EMOTIONS.worried}`);
  assert.equal(
    styleFor('happy', '基础。', { happy: '自定义。' }),
    '基础。自定义。',
  );
});
