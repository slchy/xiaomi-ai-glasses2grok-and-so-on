/**
 * PCM16 单声道小端的纯函数与定长缓冲。
 *
 * 本模块**不做任何 I/O**，不依赖其它业务模块，可单独测试。
 */

/** 16 kHz × 16 bit × 单声道 = 32 字节/毫秒 */
export const PCM16_BYTES_PER_MS_AT_16K = 32;

/** 全零 PCM 的峰值电平（dBFS）。用 -120 而不是 -Infinity，避免日志里出现 Infinity。 */
export const PCM16_SILENCE_DBFS = -120;

/**
 * 峰值电平（dBFS）。
 *
 * 用**峰值**而不是 RMS：判的是「这段到底有没有人声」，瞬态比能量更敏感，
 * 而回声/底噪在峰值上看得更清楚（实测播放期回波底噪 −80 dBFS）。
 */
export function pcm16PeakDbfs(pcm: Buffer): number {
  let peak = 0;
  const samples = pcm.length >> 1;
  for (let i = 0; i < samples; i += 1) {
    const value = pcm.readInt16LE(i << 1);
    const magnitude = value < 0 ? -value : value;
    if (magnitude > peak) {
      peak = magnitude;
    }
  }
  return peak === 0 ? PCM16_SILENCE_DBFS : 20 * Math.log10(peak / 32768);
}

/** 字节数 → 毫秒 */
export function pcm16DurationMs(bytes: number, sampleRate: number): number {
  return (bytes / (sampleRate * 2)) * 1000;
}

/**
 * 放大 PCM16（返回新 Buffer），带**软限幅**。
 *
 * 为什么需要：MiMo 出来的音量偏小，手机上要开到接近满格才听清。直接乘系数会在
 * 峰值处硬削顶（爆音），所以过了拐点后改用 tanh 平滑压向 ±1 —— 听感是"压住"
 * 而不是"削平"，且曲线在拐点处斜率连续（不会出现折角噪声）。
 *
 * @param gain 线性倍数，1.0 = 原样返回
 */
export function pcm16Gain(pcm: Buffer, gain: number): Buffer {
  if (gain === 1 || pcm.length === 0) {
    return pcm;
  }
  const knee = 0.85; // 低于此比例保持线性
  const out = Buffer.allocUnsafe(pcm.length);
  const samples = pcm.length >> 1;
  for (let i = 0; i < samples; i += 1) {
    const x = (pcm.readInt16LE(i << 1) / 32768) * gain;
    const magnitude = Math.abs(x);
    const shaped = magnitude <= knee
      ? x
      : Math.sign(x) * (knee + (1 - knee) * Math.tanh((magnitude - knee) / (1 - knee)));
    out.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(shaped * 32767))), i << 1);
  }
  return out;
}

/**
 * 按**字节**定长的滑动预卷缓冲。
 *
 * 为什么不能按块数：ffmpeg 每次 `data` 事件的块大小不固定 —— 实测同一次会话里
 * 既出现「8 块 = 105 ms」也出现「3 块 = 225 ms」。按块数留 8 块在最坏情况下
 * 只能盖 105 ms，连一个中文音节都不够，于是开头被削
 * （「你在运行什么」被识别成「李运行在什么」）。
 */
export class PcmPreroll {
  private readonly chunks: Buffer[] = [];
  private bufferedBytes = 0;
  private readonly maxBytes: number;

  constructor(maxBytes: number) {
    this.maxBytes = maxBytes;
  }

  push(chunk: Buffer): void {
    this.chunks.push(chunk);
    this.bufferedBytes += chunk.length;
    while (this.bufferedBytes > this.maxBytes && this.chunks.length > 1) {
      const dropped = this.chunks.shift();
      this.bufferedBytes -= dropped?.length ?? 0;
    }
  }

  /** 取出全部内容并清空。 */
  drain(): Buffer[] {
    this.bufferedBytes = 0;
    return this.chunks.splice(0, this.chunks.length);
  }

  get byteLength(): number {
    return this.bufferedBytes;
  }

  get chunkCount(): number {
    return this.chunks.length;
  }

  durationMs(sampleRate: number): number {
    return pcm16DurationMs(this.bufferedBytes, sampleRate);
  }
}
