/**
 * `AnnounceStore` 的单测：离线期间攒下的后台汇报，下次连上要能完整取走。
 */
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

import { AnnounceStore } from '../src/announce-store.ts';

function store(dir: string, maxItems = 20): AnnounceStore {
  const s = AnnounceStore.open({ file: join(dir, 'announce.jsonl'), maxItems, log: () => {} });
  assert.ok(s);
  return s;
}

test('push 后能按顺序 drain 出来，并且清空', () => {
  const dir = mkdtempSync(join(tmpdir(), 'announce-'));
  try {
    const s = store(dir);
    s.push('第一条');
    assert.equal(s.push('第二条'), 2);
    assert.deepEqual(s.drain(), ['第一条', '第二条']);
    assert.equal(s.size, 0);
    assert.deepEqual(s.drain(), [], '取空之后再取应当为空');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('超过上限时丢最旧的', () => {
  const dir = mkdtempSync(join(tmpdir(), 'announce-cap-'));
  try {
    const s = store(dir, 3);
    for (const t of ['a', 'b', 'c', 'd']) {
      s.push(t);
    }
    assert.deepEqual(s.drain(), ['b', 'c', 'd']);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('坏行被跳过，好行仍然可用', () => {
  const dir = mkdtempSync(join(tmpdir(), 'announce-bad-'));
  try {
    const file = join(dir, 'announce.jsonl');
    writeFileSync(file, `${JSON.stringify({ t: 1, text: '好的' })}\nnot-json\n{"t":2}\n`);
    const s = AnnounceStore.open({ file, log: () => {} });
    assert.ok(s);
    assert.deepEqual(s.drain(), ['好的']);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('路径为空时不持久化（返回 null）', () => {
  assert.equal(AnnounceStore.open({ file: '' }), null);
});

test('写入是同步的：push 之后文件立刻可读', () => {
  const dir = mkdtempSync(join(tmpdir(), 'announce-sync-'));
  try {
    const file = join(dir, 'announce.jsonl');
    const s = AnnounceStore.open({ file, log: () => {} });
    assert.ok(s);
    s.push('断线期间的汇报');
    assert.match(readFileSync(file, 'utf8'), /断线期间的汇报/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
