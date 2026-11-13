/**
 * 连接层：accept、帧编解码、首帧鉴权、心跳/空闲看门狗。
 *
 * **这一层不认识任何业务。** 它只做两件事：
 * - 把字节变成 `onFrame(type, payload)` 事件
 * - 把 `sendAudio / sendControl / close` 变成字节
 *
 * 音频、判停、对话、拍照全在它之上（见 `aliyun-session.ts`）。抽出来的意义：
 * 以前这些和 socket 回调混在一个 600 行的类里，改心跳要动对话代码。
 */
import { createServer, type Server, type Socket } from 'node:net';
import { createServer as createTlsServer } from 'node:tls';

import {
  DownstreamFrameType,
  FrameDecoder,
  FrameWriter,
  UpstreamFrameType,
} from './frame.ts';

/** `Hello` 帧的内容。字段都可选，方便别人只报自己想报的。 */
export interface HelloInfo {
  readonly proto?: number;
  readonly deviceId?: string;
  readonly token?: string;
}

/** 解析 `Hello`；坏 JSON 返回 null（由调用方决定拒绝还是忽略）。 */
export function parseHello(payload: Buffer): HelloInfo | null {
  try {
    const parsed: unknown = JSON.parse(payload.toString('utf8'));
    if (parsed === null || typeof parsed !== 'object') {
      return null;
    }
    const o = parsed as Record<string, unknown>;
    return {
      proto: typeof o.proto === 'number' ? o.proto : undefined,
      deviceId: typeof o.deviceId === 'string' ? o.deviceId : undefined,
      token: typeof o.token === 'string' ? o.token : undefined,
    };
  } catch {
    return null;
  }
}

/** 业务层只实现这两个回调。 */
export interface ConnectionHandlers {
  onFrame(type: number, payload: Buffer): void;
  onClose(reason: string): void;
}

/** 业务层往设备发东西只需要这两个动作；`Connection` 天然满足它。 */
export interface DownlinkSink {
  sendAudio(pcm: Buffer): void;
  sendControl(text: string): void;
}

/** 一条已建立的连接。业务层只碰这个接口，不碰 socket。 */
export class Connection {
  readonly remote: string;

  private authDeviceId?: string;
  private readonly socket: Socket;
  private readonly frames: FrameWriter;
  private readonly decoder: FrameDecoder;
  private readonly log: (line: string) => void;
  private readonly authenticate?: (hello: HelloInfo) => boolean;
  private readonly idleTimeoutMs: number;

  private handlers: ConnectionHandlers | null = null;
  private readonly onClosed?: () => void;
  private authed: boolean;
  private lastRxAt = Date.now();
  private idleTimer: NodeJS.Timeout | null = null;
  /** `end()` 已调用但 'close' 还没来 —— 这期间必须停止收发。 */
  private closing = false;
  private closed = false;

  constructor(
    socket: Socket,
    log: (line: string) => void,
    authenticate: ((hello: HelloInfo) => boolean) | undefined,
    idleTimeoutMs: number,
    onClosed?: () => void,
  ) {
    this.socket = socket;
    this.log = log;
    this.authenticate = authenticate;
    this.idleTimeoutMs = idleTimeoutMs;
    this.onClosed = onClosed;
    this.remote = `${socket.remoteAddress}:${socket.remotePort}`;
    this.frames = new FrameWriter(socket);
    this.decoder = new FrameDecoder((message) => { log(`  ✗ ${message}`); });
    this.authed = authenticate === undefined;
    this.authDeviceId = undefined;

    socket.setNoDelay(true); // 小帧 + 实时语音：别让 Nagle 攒包
    socket.on('data', (chunk: Buffer) => { this.onData(chunk); });
    socket.on('error', (error: Error) => { log(`socket 错误: ${error.message}`); });
    socket.on('close', () => { this.finish('socket closed'); });

    this.idleTimer = setInterval(() => { this.checkIdle(); }, 5_000);
  }

  /**
   * 注册业务回调，**同一 tick 内注册**。
   *
   * 帧在注册之前到达会被丢弃 —— 连接刚建立时不会有帧，所以实践上安全；
   * 真要更严谨可以在未注册时缓冲，但那是没人会用到的复杂度。
   */
  bind(handlers: ConnectionHandlers): void {
    this.handlers = handlers;
  }

  /** 鉴权得到的设备 ID（没配鉴权时为 undefined）。 */
  get deviceId(): string | undefined {
    return this.authDeviceId;
  }

  get isAuthed(): boolean {
    return this.authed;
  }

  get isClosed(): boolean {
    return this.closed;
  }

  /** 下行音频（PCM16）。 */
  sendAudio(pcm: Buffer): void {
    if (!this.closed && !this.closing) {
      this.frames.write(DownstreamFrameType.Audio, pcm);
    }
  }

  /** 下行控制串：`take_photo` / `stop` / `end_session` / `auth_*`。 */
  sendControl(text: string): void {
    if (!this.closed && !this.closing) {
      this.frames.writeText(DownstreamFrameType.Control, text);
    }
  }

  /** 回一条控制串再关（鉴权失败等）。 */
  closeWith(reason: string): void {
    this.sendControl(reason);
    this.end();
  }

