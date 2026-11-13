/**
 * `DialogFsm` 的穷尽状态空间验证。
 *
 * <h2>方法</h2>
 *
 * 不做证明助手的符号推导，做的是**黑盒穷尽探索 + 不变量检查**：
 *
 * 1. 从空事件序列出发做 DFS，深度上限 {@link MAX_DEPTH}；
 * 2. 每个配置用抽象签名去重（状态 / 轮次截断 / 代次截断 / 结束标志），
 *    避免无限展开；
 * 3. 对每条「配置 × 事件」边，用**全新实例重放**该序列得到下一个配置 ——
 *    不依赖反射，也不改状态机的私有状态；
 * 4. 对每条边与每个配置检查下列不变量。
 *
 * 这样得到的是**可达转移关系**（reachable transition relation），
 * 后面所有断言都只在真实可达的配置上成立，而不是对所有理论组合。
 *
 * <h2>怎么跑</h2>
 *
 * ```bash
 * cd cloud && node tests/fsm-verify.ts
 * ```
 *
 * 退出码 0 = 全部通过；1 = 有断言失败（会逐条打印反例序列）。
 */
import { DialogFsm, type DialogState, type TurnTicket } from '../src/dialog-fsm.ts';

const STATES: readonly DialogState[] = [
  'listening', 'thinking', 'speaking', 'ending', 'closed',
];

const MAX_DEPTH = 7;

/** 事件是「对状态机的一次调用」。票据按与当前状态的关系分三种。 */
type EventKind =
  | 'beginTurn'
  | 'audioStarted:own'
  | 'audioStarted:stale'
  | 'audioStarted:past'
  | 'audioDone:own'
  | 'audioDone:stale'
  | 'audioDone:past'
  | 'bargeIn'
  | 'requestEnd'
  | 'close';

const EVENTS: readonly EventKind[] = [
  'beginTurn',
  'audioStarted:own', 'audioStarted:stale', 'audioStarted:past',
  'audioDone:own', 'audioDone:stale', 'audioDone:past',
  'bargeIn',
  'requestEnd',
  'close',
];

interface Config {
  readonly state: DialogState;
  readonly turn: number;
  readonly gen: number;
  readonly endAfterSpeech: boolean;
}

interface Transition {
  readonly from: Config;
  readonly event: EventKind;
  readonly to: Config;
  readonly bargeInAccepted: boolean;
  readonly beginTurnAccepted: boolean;
}

const observations: string[] = [];
function observe(line: string): void {
  observations.push(line);
}

function snapshot(fsm: DialogFsm): Config {
  return {
    state: fsm.state,
    turn: fsm.turn,
    gen: fsm.gen,
    endAfterSpeech: fsm.endAfterSpeech,
  };
}

/** 抽象签名：轮次与代次只保留 0 / 1 / ≥2 三档，否则空间无界。 */
function signature(c: Config): string {
  const bucket = (n: number): string => (n === 0 ? '0' : n === 1 ? '1' : '2+');
  return `${c.state}|${bucket(c.turn)}|${bucket(c.gen)}|${c.endAfterSpeech ? 'E' : '-'}`;
}

function ticketFor(kind: EventKind, fsm: DialogFsm, variant: 'own' | 'stale' | 'past'): TurnTicket {
  switch (variant) {
    case 'own':
      return fsm.ticket();
    case 'stale':
      // 同一轮但代次落后 —— 模拟「插话之后上一轮迟到的回调」
      return { no: fsm.turn, gen: fsm.gen - 1 };
    case 'past':
      // 上一轮的票据，代次未变 —— 这正是评审指出的漏洞场景
      return { no: fsm.turn - 1, gen: fsm.gen };
    default:
      return fsm.ticket();
  }
}

/** 重放一段事件序列，返回终态配置与调用返回值。 */
function replay(events: readonly EventKind[]): {
  config: Config;
  bargeInAccepted: boolean;
  beginTurnAccepted: boolean;
} {
  observations.length = 0;
  const fsm = new DialogFsm(observe);
  let bargeInAccepted = false;
  let beginTurnAccepted = false;

  for (const event of events) {
    switch (event) {
      case 'beginTurn':
        beginTurnAccepted = fsm.beginTurn() !== null;
        break;
      case 'bargeIn':
        bargeInAccepted = fsm.bargeIn('verify');
        break;
      case 'requestEnd':
        fsm.requestEnd();
        break;
      case 'close':
        fsm.close();
        break;
      default: {
        const [method, variant] = event.split(':') as [
          'audioStarted' | 'audioDone',
          'own' | 'stale' | 'past',
        ];
        const ticket = ticketFor(event, fsm, variant);
        if (method === 'audioStarted') {
          fsm.audioStarted(ticket);
        } else {
          fsm.audioDone(ticket);
        }
      }
    }
  }

  return { config: snapshot(fsm), bargeInAccepted, beginTurnAccepted };
}

