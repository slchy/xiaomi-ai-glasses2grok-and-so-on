/**
 * 把一份会话档案（`events.jsonl`）折算成**逐轮延迟分解**。
 *
 * <h2>为什么需要它</h2>
 *
 * 语音形态的硬指标只有一个：**用户说完 → 听到第一个字**。以前这个数字只散在
 * console 日志里（`TURN n=… dsh_ms=… tts_first_ms=…`），人肉对得上但**没法复算**，
 * 于是任何改动之后都无法回答「变快了还是变慢了」。
 *
 * 档案里的每条事件都带相对时间 `t`（ms），所以这件事是纯函数：喂一批事件，
 * 产出每一轮的分段耗时。不碰 socket、不读时钟、不落盘。
 *
 * <h2>分段口径</h2>
 *
 * ```text
 * gate.close ──①──▶ asr.sentence ──②──▶ turn.start ──③──▶ tts.first ──④──▶ turn.end
 * ```
 *
 * - ① 判停 + ASR 定稿一句
 * - ② 定稿 → 开跑一轮（同一进程内的排队）
 * - ③ agent + TTS 首块（**宠物形态里最大的一块**）
 * - ④ 首块出声 → 本轮结束（播放，不属于延迟）
 *
 * **首字总延迟 = ① + ② + ③**，也就是 ③ 结束时相对 ① 起点的距离。
 *
 * <h2>缺仪表怎么表示</h2>
 *
 * `tts.first` / `turn.end` 是后加的。更早的档案里没有它们，那些轮次对应字段为
 * `null`，报告里单独计数 —— **不拿 0 冒充缺失值**，否则历史基线会被伪造成「很快」。
 */

/** 档案里的一行事件；只声明报告用得到的字段，其余原样忽略。 */
export interface ArchiveEvent {
  /** 相对会话开始的毫秒数 */
  readonly t: number;
  /** 事件名 */
  readonly ev: string;
  readonly text?: unknown;
  readonly ms?: unknown;
  readonly dsh_ms?: unknown;
  readonly audio_s?: unknown;
  readonly interrupted?: unknown;
}

/** 一轮里可能缺席的时刻名。 */
export type MissingMark = 'gate.close' | 'asr.sentence' | 'tts.first' | 'turn.end';

/** 一轮的分段耗时；`null` = 该段缺仪表。 */
export interface TurnLatency {
  /** 第几轮，从 1 开始，按 `turn.start` 出现顺序 */
  readonly turn: number;
  /** ① 静音判停 → ASR 定稿一句（ms） */
  readonly silenceToAsr: number | null;
  /** ② ASR 定稿 → 开跑一轮（ms） */
  readonly asrToTurn: number | null;
  /** ③ 开跑 → 首块音频（agent + TTS 首块，ms） */
  readonly turnToAudio: number | null;
  /** **首字总延迟**：用户说完 → 第一个字出声（ms） */
  readonly firstWordMs: number | null;
  /** ④ 首块音频 → 本轮结束（播放，ms） */
  readonly playbackMs: number | null;
  /** agent 自报耗时（ms） */
  readonly agentMs: number | null;
  /** 是否被用户插话中断 */
  readonly interrupted: boolean;
  /** 这一轮对应的 ASR 文本（截断，便于人肉对上） */
  readonly text: string;
  /**
   * 这一轮缺哪些时刻。
   *
   * 必须逐个记：`firstWordMs` 为 `null` 可能是"旧档案没有 tts.first"，也可能是
   * "这一轮根本没有 gate.close"（主动播报、或用户中途插话），两者的处置完全不同。
   */
  readonly missing: readonly MissingMark[];
}

/** 报告里文本预览的最大字数。 */
const TEXT_PREVIEW = 40;

