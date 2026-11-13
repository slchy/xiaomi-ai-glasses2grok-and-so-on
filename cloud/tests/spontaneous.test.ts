/**
 * `SpontaneousTurn` 的单测：钉住「谁的轮次」这条判定。
 *
 * 背景（2026-11-14 实测）：dsh 的后台作业结算后会自己开一轮汇报，没有
 * `session/prompt`。以前这一轮被静默丢掉 —— 文本写进了 `assistantText` 却没人取。
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';

import { SpontaneousTurn } from '../src/spontaneous.ts';

test('prompt 期间的 running/idle 不算自发', () => {
  const s = new SpontaneousTurn();
  assert.equal(s.onStatus('running', true), null);
  assert.equal(s.onStatus('idle', true), null);
  assert.equal(s.isActive, false);
});

test('没人催的 running → begin，idle → settle', () => {
  const s = new SpontaneousTurn();
  assert.equal(s.onStatus('running', false), 'begin');
  assert.equal(s.isActive, true);
  assert.equal(s.onStatus('idle', false), 'settle');
  assert.equal(s.isActive, false);
});

test('settle 之后再来 idle 不重复结算', () => {
  const s = new SpontaneousTurn();
  s.onStatus('running', false);
  assert.equal(s.onStatus('idle', false), 'settle');
  assert.equal(s.onStatus('idle', false), null);
});

test('自发轮次还没结束时自己又发 prompt：那一轮改归 prompt 等待者', () => {
  const s = new SpontaneousTurn();
  s.onStatus('running', false);
  assert.equal(s.isActive, true);
  s.reset();
  assert.equal(s.isActive, false);
  // 接下来 prompt 的 idle 不会被误判成自发结算
  assert.equal(s.onStatus('idle', true), null);
});

test('无关状态串被忽略', () => {
  const s = new SpontaneousTurn();
  assert.equal(s.onStatus('', false), null);
  assert.equal(s.onStatus('starting', false), null);
});
