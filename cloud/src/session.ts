/**
 * dsh 会话封装：把一段文本交给 DeepSeek Harness，拿回回复文本。
 *
 * 实测事实（2026-11-05，Oracle aarch64，dsh 0.2.1-rc.2）：
 * - `dsh headless --json -` 从 stdin 读任务，stdout 输出 NDJSON
 * - `{"type":"session","sessionId":"session-…","cwd":"…"}` 给出会话 id
 * - `{"type":"final","text":"…"}` 是最终回复，status 事件里带 usage
 * - `--session-id <id>` 续同一会话；**未知 id 是硬错误**
 *
 * 三条硬约束（都是实测踩出来的，不是推测）：
 *
 * 1. **会话绑定创建目录**。dsh 拒绝在别的目录续会话（"was recorded in X,
 *    not Y"），所以 workDir 必须固定，且每次调用的 cwd 都一致。
 * 2. **上下文膨胀靠压缩，不靠轮换**。`dsh-compaction-basic` 在 base 配置里
 *    默认挂载，token 压力上来会自动把旧历史压成摘要，溢出后还会"先压缩再
 *    重试"。所以这里只持久化 id，不设 maxUses、不主动换会话。
 * 3. **续会话失败必须自愈**。带 id 调用失败时清空记录、重开一次，
 *    否则 agent 会永久卡在一个坏 id 上。
 *
 * 会话文件默认放在 workDir **之外**：workspace-write 沙箱允许 agent 写
 * workDir 内的一切，放里面它就能改自己的会话记录。
 */
import { randomUUID } from 'node:crypto';
import { spawn, type ChildProcess } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';

import { SpontaneousTurn } from './spontaneous.ts';

/** 单轮超时。agent 可能跑工具，给得比纯对话宽。 */
const DEFAULT_TIMEOUT_MS = 180_000;

export interface TokenUsage {
  inputTokens?: number;
  outputTokens?: number;
  cacheReadTokens?: number;
  totalTokens?: number;
}

export interface AskResult {
  text: string;
  /** 本次实际使用的会话 id（新会话时是刚拿到的） */
  sessionId: string | null;
  usage: TokenUsage | null;
  durationMs: number;
  /** 是否沿用了已有会话 */
  resumed: boolean;
}

/**
 * 随提示一起下发的图片（ACP `image` content block）。
 *
 * 实测（2026-11-06）：`deepseek-flash` 的 `input_modalities` 是 `["text","image"]`，
 * 而 dsh 的 ACP 层本来就支持 image 块，mimeType 只收
 * png / jpeg / webp / gif，且 data 必须是**标准 base64**（不能带 `data:` 前缀）。
 */
export interface ImageAttachment {
  mimeType: 'image/png' | 'image/jpeg' | 'image/webp' | 'image/gif';
  /** 标准 base64，不带 data URL 前缀 */
  data: string;
}

export interface SessionOptions {
  /**
   * dsh 可执行文件。服务器上 `~/node22/bin` 只在交互式 zsh 的 PATH 里，
   * 非交互式（systemd / ssh 命令）拿不到，所以建议用 DSH_BIN 指定绝对路径。
   */
  program?: string;
  /** 固定工作目录：既是 dsh 的 cwd，也是沙箱的 workspaceRoot */
  workDir: string;
  /** 会话 id 的持久化位置，默认 ~/.voice-agent/session.json */
  sessionFile?: string;
  timeoutMs?: number;
  /** 额外的 profile patch 文件（用于挂 MCP 工具等） */
  patch?: string;
  log?: (line: string) => void;
  /**
   * 调用方式。
   *
   * - `headless`：每轮起一个新进程（`dsh headless --json`），简单但每轮多 2–3 s 冷启动。
   * - `sdk`：一个常驻进程通过 stdio JSON-RPC 服务多轮（`dsh --profile sdk`），
   *   省掉进程启动与会话重加载。会话 id 由客户端指定，所以首次自己生成。
   */
  mode?: 'headless' | 'sdk';
  /** sdk 模式必填：provider 路由，实测为 deepseek-official */
  provider?: string;
  /** sdk 模式必填：模型名。实测可用 deepseek-flash / deepseek-v4-pro */
  model?: string;
}

export class DshError extends Error {}

interface RunOutcome {
  code: number;
  stdout: string;
  stderr: string;
  timedOut: boolean;
}

