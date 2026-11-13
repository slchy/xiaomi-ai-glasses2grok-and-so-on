/**
 * Smart Turn v3.2 客户端：常驻的 Python 侧车，回答「这一轮说完了吗」。
 *
 * 为什么不是一个纯 TS 实现：模型输入是 Whisper 风格 log-mel（80×800），
 * 要先做 400 点 rFFT（400 = 2^4×5^2，非 2 的幂）。参考实现那份 numpy 实现
 * 是自包含的，直接复用比在 TS 里手写混合基 FFT 可靠。
 *
 * 协议：请求 `[u32 len][int16 PCM]`，响应 `[f64 概率]`，都是小端。
 * 进程常驻，模型只加载一次。
 */
import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';

export interface SmartTurnOptions {
  /** 带 onnxruntime 的 python，通常是项目里的 .venv */
  python: string;
  /** server.py 路径 */
  script: string;
  log?: (line: string) => void;
}

export class SmartTurn {
  private readonly opts: SmartTurnOptions;
  private readonly log: (line: string) => void;
  private proc: ChildProcessWithoutNullStreams | null = null;
  private rbuf: Buffer = Buffer.alloc(0);
  /** 串行化：侧车一次只处理一条请求 */
  private chain: Promise<unknown> = Promise.resolve();
  private pending: ((p: number) => void) | null = null;

  constructor(opts: SmartTurnOptions) {
    this.opts = opts;
    this.log = opts.log ?? (() => {});
  }

  private ensure(): void {
    if (this.proc) return;
    const proc = spawn(this.opts.python, [this.opts.script], {
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
    });
    this.proc = proc;

    proc.stdout.on('data', (d: Buffer) => {
      this.rbuf = this.rbuf.length === 0 ? d : Buffer.concat([this.rbuf, d]);
      while (this.rbuf.length >= 8) {
        const prob = this.rbuf.readDoubleLE(0);
        this.rbuf = this.rbuf.subarray(8);
        const r = this.pending;
        this.pending = null;
        r?.(prob);
      }
    });
    proc.stderr.on('data', (d: Buffer) => {
      const s = d.toString().trim();
      if (s) this.log(`[smart-turn] ${s.slice(0, 200)}`);
    });
    proc.on('exit', (code) => {
      this.log(`[smart-turn] 侧车退出 code=${code}，下次调用会重新拉起`);
      this.proc = null;
      const r = this.pending;
      this.pending = null;
      r?.(-1);
    });
  }

  /**
   * 判断这段音频之后「用户说完了吗」。
   *
   * @param pcm 16 kHz 单声道 PCM16，建议给整轮（截最近 8 秒）
   * @returns 概率 0..1；侧车故障时返回 -1（调用方应退回保守策略）
   */
  async predict(pcm: Buffer): Promise<number> {
    const run = async (): Promise<number> => {
      if (pcm.length === 0) return -1;
      this.ensure();
      const proc = this.proc;
      if (!proc) return -1;
      const head = Buffer.allocUnsafe(4);
      head.writeUInt32LE(pcm.length, 0);
      const done = new Promise<number>((resolve) => {
        this.pending = resolve;
        setTimeout(() => {
          if (this.pending === resolve) {
            this.pending = null;
            resolve(-1);
          }
        }, 5000).unref?.();
      });
      proc.stdin.write(head);
      proc.stdin.write(pcm);
      return await done;
    };

    const next = this.chain.then(run, run);
    this.chain = next.catch(() => undefined);
    return await next;
  }

  stop(): void {
    const p = this.proc;
    this.proc = null;
    try {
      p?.kill('SIGKILL');
    } catch {
      // 已退出
    }
  }
}
