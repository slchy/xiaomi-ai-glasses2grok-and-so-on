/**
 * 假手机：最小客户端，用来验证云端链路而不碰真机。
 *
 * 它同时是**协议的最小实现**（将来开源时给客户端作者当参照）：
 *   [type:u8][len:u32 LE][payload]
 *   Hello(6) -> Ping(4)/Pong(5) -> Audio(1) / Image(2) / Wake(3) / Status(5)
 *
 * 用法：
 *   node tools/fake-phone.ts --port 8766 --sbc ../../tmp/voice_1793904904745.sbc \
 *     [--token <hex>] [--host 127.0.0.1] [--frames 400] [--tls [--ca cert.pem]]
 *     [--hq --image <path>]
 *
 * `--tls` 不给 `--ca` 时跳过证书校验（本地自签图省事）；给了 `--ca` 就严格校验 ——
 * 这也是「自签 CA 打进客户端 = 天然 pinning」在测试里的样子。
 *
 * `--hq` 模拟**协作式高清照**：收到 `take_photo_hq` 先回 `hq_armed`，`--hq-after` 毫秒后
 * 再发 `hq_image` + 一张图（默认用 `--image` 指定的文件）。收到 `hq_cancel` 就回
 * `hq_failed{cancelled}`。用它能整条链路（含云端 `awaiting_shot`）不碰真机跑通。
 *
 * 退出码 0 表示：连上、发完、收到了下行 Audio（或至少 Pong/Control）。
 */
import { readFileSync } from 'node:fs';
import { connect, type Socket } from 'node:net';
import { connect as tlsConnect } from 'node:tls';

const FRAME_HEADER_BYTES = 5;
const UP = { Audio: 1, Image: 2, Wake: 3, Ping: 4, Status: 5, Hello: 6 } as const;
const DOWN = { Audio: 1, Control: 2, Pong: 5 } as const;

function opt(argv: string[], flag: string, def: string): string {
  const i = argv.indexOf(flag);
  return i >= 0 && i + 1 < argv.length ? (argv[i + 1] ?? def) : def;
}

function frame(type: number, payload: Buffer): Buffer {
  const header = Buffer.alloc(FRAME_HEADER_BYTES);
  header.writeUInt8(type, 0);
  header.writeUInt32LE(payload.length, 1);
  return Buffer.concat([header, payload]);
}

const argv = process.argv.slice(2);
const host = opt(argv, '--host', '127.0.0.1');
const port = Number(opt(argv, '--port', '8766'));
const sbcPath = opt(argv, '--sbc', '');
const token = opt(argv, '--token', '');
const frameLimit = Number(opt(argv, '--frames', '400'));
const useTls = argv.includes('--tls');
const caPath = opt(argv, '--ca', '');
const hqMode = argv.includes('--hq');
const hqImagePath = opt(argv, '--image', '');
const hqAfterMs = Number(opt(argv, '--hq-after', '3000'));

let gotAudio = 0;
const gotControl: string[] = [];
let gotPong = false;
let buffered = Buffer.alloc(0);
/** 只在 `--hq` 下用：避免重复响应同一条 `take_photo_hq` */
let hqHandled = false;

function sendStatus(ev: string, fields: Record<string, string> = {}): void {
  const payload = Buffer.from(JSON.stringify({ ev, ...fields }), 'utf8');
  socket.write(frame(UP.Status, payload));
  console.log(`→ Status ${JSON.stringify({ ev, ...fields })}`);
}

/** 模拟「用户按下快门 → 相册里多了一张」：先公告再发图 */
function sendHqPhoto(): void {
  if (hqImagePath === '') {
    console.error('✗ --hq 需要 --image <一张 JPEG>');
    process.exit(1);
  }
  const jpeg = readFileSync(hqImagePath);
  sendStatus('hq_image');
  socket.write(frame(UP.Image, jpeg));
  console.log(`→ Image ${jpeg.length} B（假装用户按了快门）`);
}