/** 从 NDJSON 事件流里取会话 id、最终文本、usage 与流式文本。 */
export function parseEvents(stdout: string): {
  sessionId: string | null;
  final: string | null;
  streamed: string;
  usage: TokenUsage | null;
} {
  let sessionId: string | null = null;
  let final: string | null = null;
  let streamed = '';
  let usage: TokenUsage | null = null;

  for (const line of stdout.split(/\r?\n/)) {
    const s = line.trim();
    if (!s.startsWith('{')) continue;
    let e: Record<string, unknown>;
    try {
      e = JSON.parse(s) as Record<string, unknown>;
    } catch {
      continue; // 半行/非 JSON，跳过
    }
    const type = e['type'];
    if (type === 'session') {
      const id = e['sessionId'];
      if (typeof id === 'string' && id) sessionId = id;
    } else if (type === 'final') {
      const t = e['text'];
      if (typeof t === 'string') final = t;
    } else if (type === 'text') {
      // --json 模式下 text 是流式增量；final 缺失时用它兜底
      const t = e['text'];
      if (typeof t === 'string') streamed += t;
    } else if (type === 'status') {
      const u = e['usage'];
      if (u && typeof u === 'object') usage = u as TokenUsage;
    }
  }

  return { sessionId, final, streamed, usage };
}

export class DshSession {
  private readonly program: string;
  private readonly workDir: string;
  private readonly sessionFile: string;
  private readonly timeoutMs: number;
  private readonly patch?: string;
  private readonly log: (line: string) => void;
  private readonly mode: 'headless' | 'sdk';
  private readonly provider: string;
  private readonly model: string;

  // ---------------------------------------------------------------- sdk 常驻态
  private proc: ChildProcess | null = null;
  private buf = '';
  private nextId = 1;
  private readonly pending = new Map<
    number,
    { resolve: (v: unknown) => void; reject: (e: Error) => void }
  >();
  /** 最近一条 assistant/message 的文本；多 step 时后者覆盖前者 */
  private assistantText = '';
  /** turn 结束（agent 整体 idle）时触发 */
  private onIdle: (() => void) | null = null;
  private sdkReady = false;
  /**
   * 后台任务完成后**自发**产生的汇报文本（不属于任何一轮）。
   *
   * dsh 的后台作业结算后会自己开一轮说结果（没有 `session/prompt`），这条回调
   * 就是接住它的唯一入口 —— 不接就是静默丢弃。
   */
  onAnnounce: ((text: string) => void) | null = null;
  /**
   * 自发轮次的判定：只在 prompt 期间挂 `onIdle`，所以那种轮次必须单独识别
   * （见 `spontaneous.ts` 的实测记录）。
   */
  private readonly spontaneous = new SpontaneousTurn();
  /**
   * 本进程内已经打开（resume 过或创建过）的会话 id。
   *
   * SDK 对已在当前进程打开的会话再发 `session/resume` 会报
   * "SDK session is already open or opening"。所以同一个 id 只能 resume 一次，
   * 后续轮次直接 prompt —— 会话本来就在这个常驻进程里活着。
   */
  private openSessionId: string | null = null;

  constructor(opts: SessionOptions) {
    this.program = opts.program ?? process.env['DSH_BIN'] ?? 'dsh';
    this.workDir = opts.workDir;
    this.patch = opts.patch;
    this.sessionFile = opts.sessionFile
      ?? join(homedir(), '.voice-agent', 'session.json');
    this.timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS;
    this.log = opts.log ?? (() => {});
    this.mode = opts.mode ?? 'headless';
    this.provider = opts.provider ?? 'deepseek-official';
    this.model = opts.model ?? 'deepseek-flash';
    mkdirSync(dirname(this.sessionFile), { recursive: true });
    // spawn 的 cwd 必须存在，否则 ENOENT，且报错信息指向不明确
    mkdirSync(this.workDir, { recursive: true });
  }

  /** 当前记录的会话 id，没有就是 null。 */
  get currentSessionId(): string | null {
    return this.readSession();
  }

  /** 主动丢弃会话，下次 ask 会开新的。 */
  reset(): void {
    try {
      if (existsSync(this.sessionFile)) writeFileSync(this.sessionFile, '{}');
    } catch {
      // 写不进去也不影响下次调用：readSession 失败就当没有
    }
  }

