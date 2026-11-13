/**
 * `SessionCore` 的单测：只喂事件，断言「产出什么动作」。
 *
 * 这些判断以前散在 socket 回调里，只能靠真机听感验证；现在纯到可以直接断言。
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';

import { SessionCore } from '../src/session-core.ts';

function newCore(): { core: SessionCore; lines: string[] } {
  const lines: string[] = [];
  const core = new SessionCore({
    imageQuestion: '这张图里有什么？',
    log: (line) => { lines.push(line); },
  });
  return { core, lines };
}

/** 把会话推到 speaking：开一轮 + 首块音频。 */
function toSpeaking(core: SessionCore): void {
  const ticket = core.fsm.beginTurn();
  assert.ok(ticket);
  core.fsm.audioStarted(ticket);
  assert.equal(core.phase, 'speaking');
}

test('唤醒词：不过成句合并', () => {
  const { core } = newCore();
  assert.deepEqual(core.dispatch({ type: 'sentence', text: '小爱同学。' }), [
    { type: 'drop', why: 'wake-word' },
  ]);
});

test('正常一句话：交给合并器', () => {
  const { core } = newCore();
  assert.deepEqual(core.dispatch({ type: 'sentence', text: '今天天气怎么样' }), [
    { type: 'assemble', text: '今天天气怎么样' },
  ]);
});

test('旁听反馈：不成轮', () => {
  const { core } = newCore();
  assert.deepEqual(core.dispatch({ type: 'utterance', text: '嗯嗯，我知道了我知道了。' }), [
    { type: 'drop', why: 'backchannel' },
  ]);
  assert.equal(core.turn, 0);
});

test('定稿一句话：开一轮并带上票据', () => {
  const { core } = newCore();
  const effects = core.dispatch({ type: 'utterance', text: '帮我看看这个' });
  assert.equal(effects.length, 1);
  assert.equal(effects[0]?.type, 'turn');
  assert.equal(effects[0]?.type === 'turn' && effects[0].text, '帮我看看这个');
});

test('插话只在 speaking 成立', () => {
  const { core } = newCore();
  // listening：不算插话
  assert.deepEqual(core.dispatch({ type: 'partial', text: '等一下' }), []);

  toSpeaking(core);
  assert.deepEqual(core.dispatch({ type: 'partial', text: '等一下' }), [{ type: 'stop' }]);
  assert.equal(core.phase, 'listening');
});

test('太短的中间结果不触发插话', () => {
  const { core } = newCore();
  toSpeaking(core);
  assert.deepEqual(core.dispatch({ type: 'partial', text: '嗯' }), []);
  assert.equal(core.phase, 'speaking');
});

test('没人等的图：起一轮识图', () => {
  const { core } = newCore();
  const effects = core.dispatch({ type: 'image', jpeg: Buffer.from([1, 2, 3]) });
  assert.equal(effects.length, 1);
  const effect = effects[0];
  assert.ok(effect && effect.type === 'turn');
  assert.equal(effect.text, '这张图里有什么？');
  assert.equal(effect.image?.mimeType, 'image/jpeg');
  assert.equal(effect.image?.data, Buffer.from([1, 2, 3]).toString('base64'));
});

test('模型要求结束：告别语播完才挂断', () => {
  const { core } = newCore();
  toSpeaking(core);
  assert.deepEqual(core.dispatch({ type: 'endRequested' }), [{ type: 'stop_capture' }]); // 先停采集
  const ticket = core.fsm.ticket();
  core.fsm.audioDone(ticket);
  assert.equal(core.phase, 'ending');
  assert.deepEqual(core.dispatch({ type: 'turnFinished' }), [{ type: 'end' }]);
});

test('空闲时要求结束：停采集 + 立刻挂断', () => {
  const { core } = newCore();
  assert.deepEqual(core.dispatch({ type: 'endRequested' }), [
    { type: 'stop_capture' },
    { type: 'end' },
  ]);
  assert.equal(core.phase, 'ending');
});

test('收尾后到达的识别结果不再开新一轮', () => {
  const { core } = newCore();
  toSpeaking(core);
  core.dispatch({ type: 'endRequested' });
  assert.deepEqual(core.dispatch({ type: 'utterance', text: '等等我还有事' }), [
    { type: 'drop', why: 'ending' },
  ]);
});

