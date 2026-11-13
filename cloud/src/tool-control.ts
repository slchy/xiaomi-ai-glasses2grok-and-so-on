/**
 * 本机控制口：给 dsh 的 MCP 工具回来调用的 HTTP 入口。
 *
 * 工具进程（`mcp/mcp-camera.mjs`、`mcp/mcp-session.mjs`）是 dsh spawn 的
 * 独立进程，只能通过一个 URL 与 harness 通信。本模块只负责 HTTP 层：
 * 路由、并发保护、超时、响应码；真正的硬件动作由调用方注入。
 *
 * - `POST /take_photo`  → 调 {@link ToolControlHandlers.takePhoto} 取 JPEG（低清，快）
 * - `POST /take_photo_hq` → 高清版，见 {@link ToolControlHandlers.takePhotoHq}
 * - `POST /end_session` → 调 {@link ToolControlHandlers.endSession}
 * - `POST /say`         → 当作「用户说了这句话」，起一轮（**调试/联调用**，
 *   没有麦克风也能验证 agent→TTS→下发整条链）
 * - `POST /control`     → 原样下发一条控制串（**只给调试用**：`stop_capture` /
 *   `start_capture` 这类"要让设备配合做动作"的实验，没法从对话里自然触发）
 */
import { createServer, type Server } from 'node:http';
import type { PhotoOutcome } from './photo-inbox.ts';

export interface ToolControlHandlers {
  /** 取一张照片；`ok:false` 表示超时/失败，`reason` 会原样写回模型 */
  readonly takePhoto: () => Promise<PhotoOutcome>;
  /** 取一张**高清**照片（手机侧开一次 P2P 拉原图再缩，需要用户按快门），无此能力时回落到 [takePhoto] */
  readonly takePhotoHq?: () => Promise<PhotoOutcome>;
  /** 模型判定应当结束会话 */
  readonly endSession: () => void;
  /** 注入一句话，按正常轮次处理（调试/联调） */
  readonly say?: (text: string) => void;
  /** 原样下发一条控制串（调试用；`stop_capture` / `start_capture` 这类实验） */
  readonly control?: (text: string) => void;
  readonly log?: (line: string) => void;
}

export interface ToolControlOptions {
  readonly port: number;
  /** `spp` 等图的硬上限（收件箱自己也会超时，这里只是兜底） */
  readonly photoTimeoutMs: number;
  /** `hq` 等图的硬上限：要覆盖「建 P2P 15 s + 等用户按快门 25 s」 */
  readonly hqTimeoutMs?: number;
  readonly handlers: ToolControlHandlers;
}

export interface ToolControl {
  /** 停止监听。连接结束时必须调用，否则下一个连接会撞端口 */
  close: () => void;
}

/**
 * 启动控制口。
 *
 * **每个连接都要新建一个**（端口固定），所以 `error` 必须监听：
 * 上一个连接没关干净时端口会被占，未处理的 `error` 事件会让整个进程退出。
 */
export function startToolControl(options: ToolControlOptions): ToolControl {
  const { handlers } = options;
  let photoInFlight = false;

  const server: Server = createServer((req, res) => {
    if (req.method !== 'POST') {
      res.writeHead(404).end();
      return;
    }

    if (req.url === '/say') {
      const chunks: Buffer[] = [];
      req.on('data', (chunk: Buffer) => { chunks.push(chunk); });
      req.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8').trim();
        if (!text || !handlers.say) {
          res.writeHead(400, { 'content-type': 'text/plain; charset=utf-8' }).end('need text');
          return;
        }
        handlers.log?.(`  注入    ${JSON.stringify(text)}（调试口）`);
        handlers.say(text);
        res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' }).end('ok');
      });
      return;
    }

    if (req.url === '/end_session') {
      handlers.log?.('  退出模型判定该结束了（说完这句就挂断）');
      handlers.endSession();
      res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' }).end('ok');
      return;
    }

    if (req.url === '/control') {
      const chunks: Buffer[] = [];
      req.on('data', (chunk: Buffer) => { chunks.push(chunk); });
      req.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8').trim();
        if (!text || !handlers.control) {
          res.writeHead(400, { 'content-type': 'text/plain; charset=utf-8' }).end('need text');
          return;
        }
        handlers.log?.(`  控制下发 ${JSON.stringify(text)}（调试口）`);
        handlers.control(text);
        res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' }).end('ok');
      });
      return;
    }

    if (req.url !== '/take_photo' && req.url !== '/take_photo_hq') {
      res.writeHead(404).end();
      return;
    }

    const wantHq = req.url === '/take_photo_hq';
    const shooter = wantHq && handlers.takePhotoHq !== undefined ? handlers.takePhotoHq : handlers.takePhoto;

    if (photoInFlight) {
      res.writeHead(409, { 'content-type': 'text/plain; charset=utf-8' })
        .end('已有拍照请求在途');
      return;
    }

    photoInFlight = true;
    const timer = setTimeout(() => {
      photoInFlight = false;
      if (!res.writableEnded) {
        res.writeHead(504, { 'content-type': 'text/plain; charset=utf-8' })
          .end('等图超时');
      }
    }, wantHq ? (options.hqTimeoutMs ?? options.photoTimeoutMs) : options.photoTimeoutMs);

    void shooter().then((outcome) => {
      clearTimeout(timer);
      photoInFlight = false;
      if (res.writableEnded) {
        return;
      }
      if (!outcome.ok) {
        handlers.log?.(`  拍照    ✗ 失败（${outcome.reason}）`);
        res.writeHead(504, { 'content-type': 'text/plain; charset=utf-8' })
          .end(`拍照失败：${outcome.reason}`);
        return;
      }
      const jpeg = outcome.jpeg;
      res.writeHead(200, {
        'content-type': 'image/jpeg',
        'content-length': String(jpeg.length),
      });
      res.end(jpeg);
    }).catch((error: Error) => {
      clearTimeout(timer);
      photoInFlight = false;
      if (!res.writableEnded) {
        res.writeHead(500).end(`拍照失败：${error.message}`);
      }
    });
  });

  server.on('error', (error: Error) => {
    handlers.log?.(`  ✗ 控制口 ${options.port}: ${error.message}`);
  });
  server.listen(options.port, '127.0.0.1');

  return {
    close: () => {
      server.close();
      // `close()` 只是停止**接受**新连接；已经建立的长连接（工具进程、keep-alive）会
      // 一直占着端口，于是下一条设备连接绑定同一个端口就 EADDRINUSE —— 那个旧 server
      // 还在活着，并且把 `/say`、`take_photo` 路由给**上一条已关闭的连接**。
      // 实测：手机重连一次之后，MCP 拍照就全都没反应了。
      server.closeAllConnections();
    },
  };
}