  /**
   * 问一次。沿用已记录的会话；失败则清空记录重开一次。
   *
   * 只有"带着存量 id"才会多试一次；全新会话失败就直接抛，
   * 避免把真实错误（比如缺 API key）当成会话失效反复重试。
   */
  async ask(text: string, image?: ImageAttachment): Promise<AskResult> {
    if (this.mode === 'sdk') return await this.askSdk(text, image);
    if (image) {
      throw new DshError('headless 模式的 stdin 只收纯文本，带图片请用 --dsh-sdk');
    }

    const stored = this.readSession();
    if (stored) {
      try {
        return await this.run(text, stored);
      } catch (e) {
        this.log(`会话 ${stored} 失败，改开新会话：${(e as Error).message}`);
        this.reset();
      }
    }
    return await this.run(text, null);
  }

  /** 关掉常驻进程（有的话）。headless 模式无副作用。 */
  stop(): void {
    const proc = this.proc;
    if (!proc) return;
    this.proc = null;
    this.sdkReady = false;
    this.openSessionId = null;
    try {
      proc.stdin?.write(
        `${JSON.stringify({ jsonrpc: '2.0', id: this.nextId++, method: 'shutdown', params: {} })}\n`,
      );
    } catch {
      // 已经退了
    }
    // 给 shutdown 一点时间优雅退出，超时就强杀
    setTimeout(() => {
      try {
        proc.kill();
      } catch {
        // 已经退了
      }
    }, 2000).unref?.();
  }

  // ---------------------------------------------------------------- sdk 常驻
  /**
   * 一轮对话：`session/prompt` 入队，等 `session.status=idle` 收尾。
   *
   * 注意 SDK 协议里没有「最终回答」这种事件：`session/prompt` 只回一个
   * messageId，内容全靠 `session.event` 推。所以这里取**最后一条**
   * `assistant/message` 的 text 块 —— 多 step 时前面的都是中间过程。
   */
  private async askSdk(text: string, image?: ImageAttachment): Promise<AskResult> {
    await this.ensureSdk();

    const started = Date.now();
    const stored = this.readSession();
    let sessionId = stored ?? `session-${randomUUID()}`;
    let resumed = stored !== null;
    if (!resumed) this.saveSession(sessionId);

    // 已持久化的会话必须先 resume。SDK 的 session/prompt 只对**未知** id
    // 懒创建；对已存在的 id 直接 prompt 会报 "session ... already exists"（实测）。
    // 但 resume 只在本进程内做一次：会话已经活着时再 resume 会报
    // "SDK session is already open or opening"。
    if (resumed && this.openSessionId !== sessionId) {
      try {
        await this.rpc('session/resume', { sessionId });
        this.openSessionId = sessionId;
      } catch (e) {
        this.log(`恢复 ${sessionId} 失败，改开新会话：${(e as Error).message}`);
        sessionId = `session-${randomUUID()}`;
        resumed = false;
        this.saveSession(sessionId);
        this.openSessionId = sessionId;
      }
    } else if (!resumed) {
      this.openSessionId = sessionId;
    }

    this.assistantText = '';
    // 接下来这一轮的 idle 归 prompt 等待者，不算自发轮次
    this.spontaneous.reset();
    const idle = new Promise<void>((resolve) => { this.onIdle = resolve; });

    await this.rpc('session/prompt', {
      sessionId,
      contentBlocks: image
        ? [
            { type: 'text', text },
            { type: 'image', mimeType: image.mimeType, data: image.data },
          ]
        : [{ type: 'text', text }],
    });

    let timer: NodeJS.Timeout | undefined;
    const timeout = new Promise<never>((_, reject) => {
      timer = setTimeout(
        () => reject(new DshError(`等待 turn 结束超时（${this.timeoutMs} ms）`)),
        this.timeoutMs,
      );
    });
    try {
      await Promise.race([idle, timeout]);
    } finally {
      clearTimeout(timer);
      this.onIdle = null;
    }

    const answer = this.assistantText.trim();
    if (!answer) throw new DshError('sdk 没有返回任何文本');

    return {
      text: answer,
      sessionId,
      usage: null,
      durationMs: Date.now() - started,
      resumed,
    };
  }