test('连接结束是吸收态', () => {
  const { core } = newCore();
  core.dispatch({ type: 'closed' });
  assert.equal(core.phase, 'closed');
  core.dispatch({ type: 'endRequested' });
  assert.equal(core.phase, 'closed');
});

// ---------------------------------------------------------------- 高清照流程

test('说「看清细节」：先进 awaiting_shot，不直接开轮', () => {
  const { core } = newCore();
  const effects = core.dispatch({ type: 'utterance', text: '看看上面写的什么字' });
  assert.deepEqual(effects.map((e) => e.type), ['awaitShot']);
  assert.equal(core.phase, 'awaiting_shot');
  // 还没开轮：等图期间说话不该被票据挡住
  assert.equal(core.turn, 0);
});

test('拿到高清图：开一轮，图挂在 text 后面一起交给模型', () => {
  const { core } = newCore();
  core.dispatch({ type: 'utterance', text: '看看上面写的什么字' });
  const jpeg = Buffer.from([1, 2, 3]);
  const effects = core.dispatch({ type: 'shotResult', ok: true, jpeg });
  assert.equal(effects.length, 1);
  const turn = effects[0];
  assert.equal(turn?.type, 'turn');
  assert.ok(turn?.type === 'turn' && turn.text.startsWith('看看上面写的什么字'));
  assert.ok(turn?.type === 'turn' && turn.text.includes('不要再调用拍照工具'));
  assert.equal(turn?.type === 'turn' && turn.image?.mimeType, 'image/jpeg');
  assert.equal(turn?.type === 'turn' && turn.image?.data, jpeg.toString('base64'));
});

test('没拿到高清图：照常开一轮，并把「请按快门」告诉模型', () => {
  const { core } = newCore();
  core.dispatch({ type: 'utterance', text: '看看上面写的什么字' });
  const effects = core.dispatch({ type: 'shotResult', ok: false, reason: 'no_shutter' });
  const turn = effects[0];
  assert.equal(turn?.type, 'turn');
  assert.equal(turn?.type === 'turn' && turn.image, undefined);
  assert.ok(turn?.type === 'turn' && turn.text.includes('按一下眼镜上的快门'));
});

test('等快门时又改主意：取消拍照、打断旁白，再开新一轮', () => {
  const { core } = newCore();
  core.dispatch({ type: 'utterance', text: '看看上面写的什么字' });
  const effects = core.dispatch({ type: 'utterance', text: '算了，今天天气怎么样' });
  assert.deepEqual(effects.map((e) => e.type), ['cancel_shot', 'stop', 'turn']);
  assert.equal(core.phase, 'listening');
});

test('等快门时又要高清：取消旧的，重新等', () => {
  const { core } = newCore();
  core.dispatch({ type: 'utterance', text: '看看上面写的什么字' });
  const effects = core.dispatch({ type: 'utterance', text: '算了，凑近看清楚点' });
  assert.deepEqual(effects.map((e) => e.type), ['cancel_shot', 'stop', 'awaitShot']);
  assert.equal(core.phase, 'awaiting_shot');
});

test('等快门时收尾：取消拍照 + 停采集 + 立刻挂断', () => {
  const { core } = newCore();
  core.dispatch({ type: 'utterance', text: '看看上面写的什么字' });
  const effects = core.dispatch({ type: 'endRequested' });
  assert.deepEqual(
    effects.map((e) => e.type),
    ['cancel_shot', 'stop', 'stop_capture', 'end'],
  );
  assert.equal(core.phase, 'ending');
});

test('拍照结果迟到：没有待处理的句子就丢弃', () => {
  const { core } = newCore();
  const effects = core.dispatch({ type: 'shotResult', ok: true, jpeg: Buffer.from([1]) });
  assert.deepEqual(effects, [{ type: 'drop', why: 'shot-stale' }]);
  assert.equal(core.phase, 'listening');
});

test('等高清图期间收到低清回图：丢掉，不另起一轮', () => {
  const { core } = newCore();
  core.dispatch({ type: 'utterance', text: '看看上面写的什么字' });
  assert.deepEqual(core.dispatch({ type: 'image', jpeg: Buffer.from([1]) }), [
    { type: 'drop', why: 'spp-during-shot' },
  ]);
  assert.equal(core.phase, 'awaiting_shot');
});
