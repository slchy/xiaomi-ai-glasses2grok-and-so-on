/**
 * 把一段 mSBC 按实时速率推给本地 harness，并收集下行音频。
 *
 *   node scripts/push-sbc.mjs ../tmp/voice.sbc [--port 8765] [--wait 25]
 *
 * 用于在没有手机的机器上验证「解码 → 识别 → agent → TTS → 回传」整条链路。
 * 音频按真实节奏推（570 B / 20 ms ≈ 7.5 帧），推快了服务端 VAD 判停会失真。
 */
import { connect } from 'node:net';
import { readFileSync } from 'node:fs';

const file = process.argv[2];
if (!file) {
  console.error('用法: node scripts/push-sbc.mjs <file.sbc> [--port N] [--wait S]');
  process.exit(2);
}
const arg = (flag, def) => {
  const i = process.argv.indexOf(flag);
  return i > 0 && i + 1 < process.argv.length ? process.argv[i + 1] : def;
};
const PORT = Number(arg('--port', '8765'));
const WAIT_S = Number(arg('--wait', '25'));
/**
 * 570 字节 = 10 个 mSBC 帧 × 7.5 ms = **75 ms 音频**。
 * 早期写成 20 ms 相当于 3.75 倍速推送，服务端 VAD 会被冲乱（句子切错/丢失）。
 */
const CHUNK = 570;
const GAP_MS = 75;

const data = readFileSync(file);
console.log(`推送 ${file}  ${data.length} 字节 → 127.0.0.1:${PORT}`);
console.log(`块 ${CHUNK} B / ${GAP_MS} ms，约 ${((data.length / CHUNK) * GAP_MS / 1000).toFixed(1)} s 推完\n`);

let down = 0;
let t0 = Date.now();
const sock = connect(PORT, '127.0.0.1', async () => {
  t0 = Date.now();
  for (let off = 0; off < data.length; off += CHUNK) {
    if (sock.destroyed) break;
    sock.write(data.subarray(off, Math.min(off + CHUNK, data.length)));
    await new Promise((r) => setTimeout(r, GAP_MS));
  }
  console.log(`\n[${((Date.now() - t0) / 1000).toFixed(1)}s] 上行推送完毕，等下行…`);
});

sock.on('data', (d) => {
  if (down === 0) console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s] ★ 下行首包 ${d.length} 字节`);
  down += d.length;
});

sock.on('error', (e) => {
  console.error(`socket 错误: ${e.message}`);
  process.exit(1);
});

setTimeout(() => {
  const secs = down / (24_000 * 2);
  console.log(`\n[${((Date.now() - t0) / 1000).toFixed(1)}s] 结束：下行累计 ${down} 字节（${secs.toFixed(2)} s 音频）`);
  sock.end();
  process.exit(0);
}, (WAIT_S + (data.length / CHUNK) * GAP_MS / 1000) * 1000);
