/**
 * 阿里云 NLS 实时语音识别（SpeechTranscriber）客户端。
 *
 * 协议要点全部是实测撞出来的，参考实现示例在
 * `aliyun/alibabacloud-nls-nodejs-sdk/lib/st.js`：
 *
 *   1. `message_id` / `task_id` 必须是 **32 位纯十六进制**（uuid 去掉连字符）
 *   2. `task_id` 由**客户端生成**，StopTranscription 要用同一个
 *   3. StartTranscription 的 header 需要 `name` + `namespace` + `appkey`
 *   4. 顶层必须有 `context`（{ sdk: {...} }），缺了会回误导性的
 *      `Gateway:MESSAGE_INVALID:Missing message name!`
 *   5. token 走 URL query（Node 内置 WebSocket 不支持自定义 header，
 *      实测 query 可用）
 *
 * 判停交给服务端：`SentenceEnd` 就是「这句说完了」，不需要本地 VAD 猜。
 */
import { createHmac, randomUUID } from 'node:crypto';

import type { SttProvider } from './providers.ts';

const SAMPLE_RATE = 16_000;
/** token 提前这么多秒视为过期，避免边界上刚好失效 */
const TOKEN_REFRESH_MARGIN_SEC = 300;

const pct = (s: string): string => encodeURIComponent(s)
  .replace(/\+/g, '%20')
  .replace(/\*/g, '%2A')
  .replace(/%7E/g, '~');

/** NLS 只认 32 位无连字符的十六进制 */
const hex32 = (): string => randomUUID().replace(/-/g, '');

export interface AliyunToken {
  id: string;
  expireAtSec: number;
}

export interface AliyunAsrOptions {
  akId: string;
  akSecret: string;
  appkey: string;
  /** cn-shanghai / ap-southeast-1 等 */
  region: string;
  /** 一句结束后判定「说完了」所需的静音时长（毫秒） */
  maxSentenceSilenceMs?: number;
  /** 中间结果（边说边出字），可选 */
  onPartial?: (text: string) => void;
  /** 一句说完，text 是完整句子 */
  onSentence: (text: string, index: number) => void;
  onError?: (e: Error) => void;
  log?: (line: string) => void;
}

export class AliyunAsrError extends Error {}

/** 用 AK/SK 换临时 token。有效期 24 h，调用方应缓存。 */
export async function createToken(
  akId: string,
  akSecret: string,
  region: string,
): Promise<AliyunToken> {
  const params: Record<string, string> = {
    AccessKeyId: akId,
    Action: 'CreateToken',
    Format: 'JSON',
    RegionId: region,
    SignatureMethod: 'HMAC-SHA1',
    SignatureNonce: randomUUID(),
    SignatureVersion: '1.0',
    Timestamp: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
    Version: '2019-02-28',
  };
  const canonical = Object.keys(params)
    .sort()
    .map((k) => `${pct(k)}=${pct(params[k] ?? '')}`)
    .join('&');
  const stringToSign = `POST&${pct('/')}&${pct(canonical)}`;
  const signature = createHmac('sha1', `${akSecret}&`).update(stringToSign).digest('base64');

  const res = await fetch(`https://nls-meta.${region}.aliyuncs.com/`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: `${canonical}&Signature=${pct(signature)}`,
  });
  const text = await res.text();
  if (!res.ok) throw new AliyunAsrError(`CreateToken HTTP ${res.status}: ${text.slice(0, 300)}`);

  const json = JSON.parse(text) as { Token?: { Id?: string; ExpireTime?: number } };
  const id = json.Token?.Id;
  const expire = json.Token?.ExpireTime;
  if (!id || !expire) throw new AliyunAsrError(`CreateToken 响应缺字段: ${text.slice(0, 300)}`);
  return { id, expireAtSec: expire };
}

interface TokenCache {
  token: AliyunToken | null;
}

/**
 * 一条 WebSocket 连接 = 一个持续识别任务。
 *
 * 多轮对话复用同一条连接：服务端会持续发 SentenceBegin/SentenceEnd，
 * 每句独立编号（payload.index）。
 */
