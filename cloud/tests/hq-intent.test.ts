/**
 * `wantsHighRes` 的单测：这层判错会直接影响体验（该高清时给了糊图 / 不该高清时白等 8 s）。
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';

import { wantsHighRes } from '../src/hq-intent.ts';

test('要求看清细节的说法都命中', () => {
  const hits = [
    '拍一张高清照片',
    '仔细看看我屏幕上写的什么字',
    '你能看清那个标签吗',
    '放大看一下型号',
    '帮我读一下说明书',
    '念一下上面写的什么',
    '凑近点看清楚',
    '有点小字看不清楚',
  ];
  for (const text of hits) {
    assert.equal(wantsHighRes(text), true, `应命中：${text}`);
  }
});

test('普通问句不命中（否则每次都要多等 8 s）', () => {
  const misses = [
    '这是什么',
    '前面是什么',
    '帮我看看这个',
    '今天天气怎么样',
    '拍张照片',
    '你是谁',
  ];
  for (const text of misses) {
    assert.equal(wantsHighRes(text), false, `不该命中：${text}`);
  }
});