  end(): void {
    this.closing = true;
    if (!this.socket.destroyed) {
      this.socket.end();
    }
  }

  destroy(): void {
    if (!this.socket.destroyed) {
      this.socket.destroy();
    }
  }

  // ------------------------------------------------------------------ 内部

  private onData(chunk: Buffer): void {
    this.lastRxAt = Date.now();
    for (const frame of this.decoder.push(chunk)) {
      this.handle(frame.type, frame.payload);
    }
  }

  private handle(type: number, payload: Buffer): void {
    if (this.closed || this.closing) {
      return; // 已决定关闭：同一次读里剩下的帧不再处理
    }
    if (type === UpstreamFrameType.Hello) {
      this.onHello(payload);
      return;
    }
    if (type === UpstreamFrameType.Ping) {
      // 心跳不必等鉴权：它不携带任何信息，也不触发业务
      if (!this.closed) {
        this.frames.write(DownstreamFrameType.Pong, Buffer.alloc(0));
      }
      return;
    }
    if (!this.authed) {
      this.log(`  鉴权未收到 Hello 就发帧（type=${type}），断开`);
      this.closeWith('auth_required');
      return;
    }
    this.handlers?.onFrame(type, payload);
  }

  private onHello(payload: Buffer): void {
    if (this.authed) {
      return; // 已经放行过，重复 Hello 忽略
    }
    const hello = parseHello(payload);
    if (!hello || !this.authenticate || !this.authenticate(hello)) {
      this.log(`  鉴权拒绝（deviceId=${hello?.deviceId ?? '?'}）`);
      this.closeWith('auth_failed');
      return;
    }
    this.authed = true;
    this.authDeviceId = hello.deviceId;
    this.log(`  鉴权通过（deviceId=${hello.deviceId ?? '?'} proto=${hello.proto ?? '?'}）`);
  }

  private checkIdle(): void {
    if (this.closed) {
      return;
    }
    const quietMs = Date.now() - this.lastRxAt;
    if (quietMs > this.idleTimeoutMs) {
      this.log(`  连接    ${Math.round(quietMs / 1000)} s 无数据，主动断开`);
      this.end();
    }
  }

  private finish(reason: string): void {
    if (this.closed) {
      return;
    }
    this.closed = true;
    if (this.idleTimer) {
      clearInterval(this.idleTimer);
      this.idleTimer = null;
    }
    this.handlers?.onClose(reason);
    this.onClosed?.();
  }
}

export interface TransportOptions {
  readonly port: number;
  /** 缺省 `127.0.0.1`：本地开发默认不暴露；上公网由部署方显式给。 */
  readonly host?: string;
  readonly tls?: { readonly key: Buffer; readonly cert: Buffer };
  readonly authenticate?: (hello: HelloInfo) => boolean;
  /** 这么久没收到任何字节就断开（默认 45 s）。 */
  readonly idleTimeoutMs?: number;
  /**
   * 单设备模式（默认 true）：只接一条连接，连接结束就关监听、返回退出码。
   *
   * 多设备时改 false 并把连接表交给业务层 —— 这正是把这层独立出来的原因之一。
   */
  readonly singleConnection?: boolean;
  readonly log: (line: string) => void;
  /** 连接建立：在这里 `conn.bind(...)` 登记业务回调。 */
  onConnection(conn: Connection): void;
}

/** 起监听，返回进程退出码。 */
export async function runTransport(options: TransportOptions): Promise<number> {
  const host = options.host ?? '127.0.0.1';
  const idleTimeoutMs = options.idleTimeoutMs ?? 45_000;
  const single = options.singleConnection !== false;

  return await new Promise<number>((resolve) => {
    let server: Server;
    let finished = false;

    const finish = (code: number): void => {
      if (finished) {
        return;
      }
      finished = true;
      server.close();
      resolve(code);
    };

    const onConnection = (socket: Socket): void => {
      const conn = new Connection(
        socket,
        options.log,
        options.authenticate,
        idleTimeoutMs,
        single ? () => { finish(0); } : undefined,
      );
      options.onConnection(conn);
      if (single) {
        server.close(); // 不再收第二条
      }
    };

    // TLS 只在部署方显式给了证书时才启用 —— 两条路径是同一份代码的两种配置
    server = options.tls
      ? createTlsServer(
        { key: options.tls.key, cert: options.tls.cert, minVersion: 'TLSv1.2' },
        (socket) => { onConnection(socket as unknown as Socket); },
      )
      : createServer((socket) => { onConnection(socket); });

    server.on('error', (error: Error) => {
      options.log(`监听失败: ${error.message}`);
      finish(1);
    });

    server.listen(options.port, host, () => {
      const scheme = options.tls ? 'tls' : 'tcp';
      const auth = options.authenticate ? '需要 Hello 鉴权' : '不鉴权';
      options.log(`监听 ${scheme}://${host}:${options.port}（${auth}）`);
      if (host === '127.0.0.1') {
        options.log(`手机侧先执行： adb reverse tcp:${options.port} tcp:${options.port}`);
      }
    });
  });
}