export class AliyunAsr {
  private readonly opts: AliyunAsrOptions;
  private ws: WebSocket | null = null;
  private taskId: string | null = null;
  private ready = false;
  private readonly cache: TokenCache = { token: null };
  private readonly log: (line: string) => void;
  /** 缓冲区：WebSocket 未 ready 时先攒着，别丢音频 */
  private backlog: Buffer[] = [];
  /**
   * true 时丢弃一切输入。
   *
   * 只在 `close()` 置位：表示本条会话已结束，之后即使还有上游音频推来
   * 也不该再进 backlog。**播放期不置位** —— 打断/全双工要求播报期间
   * 上行持续送识别（见 `` ）。
   */
  private dropping = false;
  /**
   * connect() 正在进行。
   *
   * 必须单独记：connect 要 await token（首次是 1 s 级别的网络请求），
   * 这期间 `ws` 仍是 null。只靠 `!this.ws` 判断的话，每来一个音频 chunk
   * 就发起一次新连接，几十个并发连接互相打架（实测刷出十几次
   * `IDLE_TIMEOUT ... last directive is 'null'`）。
   */
  private connecting = false;

  constructor(opts: AliyunAsrOptions) {
    this.opts = opts;
    this.log = opts.log ?? (() => {});
  }

  get isReady(): boolean {
    return this.ready && this.ws?.readyState === WebSocket.OPEN;
  }

  private async token(): Promise<AliyunToken> {
    const now = Math.floor(Date.now() / 1000);
    const cur = this.cache.token;
    if (cur && cur.expireAtSec - now > TOKEN_REFRESH_MARGIN_SEC) return cur;
    const fresh = await createToken(this.opts.akId, this.opts.akSecret, this.opts.region);
    this.cache.token = fresh;
    return fresh;
  }

  async connect(): Promise<void> {
    if (this.ws || this.connecting) return;
    this.connecting = true;
    try {
      // 不在 connect 里清 dropping：
      // dropping 只表示「本条会话已结束」，播放期并不置位。
      // 解 dropping 的时机只有两个：初始值，以及显式 resume()。
      this.backlog = [];
      const token = await this.token();
    const url = `wss://nls-gateway-${this.opts.region}.aliyuncs.com/ws/v1`
      + `?token=${encodeURIComponent(token.id)}`;

    const ws = new WebSocket(url);
    this.ws = ws;

    await new Promise<void>((resolve, reject) => {
      const onOpen = (): void => { resolve(); };
      const onError = (e: Event): void => {
        reject(new AliyunAsrError(`WebSocket 连接失败: ${String(e)}`));
      };
      ws.addEventListener('open', onOpen, { once: true });
      ws.addEventListener('error', onError, { once: true });
    });

    ws.addEventListener('message', (ev) => { this.onMessage(ev); });
    ws.addEventListener('error', (e) => {
      this.opts.onError?.(new AliyunAsrError(`WebSocket error: ${String(e)}`));
    });
    ws.addEventListener('close', (ev) => {
      this.ready = false;
      this.ws = null;
      this.taskId = null;
      this.log(`[asr] 连接关闭 code=${ev.code} reason=${ev.reason || '无'}`);
    });

    this.startTranscription();
    } finally {
      this.connecting = false;
    }
  }

  private startTranscription(): void {
    const ws = this.ws;
    if (!ws) return;
    this.taskId = hex32();
    ws.send(JSON.stringify({
      header: {
        appkey: this.opts.appkey,
        message_id: hex32(),
        task_id: this.taskId,
        namespace: 'SpeechTranscriber',
        name: 'StartTranscription',
      },
      payload: {
        format: 'pcm',
        sample_rate: SAMPLE_RATE,
        enable_intermediate_result: true,
        enable_punctuation_prediction: true,
        enable_inverse_text_normalization: true,
        // 服务端判停的静音阈值。默认（800 ms）对话场景偏慢：
        // 说完一句要等近 1 s 才发 SentenceEnd。范围 200–2000。
        ...(this.opts.maxSentenceSilenceMs
          ? { max_sentence_silence: this.opts.maxSentenceSilenceMs }
          : {}),
      },
      // 缺 context 会被回误导性的 "Missing message name!"
      context: { sdk: { name: 'nls-nodejs-sdk', version: '0.0.1', language: 'nodejs' } },
    }));
  }

  /** 推音频。16 kHz / 单声道 / PCM16 小端。未 ready 时先缓存。 */
  feed(pcm: Buffer): void {
    if (this.dropping) return;
    // 懒连接：只有真有待推音频时才建连。
    // 空连接会被服务端判 IDLE_TIMEOUT（实测 `Gateway:IDLE_TIMEOUT`）。
    if (!this.ws && !this.connecting) {
      void this.connect().catch((e: Error) => {
        this.opts.onError?.(e);
      });
    }
    if (!this.isReady) {
      this.backlog.push(pcm);
      // 别无限攒：正常情况下 ready 只要几百毫秒
      if (this.backlog.length > 200) this.backlog.shift();
      return;
    }
    this.send(pcm);
    if (this.backlog.length > 0) {
      for (const b of this.backlog) this.send(b);
      this.backlog = [];
    }
  }

