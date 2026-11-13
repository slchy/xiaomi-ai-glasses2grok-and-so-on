/**
 * `PhotoInbox` 的单测：它把「请设备拍一张」变成可等待、可取消、可分两段超时的请求。
 *
 * 这里钉的都是**真机上踩过**的判定：
 * - 高清等待期间 SPP 的低清回图不能冒充原图（实测交付过 17 KB 的糊图）
 * - 手机上报失败要立刻返回，不能让云端干等到自己的总超时
 * - 取消之后迟到的原图不该再起一轮识图
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';

import { PhotoInbox, type PhotoFailReason } from '../src/photo-inbox.ts';

interface Harness {
  readonly inbox: PhotoInbox;
  readonly sent: string[];
  readonly logs: string[];
  readonly cancels: number[];
}

function newInbox(overrides: {
  timeoutMs?: number;
  armTimeoutMs?: number;
  shutterTimeoutMs?: number;
} = {}): Harness {
  const sent: string[] = [];
  const logs: string[] = [];
  const cancels: number[] = [];
  const inbox = new PhotoInbox({
    dir: '',
    timeoutMs: overrides.timeoutMs ?? 200,
    armTimeoutMs: overrides.armTimeoutMs ?? 200,
    shutterTimeoutMs: overrides.shutterTimeoutMs ?? 200,
    log: (line) => { logs.push(line); },
    sendTakePhoto: (kind) => { sent.push(kind); },
    sendCancel: () => { cancels.push(Date.now()); },
  });
  return { inbox, sent, logs, cancels };
}

const wait = (ms: number): Promise<void> => new Promise((resolve) => { setTimeout(resolve, ms); });

test('spp：没人等时回 false，交给调用方起一轮识图', () => {
  const { inbox } = newInbox();
  assert.equal(inbox.deliver('spp', Buffer.from([1, 2])), false);
});

test('spp：超时报 timeout，不是 no_shutter', async () => {
  const { inbox } = newInbox({ timeoutMs: 30 });
  const outcome = await inbox.request('spp');
  assert.deepEqual(outcome, { ok: false, reason: 'timeout' });
  assert.equal(inbox.isWaiting, false);
});

test('hq：就绪→交付，中间的低清回图被丢弃', async () => {
  const { inbox, sent } = newInbox();
  const pending = inbox.request('hq');
  assert.deepEqual(sent, ['hq']);
  // 手机还没就绪
  assert.equal(inbox.waitingKind, 'hq');
  // 就绪，且没有提前拍到的图 → 需要提示用户按快门
  assert.equal(inbox.armed(false), true);
  // 高清等待期间 SPP 低清回图：吃掉，但不交付
  assert.equal(inbox.deliver('spp', Buffer.from([9, 9, 9])), true);
  assert.equal(inbox.isWaiting, true);
  assert.equal(inbox.deliver('hq', Buffer.from([7, 7])), true);

  const outcome = await pending;
  assert.deepEqual(outcome, { ok: true, jpeg: Buffer.from([7, 7]) });
});

test('hq：用户提前按了快门 → 不播提示', () => {
  const { inbox } = newInbox();
  void inbox.request('hq');
  assert.equal(inbox.armed(true), false);
});

test('hq：手机上报失败立刻返回，不等本地超时', async () => {
  const { inbox } = newInbox({ armTimeoutMs: 10_000 });
  const pending = inbox.request('hq');
  assert.equal(inbox.failed('no_shutter'), true);
  assert.deepEqual(await pending, { ok: false, reason: 'no_shutter' });
});

test('hq：一直没有就绪 → no_arm', async () => {
  const { inbox } = newInbox({ armTimeoutMs: 30 });
  assert.deepEqual(await inbox.request('hq'), { ok: false, reason: 'no_arm' });
});

test('hq：就绪后用户不按 → no_shutter', async () => {
  const { inbox } = newInbox({ shutterTimeoutMs: 30 });
  const pending = inbox.request('hq');
  inbox.armed(false);
  assert.deepEqual(await pending, { ok: false, reason: 'no_shutter' });
});

test('取消：下发 hq_cancel 并立刻返回 cancelled', async () => {
  const { inbox, cancels } = newInbox();
  const pending = inbox.request('hq');
  inbox.cancel('用户改主意');
  assert.deepEqual(await pending, { ok: false, reason: 'cancelled' });
  assert.equal(cancels.length, 1);
});

test('取消之后迟到的高清图：吃掉，不回落成识图轮', () => {
  const { inbox, logs } = newInbox();
  assert.equal(inbox.deliver('hq', Buffer.from([1])), true);
  assert.ok(logs.some((line) => line.includes('丢弃迟到的高清图')));
});

test('已有请求在等：第二次直接 busy，不排队', async () => {
  const { inbox } = newInbox();
  void inbox.request('hq');
  assert.deepEqual(await inbox.request('hq'), { ok: false, reason: 'busy' });
  inbox.dispose();
});

test('dispose：连接结束时把等待者清掉', async () => {
  const { inbox } = newInbox({ shutterTimeoutMs: 10_000 });
  const pending = inbox.request('hq');
  inbox.dispose();
  const outcome = await pending;
  assert.equal(outcome.ok, false);
  assert.equal((outcome as { reason: PhotoFailReason }).reason, 'cancelled');
  assert.equal(inbox.isWaiting, false);
  await wait(1);
});
