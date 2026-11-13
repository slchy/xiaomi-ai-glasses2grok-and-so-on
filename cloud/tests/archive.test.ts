/**
 * 档案写入器的单测：只验"该留的东西真的落了盘、时间线能对上音频偏移"。
 *
 * 这块的价值全在事后排障，所以断言集中在：三条流都在、事件 JSON 可解析、
 * 事件里带的偏移能定位到音频、到上限会另起一段。
 */
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

import { SessionArchive } from '../src/archive.ts';

function open(root: string, maxMb = 1): SessionArchive | null {
  return SessionArchive.open(
    { rootDir: root, tag: 'test:1', maxBytesPerSegment: maxMb * 1024 * 1024, keepSessions: 5, log: () => {} },
    { remote: 'test:1', micGain: 4 },
  );
}

test('三条流与事件都落盘，事件带偏移', () => {
  const root = mkdtempSync(join(tmpdir(), 'miai-archive-'));
  try {
    const archive = open(root);
    assert.ok(archive);

    archive.sbc(Buffer.alloc(570, 1));
    archive.pcmRaw(Buffer.alloc(320, 2));
    archive.event('gate.open', { peakDbfs: -57.4 });
    archive.downlink(Buffer.alloc(480, 3));
    archive.event('asr.sentence', { text: '你好' });
    archive.close();

    const dirs = readdirSync(root);
    assert.equal(dirs.length, 1);
    const dir = join(root, dirs[0] ?? '');
    const read = (name: string): Buffer => readFileSync(join(dir, name));

    assert.equal(read('uplink.sbc').length, 570);
    assert.equal(read('uplink.pcm').length, 320);
    assert.equal(read('downlink.pcm').length, 480);

    const meta = JSON.parse(readFileSync(join(dir, 'meta.json'), 'utf8')) as { micGain: number };
    assert.equal(meta.micGain, 4, 'meta 必须自解释：增益是复盘的关键');

    const events = readFileSync(join(dir, 'events.jsonl'), 'utf8')
      .trim()
      .split('\n')
      .map((line) => JSON.parse(line) as { ev: string; t: number; at?: Record<string, number> });

    assert.deepEqual(events.map((e) => e.ev), ['open', 'gate.open', 'asr.sentence', 'close']);
    const opened = events.find((e) => e.ev === 'gate.open');
    // 事件里的偏移必须指向"这一刻之前写了多少"，才能据此切音频
    assert.deepEqual(opened?.at, { sbc: 570, pcm: 320, down: 0 });
    const sentence = events.find((e) => e.ev === 'asr.sentence');
    assert.equal(sentence?.at?.['down'], 480);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('到上限另起一段，不丢后面的音频', () => {
  const root = mkdtempSync(join(tmpdir(), 'miai-archive-seg-'));
  try {
    const archive = SessionArchive.open(
      { rootDir: root, tag: 't', maxBytesPerSegment: 1000, keepSessions: 5, log: () => {} },
      { remote: 't' },
    );
    assert.ok(archive);

    archive.sbc(Buffer.alloc(600));
    archive.sbc(Buffer.alloc(600)); // 超上限 → 第二段
    archive.close();

    const dirs = readdirSync(root).sort();
    assert.equal(dirs.length, 2, `应当有两段，实际 ${dirs.join(',')}`);
    assert.equal(readFileSync(join(root, dirs[0] ?? '', 'uplink.sbc')).length, 600);
    assert.equal(readFileSync(join(root, dirs[1] ?? '', 'uplink.sbc')).length, 600);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('目录为空时不落盘（默认关闭）', () => {
  assert.equal(SessionArchive.open(
    { rootDir: '', tag: 't', maxBytesPerSegment: 1, keepSessions: 1, log: () => {} },
    {},
  ), null);
});