  private send(pcm: Buffer): void {
    try {
      this.ws?.send(pcm);
    } catch (e) {
      this.log(`[asr] 发送失败: ${(e as Error).message}`);
    }
  }

  /**
   * 解除 dropping 并按需重建连接。
   *
   * 当前无调用点：`dropping` 只由 `close()` 置位，会话结束后不再恢复。
   * 保留给「需要复用同一实例」的场景（例如显式重连）。
   */
  resume(): void {
    this.dropping = false;
    this.backlog = [];
    if (!this.ws && !this.connecting) {
      void this.connect().catch((e: Error) => {
        this.opts.onError?.(e);
      });
    }
  }

  close(): void {
    this.dropping = true;
    this.hangup();
  }

  /**
   * 挂断连接，但**不改 dropping**。
   *
   * 用于「上行持续静音，主动断开避免被网关判 IDLE_TIMEOUT」——
   * 那种场景后续还要正常收音，不能把 dropping 置上。
   */
  hangup(): void {
    const ws = this.ws;
    if (!ws) return;
    this.backlog = [];
    if (ws.readyState === WebSocket.OPEN && this.taskId) {
      try {
        ws.send(JSON.stringify({
          header: {
            appkey: this.opts.appkey,
            message_id: hex32(),
            task_id: this.taskId,
            namespace: 'SpeechTranscriber',
            name: 'StopTranscription',
          },
          payload: {},
          context: { sdk: { name: 'nls-nodejs-sdk', version: '0.0.1', language: 'nodejs' } },
        }));
      } catch {
        // 已断
      }
    }
    setTimeout(() => {
      try {
        ws.close();
      } catch {
        // 已断
      }
    }, 500).unref?.();
    this.ws = null;
    this.ready = false;
  }

  private onMessage(ev: MessageEvent): void {
    const raw = typeof ev.data === 'string' ? ev.data : '';
    if (!raw) return;
    let msg: {
      header?: { name?: string; status?: number; status_text?: string };
      payload?: { result?: string; index?: number };
    };
    try {
      msg = JSON.parse(raw);
    } catch {
      return;
    }
    const name = msg.header?.name;

    if (name === 'TranscriptionStarted') {
      this.ready = true;
      this.log(`[asr] 已就绪 task=${this.taskId?.slice(0, 8)}…`);
      return;
    }
    if (name === 'TranscriptionResultChanged') {
      const t = msg.payload?.result;
      if (typeof t === 'string' && t) this.opts.onPartial?.(t);
      return;
    }
    if (name === 'SentenceEnd') {
      const t = msg.payload?.result;
      const idx = msg.payload?.index ?? 0;
      if (typeof t === 'string' && t.trim()) this.opts.onSentence(t.trim(), idx);
      return;
    }
    if (name === 'TranscriptionCompleted') {
      this.log('[asr] 识别任务已结束');
      this.ready = false;
      return;
    }
    if (name === 'TaskFailed') {
      const st = msg.header?.status_text ?? `status=${msg.header?.status}`;
      this.opts.onError?.(new AliyunAsrError(`TaskFailed: ${st}`));
      this.ready = false;
      return;
    }
  }
}

/**
 * 把阿里云 NLS 包成 {@link SttProvider}，供编排层注入。
 *
 * 凭据从这里进来（部署方负责，别写进源码）；换参考实现时只需要另一个同接口的
 * 工厂，编排层不动。
 */
export function aliyunStt(
  creds: Omit<AliyunAsrOptions, 'onSentence' | 'onPartial' | 'onError' | 'log'>,
  log?: (line: string) => void,
): SttProvider {
  return {
    name: `aliyun:${creds.region}`,
    open: (handlers) => {
      const asr = new AliyunAsr({
        ...creds,
        onSentence: handlers.onSentence,
        onPartial: handlers.onPartial,
        onError: handlers.onError,
        log,
      });
      return {
        feed: (pcm) => { asr.feed(pcm); },
        hangup: () => { asr.hangup(); },
        close: () => { asr.close(); },
      };
    },
  };
}