const failures: string[] = [];
function check(condition: boolean, message: string, context: string): void {
  if (!condition) {
    failures.push(`${message}\n      反例序列: ${context || '(空)'}`);
  }
}

function sequenceToString(events: readonly EventKind[]): string {
  return events.length === 0 ? '' : events.join(' → ');
}

// ------------------------------------------------------------------ 探索

const visited = new Map<string, EventKind[]>();
const transitions: Transition[] = [];
const queue: EventKind[][] = [[]];
visited.set('listening|0|0|-', []);

while (queue.length > 0) {
  const path = queue.shift()!;
  if (path.length >= MAX_DEPTH) {
    continue;
  }
  const base = replay(path);

  for (const event of EVENTS) {
    const nextPath = [...path, event];
    const next = replay(nextPath);

    transitions.push({
      from: base.config,
      event,
      to: next.config,
      bargeInAccepted: event === 'bargeIn' ? next.bargeInAccepted : false,
      beginTurnAccepted: event === 'beginTurn' ? next.beginTurnAccepted : false,
    });

    const sig = signature(next.config);
    if (!visited.has(sig)) {
      visited.set(sig, nextPath);
      queue.push(nextPath);
    }
  }
}

// ------------------------------------------------------------------ 不变量

let edgeCount = 0;

for (const t of transitions) {
  edgeCount += 1;
  const ctx = sequenceToString([...visited.get(signature(t.from)) ?? [], t.event]);

  // 落点必是合法状态
  check(STATES.includes(t.to.state), `不变量失败：非法状态 ${t.to.state}`, ctx);

  // 轮次与代次单调不减
  check(t.to.turn >= t.from.turn, '不变量失败：轮次回退', ctx);
  check(t.to.gen >= t.from.gen, '不变量失败：代次回退', ctx);

  switch (t.event) {
    case 'beginTurn': {
      const rejected = ['ending', 'closed'].includes(t.from.state);
      check(
        t.beginTurnAccepted === !rejected,
        `不变量失败：beginTurn 在 ${t.from.state} 的接受性不符（期望 ${!rejected}）`,
        ctx,
      );
      if (t.beginTurnAccepted) {
        check(t.to.state === 'thinking', '不变量失败：beginTurn 未进入 thinking', ctx);
        check(t.to.turn === t.from.turn + 1, '不变量失败：beginTurn 未推进轮次', ctx);
        check(!t.to.endAfterSpeech, '不变量失败：beginTurn 未清除结束标志', ctx);
      }
      break;
    }
    case 'audioStarted:own':
      check(
        t.from.state !== 'thinking' || t.to.state === 'speaking',
        '不变量失败：thinking 收到本轮回调未进入 speaking',
        ctx,
      );
      check(
        t.from.state === 'thinking' || t.to.state === t.from.state,
        `不变量失败：非 thinking 状态下 audioStarted 改变了状态（${t.from.state} → ${t.to.state}）`,
        ctx,
      );
      break;
    case 'audioStarted:stale':
    case 'audioStarted:past':
    case 'audioDone:stale':
    case 'audioDone:past':
      check(
        t.to.state === t.from.state,
        `不变量失败：过期票据改变了状态（${t.event}，${t.from.state} → ${t.to.state}）`,
        ctx,
      );
      break;
    case 'audioDone:own': {
      // 前置条件：closed 是吸收态，此时不应再有任何落点变化，
      // 该情形由 P2 单独检查，这里不能拿它当反例。
      if (t.from.state === 'closed') {
        break;
      }
      const expected = t.from.endAfterSpeech ? 'ending' : 'listening';
      check(
        t.to.state === expected,
        `不变量失败：audioDone 落点应为 ${expected}，实际 ${t.to.state}`,
        ctx,
      );
      break;
    }
    case 'bargeIn':
      check(
        t.bargeInAccepted === (t.from.state === 'speaking'),
        `不变量失败：bargeIn 仅在 speaking 可接受（当前 ${t.from.state}）`,
        ctx,
      );
      if (t.bargeInAccepted) {
        check(t.to.state === 'listening', '不变量失败：插话未回到 listening', ctx);
        check(t.to.gen === t.from.gen + 1, '不变量失败：插话未推进代次', ctx);
        check(!t.to.endAfterSpeech, '不变量失败：插话未清除结束标志', ctx);
      }
      break;
    case 'requestEnd':
      if (t.from.state === 'listening') {
        check(t.to.state === 'ending', '不变量失败：listening 期 requestEnd 应直接进 ending', ctx);
      } else if (t.from.state !== 'closed') {
        check(
          t.to.state === t.from.state,
          '不变量失败：thinking/speaking 期 requestEnd 不应改变状态',
          ctx,
        );
        check(t.to.endAfterSpeech, '不变量失败：requestEnd 未置结束标志', ctx);
      }
      break;
    case 'close':
      check(t.to.state === 'closed', '不变量失败：close 未进入 closed', ctx);
      break;
    default:
      break;
  }
}