  /** 懒启动常驻进程并握手。崩溃后下次调用会自动重启。 */
  private async ensureSdk(): Promise<void> {
    if (this.proc && this.sdkReady) return;
    if (this.proc) this.stop();

    const proc = this.spawnProgram([
      '--profile', 'sdk',
      ...(this.patch ? ['--patch', this.patch] : []),
    ]);
    this.proc = proc;

    proc.stdout?.on('data', (d: Buffer) => { this.handleData(d); });
    proc.stderr?.on('data', (d: Buffer) => {
      const s = d.toString().trim();
      // SDK profile 把诊断写 stderr，stdout 专用于协议帧
      if (s) this.log(`[sdk] ${s.slice(0, 200)}`);
    });
    proc.on('error', (e: Error) => {
      this.log(`[sdk] 启动失败：${e.message}`);
      this.sdkReady = false;
      if (this.proc === proc) this.proc = null;
    });
    proc.on('close', (code: number | null) => {
      this.sdkReady = false;
      // 进程没了，里面的会话也就不在了；下次必须重新 resume
      this.openSessionId = null;
      if (this.proc === proc) this.proc = null;
      // 没收到 shutdown 就退出，说明崩了；挂起的请求全部失败
      for (const [, p] of this.pending) p.reject(new DshError(`sdk 进程退出（code=${code}）`));
      this.pending.clear();
      this.log(`[sdk] 进程退出 code=${code}`);
    });

    // 首次握手要加载 profile 与工具树，比普通请求慢，单独放宽
    await this.rpc('initialize', {
      cwd: this.workDir,
      provider: this.provider,
      model: this.model,
    }, 120_000);
    this.sdkReady = true;
    this.log(`[sdk] 已就绪 provider=${this.provider} model=${this.model}`);
  }

  private rpc(method: string, params: unknown, timeoutMs = this.timeoutMs): Promise<unknown> {
    return new Promise<unknown>((resolve, reject) => {
      const stdin = this.proc?.stdin;
      if (!stdin?.writable) {
        reject(new DshError(`sdk 未就绪，无法调用 ${method}`));
        return;
      }
      const id = this.nextId++;
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new DshError(`${method} 超时（${timeoutMs} ms）`));
      }, timeoutMs);

      this.pending.set(id, {
        resolve: (v) => { clearTimeout(timer); resolve(v); },
        reject: (e) => { clearTimeout(timer); reject(e); },
      });

      stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
    });
  }

  private handleData(chunk: Buffer): void {
    this.buf += chunk.toString('utf8');
    let nl = this.buf.indexOf('\n');
    while (nl >= 0) {
      const line = this.buf.slice(0, nl).trim();
      this.buf = this.buf.slice(nl + 1);
      nl = this.buf.indexOf('\n');
      if (line) this.handleLine(line);
    }
  }

  private handleLine(line: string): void {
    let msg: Record<string, unknown>;
    try {
      msg = JSON.parse(line) as Record<string, unknown>;
    } catch {
      return; // 非协议帧（不该出现，stdout 专用于 JSON-RPC）
    }

    const id = msg['id'];
    const method = msg['method'];
    const params = msg['params'];

    // 响应：只有 id，没有 method
    if (typeof id === 'number' && method === undefined) {
      const p = this.pending.get(id);
      if (!p) return;
      this.pending.delete(id);
      const err = msg['error'];
      if (err) p.reject(new DshError(`JSON-RPC 错误：${JSON.stringify(err).slice(0, 300)}`));
      else p.resolve(msg['result']);
      return;
    }

    if (typeof method !== 'string' || !params || typeof params !== 'object') return;
    const p = params as Record<string, unknown>;

    if (method === 'session.event') {
      const ev = p['event'];
      if (!ev || typeof ev !== 'object') return;
      const e = ev as Record<string, unknown>;
      if (e['type'] !== 'assistant/message') return;

      const data = e['data'] as Record<string, unknown> | undefined;
      const message = data?.['message'] as Record<string, unknown> | undefined;
      const content = message?.['content'];
      if (!Array.isArray(content)) return;

      const parts: string[] = [];
      for (const c of content) {
        if (!c || typeof c !== 'object') continue;
        const block = c as Record<string, unknown>;
        if (block['type'] === 'text' && typeof block['text'] === 'string') parts.push(block['text']);
      }
      if (parts.length > 0) this.assistantText = parts.join('');
      return;
    }

    if (method === 'session.status') {
      const status = typeof p['status'] === 'string' ? p['status'] : '';
      const waiting = this.onIdle !== null;
      const verdict = this.spontaneous.onStatus(status, waiting);
      if (verdict === 'begin') {
        // 自发轮次开始：清掉上一轮的残留文本，否则会把旧回复当成本次结果播出去
        this.assistantText = '';
        this.log('[sdk] 后台任务结算 → agent 自发开了一轮（不是我们催的）');
        return;
      }
      if (verdict === 'settle') {
        const text = this.assistantText.trim();
        this.assistantText = '';
        if (text && this.onAnnounce) {
          this.onAnnounce(text);
        }
        return;
      }
      if (status === 'idle' && this.onIdle) {
        const fire = this.onIdle;
        this.onIdle = null;
        fire();
      }
    }
  }

  // ---------------------------------------------------------------- 内部
  private readSession(): string | null {
    try {
      const raw = JSON.parse(readFileSync(this.sessionFile, 'utf8')) as { id?: unknown };
      return typeof raw.id === 'string' && raw.id ? raw.id : null;
    } catch {
      return null;
    }
  }

  private saveSession(id: string): void {
    try {
      writeFileSync(this.sessionFile, JSON.stringify({ id, at: new Date().toISOString() }));
    } catch (e) {
      // 存不下来只是下次开新会话，不该让本轮失败
      this.log(`会话 id 保存失败：${(e as Error).message}`);
    }
  }

  private async run(text: string, sessionId: string | null): Promise<AskResult> {
    const args = [
      'headless',
      '--json',
      ...(sessionId ? ['--session-id', sessionId] : []),
      '-', // 任务从 stdin 读，避免命令行长度与转义问题
    ];
    const started = Date.now();
    const r = await this.exec(args, text);
    const { sessionId: sid, final, streamed, usage } = parseEvents(r.stdout);

    if (r.timedOut) {
      throw new DshError(`dsh 超时（${this.timeoutMs} ms）`);
    }
    if (r.code !== 0) {
      const tail = r.stderr.trim().split(/\r?\n/).slice(-3).join(' | ');
      throw new DshError(`dsh 退出码 ${r.code}${tail ? `：${tail}` : ''}`);
    }

    const answer = (final ?? streamed).trim();
    if (!answer) {
      throw new DshError('dsh 没有返回任何文本');
    }
    if (sid) this.saveSession(sid);

    return {
      text: answer,
      sessionId: sid,
      usage,
      durationMs: Date.now() - started,
      resumed: sessionId !== null,
    };
  }

  /**
   * `spawn` 一个可能是 npm shim 的 program。
   *
   * Windows 上 `.cmd` / `.bat` 不是可执行映像，直接 spawn 会 `EINVAL`；
   * 用 `cmd.exe /c <shim> ...` 启动，比 `shell: true` 更可控
   * （后者会把参数直接拼进命令行，Node 会报 DEP0190）。
   */
  private spawnProgram(args: string[]) {
    const isShim = process.platform === 'win32' && /\.(cmd|bat)$/i.test(this.program);
    const argv = isShim ? ['/c', this.program, ...args] : args;
    const command = isShim ? (process.env['COMSPEC'] ?? 'cmd.exe') : this.program;
    return spawn(command, argv, {
      cwd: this.workDir,
      env: process.env,
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
    });
  }

  private exec(args: string[], input: string): Promise<RunOutcome> {
    return new Promise<RunOutcome>((resolve, reject) => {
      let child;
      try {
        child = this.spawnProgram(args);
      } catch (e) {
        reject(new DshError(`无法启动 ${this.program}：${(e as Error).message}`));
        return;
      }

      let stdout = '';
      let stderr = '';
      let timedOut = false;
      let settled = false;

      const timer = setTimeout(() => {
        timedOut = true;
        try {
          child.kill('SIGKILL');
        } catch {
          // 已经退了
        }
      }, this.timeoutMs);

      const finish = (code: number): void => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolve({ code, stdout, stderr, timedOut });
      };

      child.stdout.on('data', (d: Buffer) => {
        stdout += d.toString('utf8');
      });
      child.stderr.on('data', (d: Buffer) => {
        stderr += d.toString('utf8');
      });
      child.on('error', (e: Error) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        reject(new DshError(`dsh 启动失败：${e.message}`));
      });
      child.on('close', (code: number | null) => finish(code ?? -1));

      child.stdin.on('error', () => {
        // dsh 提前退出时写 stdin 会 EPIPE，忽略
      });
      child.stdin.end(input, 'utf8');
    });
  }
}