function onData(chunk: Buffer): void {
  buffered = Buffer.concat([buffered, chunk]);
  while (buffered.length >= FRAME_HEADER_BYTES) {
    const type = buffered.readUInt8(0);
    const len = buffered.readUInt32LE(1);
    if (buffered.length < FRAME_HEADER_BYTES + len) {
      return;
    }
    const payload = buffered.subarray(FRAME_HEADER_BYTES, FRAME_HEADER_BYTES + len);
    buffered = buffered.subarray(FRAME_HEADER_BYTES + len);
    if (type === DOWN.Audio) {
      gotAudio += 1;
    } else if (type === DOWN.Control) {
      const text = payload.toString('utf8');
      gotControl.push(text);
      if (hqMode && text === 'take_photo_hq' && !hqHandled) {
        hqHandled = true;
        // 真实的手机要先建 P2P（约 5 s）再回报就绪；这里只等一小会儿
        console.log('← take_photo_hq：假装建好 P2P，回报就绪');
        setTimeout(() => {
          sendStatus('hq_armed', { fresh: 'false' });
          setTimeout(sendHqPhoto, hqAfterMs);
        }, 300);
      } else if (hqMode && text === 'hq_cancel') {
        console.log('← hq_cancel：放弃这张高清图');
        sendStatus('hq_failed', { reason: 'cancelled' });
      }
    } else if (type === DOWN.Pong) {
      gotPong = true;
    }
  }
}

async function onReady(): Promise<void> {
  console.log(`✓ 已连接 ${useTls ? 'tls' : 'tcp'}://${host}:${port}`);

  if (token) {
    const hello = Buffer.from(JSON.stringify({ proto: 1, deviceId: 'fake-phone', token }), 'utf8');
    socket.write(frame(UP.Hello, hello));
    console.log('→ Hello');
  }

  socket.write(frame(UP.Ping, Buffer.alloc(0)));
  socket.write(frame(UP.Wake, Buffer.from('fake-transaction\nfake-request', 'utf8')));

  // 真手机每 10 s 发一次心跳；不发的话云端 45 s 空闲看门狗会把连接掐掉
  // （`--hq` 要等几十秒，测试时必须像真机一样保活）
  const pinger = setInterval(() => {
    socket.write(frame(UP.Ping, Buffer.alloc(0)));
  }, 10_000);
  socket.on('close', () => { clearInterval(pinger); });

  if (sbcPath !== '') {
    const sbc = readFileSync(sbcPath);
    const CHUNK = 570; // 10 × 57 B mSBC
    let sent = 0;
    for (let off = 0; off < sbc.length && sent < frameLimit; off += CHUNK) {
      socket.write(frame(UP.Audio, sbc.subarray(off, off + CHUNK)));
      sent += 1;
      await new Promise((r) => setTimeout(r, 20));
    }
    console.log(`→ Audio ×${sent}（${sbc.length} B mSBC）`);
  }

  // 再等一会儿，让一轮跑完（TTS 是流式下发的）。`--hq` 要等外部触发 + 等人按快门，留宽一点
  const holdMs = Number(opt(argv, '--hold', hqMode ? '30000' : '6000'));
  setTimeout(() => {
    console.log(`← Audio ×${gotAudio}  Control=[${gotControl.join(', ')}]  Pong=${gotPong}`);
    socket.end();
    const ok = gotAudio > 0 || gotPong || gotControl.length > 0;
    process.exit(ok ? 0 : 1);
  }, holdMs);
}

const socket: Socket = useTls
  ? tlsConnect(
    {
      host,
      port,
      rejectUnauthorized: caPath !== '',
      servername: undefined, // 连的是 IP：证书里写 SAN=IP，别拿 IP 当域名比对
      ...(caPath !== '' ? { ca: readFileSync(caPath) } : {}),
    },
    () => { void onReady(); },
  ) as unknown as Socket
  : connect(port, host, () => { void onReady(); });

socket.on('data', onData);
socket.on('error', (e: Error) => {
  console.error(`✗ ${e.message}`);
  process.exit(1);
});
