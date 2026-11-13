/**
 * silero-vad v5 封装。
 *
 * **v5 的接口有两个坑，都是实测踩出来的，别按 v4 的文档写：**
 *
 * 1. 输入不是 `[1, 512]` 而是 `[1, 576]` —— 前面要拼 64 个采样点的上一帧 context。
 *    直接喂 512 时模型能跑、不报错，但输出恒定在 0.0005 左右，看起来像"模型坏了"。
 * 2. 输入值域是 `[-1, 1]`（int16 / 32768），不是 int16 原始值。
 *
 * 阈值 0.4 是标定出来的：同一段 37 秒录音在 0.9~0.02 之间判停点完全一致（10.08~10.18 s），
 * 说明判决稳定；0.01 会开始把噪声当语音。
 */
import type * as ort from 'onnxruntime-node';

/**
 * onnxruntime-node 的**惰性加载**。
 *
 * 只有 MiMo（本地 silero VAD）那条老路径才用得上它，而它装完是 300 MB 量级。
 * 阿里云路径把判停完全交给了服务端，根本用不到 —— 所以改成用到时才 import。
 */
let ortPromise: Promise<typeof ort> | null = null;
const ortModule = (): Promise<typeof ort> => {
  ortPromise ??= import('onnxruntime-node');
  return ortPromise;
};

export const SAMPLE_RATE = 16_000;
/** 每帧采样点数（32 ms） */
export const WINDOW = 512;
/** v5 需要拼接的 context 长度 */
const CONTEXT = 64;

export class SileroVad {
  private readonly modelPath: string;
  private session: ort.InferenceSession | null = null;
  private state: ort.Tensor | null = null;
  private context = new Float32Array(CONTEXT);

  constructor(modelPath = 'models/silero_vad.onnx') {
    this.modelPath = modelPath;
  }

  async init(): Promise<void> {
    const o = await ortModule();
    this.session = await o.InferenceSession.create(this.modelPath);
    this.reset(await ortModule());
  }

  reset(o?: typeof ort): void {
    // LSTM 的 h/c 两个状态，[2, batch, 128]
    const ortRef = o;
    if (ortRef) {
      this.state = new ortRef.Tensor('float32', new Float32Array(2 * 1 * 128), [2, 1, 128]);
    } else {
      // 没传模块时只清 context，state 等 init 时再建
      this.state = null;
    }
    this.context = new Float32Array(CONTEXT);
  }

  /** @param frame 恰好 WINDOW 个采样点，值域 [-1, 1] */
  async process(frame: Float32Array): Promise<number> {
    if (!this.session || !this.state) {
      throw new Error('SileroVad 尚未 init()');
    }
    if (frame.length !== WINDOW) {
      throw new Error(`帧长必须是 ${WINDOW}，收到 ${frame.length}`);
    }

    const x = new Float32Array(CONTEXT + WINDOW);
    x.set(this.context, 0);
    x.set(frame, CONTEXT);

    const o = await ortModule();
    const out = await this.session.run({
      input: new o.Tensor('float32', x, [1, CONTEXT + WINDOW]),
      state: this.state,
      sr: new o.Tensor('int64', BigInt64Array.from([BigInt(SAMPLE_RATE)]), []),
    });

    this.state = out.stateN as ort.Tensor;
    this.context = frame.slice(WINDOW - CONTEXT);

    const data = (out.output as ort.Tensor).data as Float32Array;
    return data[0] ?? 0;
  }
}

/**
 * 把 int16 PCM 缓冲转成 [-1, 1] 的 float 帧。
 *
 * @returns 完整的帧数组，剩余不足一帧的字节由调用方保留
 */
export function sliceFrames(pcm: Buffer): { frames: Float32Array[]; rest: Buffer } {
  const FRAME_BYTES = WINDOW * 2;
  const count = Math.floor(pcm.length / FRAME_BYTES);
  const frames: Float32Array[] = [];

  for (let i = 0; i < count; i += 1) {
    const f = new Float32Array(WINDOW);
    const base = i * FRAME_BYTES;
    for (let j = 0; j < WINDOW; j += 1) {
      f[j] = pcm.readInt16LE(base + j * 2) / 32768;
    }
    frames.push(f);
  }

  return { frames, rest: pcm.subarray(count * FRAME_BYTES) };
}
