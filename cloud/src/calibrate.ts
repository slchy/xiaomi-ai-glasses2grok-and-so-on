/**
 * silero-vad 阈值标定。
 *
 *   node src/calibrate.ts ../tmp/voice_1793905201973.wav [more.wav ...]
 *
 * 目标：找出一个阈值，让 silero 的判停点与已验证的 Python webrtcvad 基准一致。
 * 基准（voice_1793905201973.wav）：最后语音 16.38 s，之后 20 秒全静音；
 * 4 秒静音阈值下应在 20.38 s 附近判停。
 *
 * 注意：silero 帧长 32 ms，webrtcvad 30 ms，两者不能逐帧对齐，
 * 所以比较的是**时间轴上的判停点**，不是帧号。
 */
import { readFileSync } from 'node:fs';

import { SileroVad, SAMPLE_RATE, WINDOW } from './vad.ts';

const SR = SAMPLE_RATE;
const WIN = WINDOW;       // 32 ms
const SILENCE_SEC = 4.0;  // 与 main.ts 的默认值保持一致

const THRESHOLDS = [0.9, 0.7, 0.5, 0.4, 0.3, 0.2, 0.1, 0.05, 0.02, 0.01];

function readWav(path: string): Float32Array {
  const buf = readFileSync(path);
  let off = 12;
  while (off + 8 <= buf.length) {
    const id = buf.toString('ascii', off, off + 4);
    const size = buf.readUInt32LE(off + 4);
    if (id === 'data') {
      const dataOff = off + 8;
      const n = Math.floor(Math.min(size, buf.length - dataOff) / 2);
      const out = new Float32Array(n);
      for (let i = 0; i < n; i += 1) out[i] = buf.readInt16LE(dataOff + i * 2) / 32768;
      return out;
    }
    off += 8 + size + (size % 2);
  }
  throw new Error('no data chunk: ' + path);
}

async function probabilities(vad: SileroVad, pcm: Float32Array): Promise<number[]> {
  vad.reset();
  const frames = Math.floor(pcm.length / WIN);
  const probs: number[] = [];

  for (let f = 0; f < frames; f += 1) {
    probs.push(await vad.process(pcm.slice(f * WIN, (f + 1) * WIN)));
  }
  return probs;
}

/** 给定阈值，返回「最后语音时刻」与「4 秒静音后的判停时刻」。 */
function cutPoint(probs: number[], threshold: number): { lastSpeech: number; cut: number; speechFrames: number } {
  let lastSpeech = -1;
  let speechFrames = 0;
  for (let i = 0; i < probs.length; i += 1) {
    if ((probs[i] ?? 0) > threshold) {
      lastSpeech = i;
      speechFrames += 1;
    }
  }
  const cut = lastSpeech < 0 ? 0 : ((lastSpeech + 1) * WIN) / SR + SILENCE_SEC;
  return { lastSpeech: lastSpeech < 0 ? -1 : (lastSpeech * WIN) / SR, cut, speechFrames };
}

async function main(): Promise<void> {
  const files = process.argv.slice(2);
  if (files.length === 0) {
    console.error('用法: node src/calibrate.ts <a.wav> [b.wav ...]');
    process.exit(2);
  }

  const vad = new SileroVad();
  await vad.init();

  for (const path of files) {
    const pcm = readWav(path);
    const probs = await probabilities(vad, pcm);
    const dur = pcm.length / SR;

    console.log(`\n===== ${path}  ${dur.toFixed(2)} s  ${probs.length} 帧 =====`);

    // 每秒概率统计：先看 14-16 秒那段到底是多少，再谈阈值
    const perSec = SR / WIN;
    console.log('秒   max     mean    中位   >0.5 帧');
    for (let s = 0; s * perSec < probs.length; s += 1) {
      const a = Math.round(s * perSec);
      const b = Math.min(probs.length, Math.round((s + 1) * perSec));
      if (b <= a) break;
      const seg = probs.slice(a, b).sort((x, y) => x - y);
      const max = seg[seg.length - 1] ?? 0;
      const mean = seg.reduce((x, y) => x + y, 0) / seg.length;
      const med = seg[Math.floor(seg.length / 2)] ?? 0;
      const over = seg.filter((p) => p > 0.5).length;
      const flag = max > 0.5 ? ' ' : max > 0.1 ? '·' : ' ';
      console.log(`${String(s).padStart(2)} ${max.toFixed(4)}  ${mean.toFixed(4)}  `
        + `${med.toFixed(4)}  ${String(over).padStart(2)} ${flag}`);
    }

    console.log('\n阈值最后语音判停时刻语音帧数');
    for (const t of THRESHOLDS) {
      const r = cutPoint(probs, t);
      const mark = r.lastSpeech > 0 ? '' : '  (无语音)';
      console.log(`${t.toFixed(2)}   ${r.lastSpeech.toFixed(2).padStart(7)} s  `
        + `${r.cut.toFixed(2).padStart(7)} s   ${String(r.speechFrames).padStart(4)}${mark}`);
    }
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