/** 取一个可能是任意 JSON 值的数字；不是有限数就给 `undefined`。 */
function num(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

/** 文本预览：超长的截断加省略号。 */
function preview(value: unknown): string {
  if (typeof value !== 'string' || value === '') {
    return '';
  }
  return value.length <= TEXT_PREVIEW ? value : `${value.slice(0, TEXT_PREVIEW)}…`;
}

/**
 * 解析 `events.jsonl` 的全文。
 *
 * 坏行直接跳过：一行坏数据不该让整份报告失效 —— 档案是"事后能查清"用的，
 * 而它自己坏掉的时候更要能读出来。
 *
 * @param jsonl - 文件全文，可含空行
 * @returns 事件数组，保持文件顺序
 */
export function parseEvents(jsonl: string): ArchiveEvent[] {
  const out: ArchiveEvent[] = [];
  for (const line of jsonl.split('\n')) {
    const trimmed = line.trim();
    if (trimmed === '') {
      continue;
    }
    let raw: unknown;
    try {
      raw = JSON.parse(trimmed);
    } catch {
      continue;
    }
    if (!raw || typeof raw !== 'object') {
      continue;
    }
    const obj = raw as Record<string, unknown>;
    const t = num(obj['t']);
    const ev = obj['ev'];
    if (t === undefined || typeof ev !== 'string' || ev === '') {
      continue;
    }
    out.push({
      t,
      ev,
      text: obj['text'],
      ms: obj['ms'],
      dsh_ms: obj['dsh_ms'],
      audio_s: obj['audio_s'],
      interrupted: obj['interrupted'],
    });
  }
  return out;
}

/** 从 `from`（含）向前找第一个指定事件，找不到返回 -1。 */
function findBackward(events: readonly ArchiveEvent[], from: number, ev: string): number {
  for (let i = from; i >= 0; i -= 1) {
    if (events[i]?.ev === ev) {
      return i;
    }
  }
  return -1;
}

/** 从 `from`（含）向后找第一个指定事件，找不到返回 -1。 */
function findForward(events: readonly ArchiveEvent[], from: number, ev: string): number {
  for (let i = from; i < events.length; i += 1) {
    if (events[i]?.ev === ev) {
      return i;
    }
  }
  return -1;
}

/** 两个事件之间的时间差；任一缺失时给 `null`。 */
function delta(
  events: readonly ArchiveEvent[],
  laterAt: number,
  earlierAt: number,
): number | null {
  if (laterAt < 0 || earlierAt < 0) {
    return null;
  }
  const later = events[laterAt];
  const earlier = events[earlierAt];
  if (!later || !earlier) {
    return null;
  }
  return later.t - earlier.t;
}

/**
 * 把事件序列折算成逐轮延迟。
 *
 * 配对规则（每条都限定了搜索边界，避免跨轮串味）：
 * - `gate.close` / `asr.sentence` 只在本轮 `turn.start` **与上一轮 `turn.start` 之间**找
 * - `tts.first` / `turn.end` 只在本轮 `turn.start` **与下一轮 `turn.start` 之间**找
 *
 * 主动播报（`announce`）没有 `asr.sentence`，所以它的 ①② 为 `null`；
 * 若它触发了 `turn.start`，仍会算进 ③④。
 *
 * @param events - 已解析的事件序列
 * @returns 每轮一段，按出现顺序
 */
export function computeTurns(events: readonly ArchiveEvent[]): TurnLatency[] {
  const starts: number[] = [];
  for (let i = 0; i < events.length; i += 1) {
    if (events[i]?.ev === 'turn.start') {
      starts.push(i);
    }
  }

  const out: TurnLatency[] = [];
  for (let n = 0; n < starts.length; n += 1) {
    const at = starts[n];
    if (at === undefined) {
      continue;
    }
    const prevAt = n === 0 ? -1 : (starts[n - 1] ?? -1);
    const nextAt = starts[n + 1] ?? events.length;

    // ① 的起点：本轮范围内的最后一个 gate.close
    let gateAt = -1;
    for (let i = at - 1; i > prevAt; i -= 1) {
      if (events[i]?.ev === 'gate.close') {
        gateAt = i;
        break;
      }
    }
    // ② 的中间点：本轮范围内的最后一个 asr.sentence
    let asrAt = -1;
    for (let i = at - 1; i > prevAt; i -= 1) {
      if (events[i]?.ev === 'asr.sentence') {
        asrAt = i;
        break;
      }
    }

    // ③④ 的终点：本轮范围内
    let firstAt = -1;
    for (let i = at; i < nextAt; i += 1) {
      if (events[i]?.ev === 'tts.first') {
        firstAt = i;
        break;
      }
    }
    let endAt = -1;
    for (let i = at; i < nextAt; i += 1) {
      if (events[i]?.ev === 'turn.end') {
        endAt = i;
        break;
      }
    }

    const silenceToAsr = delta(events, asrAt, gateAt);
    const asrToTurn = delta(events, at, asrAt);
    const turnToAudio = delta(events, firstAt, at);
    const playbackMs = delta(events, endAt, firstAt);
    // 首字延迟必须以 gate.close 为起点：缺任何一环都不猜
    const firstWordMs = delta(events, firstAt, gateAt);

    const missing: MissingMark[] = [];
    if (gateAt < 0) {
      missing.push('gate.close');
    }
    if (asrAt < 0) {
      missing.push('asr.sentence');
    }
    if (firstAt < 0) {
      missing.push('tts.first');
    }
    if (endAt < 0) {
      missing.push('turn.end');
    }

    const end = endAt < 0 ? undefined : events[endAt];
    out.push({
      turn: n + 1,
      silenceToAsr,
      asrToTurn,
      turnToAudio,
      firstWordMs,
      playbackMs,
      agentMs: num(end?.dsh_ms) ?? null,
      interrupted: end?.interrupted === true,
      text: preview(asrAt < 0 ? undefined : events[asrAt]?.text),
      missing,
    });
  }
  return out;
}

/**
 * 取分位数（线性插值，与 `numpy.percentile` 默认一致）。
 *
 * 不用最近秩法：偶数样本时它会取偏小的那个，**让延迟数字系统性偏乐观** ——
 * 一份"看起来很快"的基线比没有基线更糟。
 *
 * @param values - 样本；`null` 会被剔除
 * @param p - 0..1 之间的分位
 * @returns 分位值；样本为空时 `null`
 */
export function percentile(values: readonly (number | null)[], p: number): number | null {
  const kept = values.filter((v): v is number => v !== null).sort((a, b) => a - b);
  if (kept.length === 0) {
    return null;
  }
  const clamped = Math.min(1, Math.max(0, p));
  const pos = (kept.length - 1) * clamped;
  const lower = Math.floor(pos);
  const upper = Math.ceil(pos);
  const lo = kept[lower];
  const hi = kept[upper];
  if (lo === undefined || hi === undefined) {
    return null;
  }
  return lower === upper ? lo : lo + (hi - lo) * (pos - lower);
}

/** 一组样本的「中位 / p90 / 最大」摘要。 */
export interface Sample {
  readonly count: number;
  readonly median: number | null;
  readonly p90: number | null;
  readonly max: number | null;
}

/** 把一列（可能含 `null`）折成摘要。 */
export function summarize(values: readonly (number | null)[]): Sample {
  const kept = values.filter((v): v is number => v !== null);
  return {
    count: kept.length,
    median: percentile(values, 0.5),
    p90: percentile(values, 0.9),
    max: kept.length === 0 ? null : Math.max(...kept),
  };
}

/** 首字延迟的判据（ms）：超过它，宠物就变成"语音问答机"。 */
export const FIRST_WORD_BUDGET_MS = 1200;

/** 把一个数字列成表格单元；`null` 显示为 `-`。 */
function cell(value: number | null, unit = ''): string {
  return value === null ? '-' : `${Math.round(value)}${unit}`;
}

/**
 * 排版成人能读的报告。
 *
 * @param turns - {@link computeTurns} 的输出
 * @param header - 报告抬头（通常是档案目录名）
 * @returns 多行文本；末尾不带换行
 */
export function formatReport(turns: readonly TurnLatency[], header = ''): string {
  const lines: string[] = [];
  if (header !== '') {
    lines.push(`# ${header}`);
    lines.push('');
  }
  if (turns.length === 0) {
    lines.push('没有找到任何 turn.start —— 这份档案里没有可算的轮次。');
    return lines.join('\n');
  }

  lines.push('轮次  ①判停+ASR  ②排队  ③agent+TTS  首字总延迟  ④播放  agent自报打断文本');
  for (const t of turns) {
    lines.push(
      [
        String(t.turn).padEnd(4),
        cell(t.silenceToAsr).padEnd(10),
        cell(t.asrToTurn).padEnd(6),
        cell(t.turnToAudio).padEnd(11),
        cell(t.firstWordMs).padEnd(11),
        cell(t.playbackMs).padEnd(7),
        cell(t.agentMs).padEnd(9),
        (t.interrupted ? 'yes' : '').padEnd(4),
        t.text,
      ].join(' '),
    );
  }

  const first = summarize(turns.map((t) => t.firstWordMs));
  const parts = summarize(turns.map((t) => t.turnToAudio));

  lines.push('');
  lines.push('单位：毫秒。首字总延迟 = ① + ② + ③');
  lines.push(
    `首字延迟中位 ${cell(first.median)} / p90 ${cell(first.p90)} / 最大 ${cell(first.max)}`
    + `（预算 ${FIRST_WORD_BUDGET_MS}）`,
  );
  lines.push(
    `其中③   中位 ${cell(parts.median)} / p90 ${cell(parts.p90)} / 最大 ${cell(parts.max)}`,
  );

  const gaps = new Map<MissingMark, number>();
  for (const t of turns) {
    for (const m of t.missing) {
      gaps.set(m, (gaps.get(m) ?? 0) + 1);
    }
  }
  if (gaps.size > 0) {
    lines.push(
      `⚠ 缺失时刻：${[...gaps].map(([m, n]) => `${m}×${n}`).join('、')}`
      + `（共 ${turns.length} 轮；缺 tts.first 多半是旧档案，`
      + `缺 gate.close 多半是主动播报或这一轮没有静音判停）`,
    );
  }
  if (first.p90 !== null) {
    lines.push(
      first.p90 <= FIRST_WORD_BUDGET_MS
        ? `判定：p90 ${Math.round(first.p90)} ms 在预算内，形态成立。`
        : `判定：p90 ${Math.round(first.p90)} ms 超预算，宠物会显得"迟钝"——先治 ③，别做插件。`,
    );
  }
  return lines.join('\n');
}