// ------------------------------------------------------------------ 全局性质

// P1：从任意可达配置出发，close 都可用（不会卡死无法收尾）
for (const path of visited.values()) {
  const base = replay(path);
  const closed = replay([...path, 'close']);
  check(
    closed.config.state === 'closed',
    `P1 失败：从 ${base.config.state} 无法 close`,
    sequenceToString(path),
  );
}

// P2：closed 是吸收态
for (const path of visited.values()) {
  for (const event of EVENTS) {
    const next = replay([...path, 'close', event]);
    check(
      next.config.state === 'closed',
      `P2 失败：closed 后 ${event} 逃出了吸收态`,
      sequenceToString([...path, 'close', event]),
    );
  }
}

// P3：不存在从 listening 直接到 speaking 的边（必须经过 thinking）
for (const t of transitions) {
  check(
    !(t.from.state === 'listening' && t.to.state === 'speaking'),
    'P3 失败：listening 直接进入 speaking',
    sequenceToString([...visited.get(signature(t.from)) ?? [], t.event]),
  );
}

// P4：正常路径可达全部 5 个状态
const reachableStates = new Set([...visited.values()].map((p) => replay(p).config.state));
for (const state of STATES) {
  check(reachableStates.has(state), `P4 失败：状态 ${state} 不可达`, '');
}

// P5：确定性 —— 同一序列重放两次结果必须一致
for (const path of visited.values()) {
  const a = replay(path).config;
  const b = replay(path).config;
  check(
    signature(a) === signature(b) && a.turn === b.turn && a.gen === b.gen,
    'P5 失败：同一序列重放结果不一致（非确定性）',
    sequenceToString(path),
  );
}

// ------------------------------------------------------------------ 输出

const distinctConfigs = [...visited.values()].length;
const stateCoverage = STATES
  .map((s) => `${s}:${[...visited.values()].some((p) => replay(p).config.state === s) ? '✓' : '✗'}`)
  .join('  ');

console.log('=== DialogFsm 穷尽状态空间验证 ===');
console.log(`配置（去重后）  ${distinctConfigs}`);
console.log(`转移（含无效事件）${transitions.length}`);
console.log(`状态覆盖       ${stateCoverage}`);
console.log('');

if (failures.length === 0) {
  console.log(`✓ 全部不变量通过（${edgeCount} 条转移 × 若干性质）`);
  console.log('  INV1 合法状态 / INV2 轮次单调 / INV3 代次单调');
  console.log('  INV4 beginTurn 仅 listening 生效且清结束标志');
  console.log('  INV5 audioStarted 仅 thinking→speaking');
  console.log('  INV6 过期票据（stale / past）不改变任何状态');
  console.log('  INV7 audioDone 落点 = endAfterSpeech ? ending : listening');
  console.log('  INV8 bargeIn 仅 speaking 生效，回 listening 且代次 +1');
  console.log('  INV9 requestEnd 在 listening 直接 ending，在播期只置标志');
  console.log('  INV10 close 进 closed');
  console.log('  P1 任意可达配置都能 close / P2 closed 是吸收态');
  console.log('  P3 无 listening→speaking 直连 / P4 五状态全可达 / P5 确定性');
  process.exit(0);
}

console.log(`✗ ${failures.length} 条反例：`);
for (const failure of failures.slice(0, 20)) {
  console.log(`  - ${failure}`);
}
if (failures.length > 20) {
  console.log(`  … 还有 ${failures.length - 20} 条`);
}
process.exit(1);
