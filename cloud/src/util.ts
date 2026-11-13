/**
 * 通用小工具。没有依赖，谁都可以引。
 */

export const sleep = (ms: number): Promise<void> => new Promise((r) => { setTimeout(r, ms); });

/** PCM16 小端单声道峰值电平（dBFS）。用来判「这段到底有没有人声」。 */
export function peakDbfs(pcm: Buffer): number {
  let peak = 0;
  const n = pcm.length >> 1;
  for (let i = 0; i < n; i += 1) {
    const v = pcm.readInt16LE(i << 1);
    const a = v < 0 ? -v : v;
    if (a > peak) peak = a;
  }
  return peak === 0 ? -120 : 20 * Math.log10(peak / 32768);
}
