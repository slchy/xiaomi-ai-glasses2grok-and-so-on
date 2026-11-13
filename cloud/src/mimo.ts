/**
 * MiMo ASR / TTS 客户端（TS 版，与 scripts/mimo_client.py 等价）。
 *
 * 实测事实见 docs/MIMO-API-PROBED.md：
 * - 端点 POST /v1/chat/completions，header `api-key`
 * - ASR：音频整段 base64 内联，输出 SSE 文本 delta
 * - TTS：输出 SSE base64 PCM16，**采样率 24 kHz**
 */

import type { TtsProvider } from './providers.ts';
import { BASE_STYLE, asEmotion, styleFor, type Emotion } from './emotion.ts';

const API_URL = 'https://api.xiaomimimo.com/v1/chat/completions';
const ASR_MODEL = 'mimo-v2.5-asr';
const TTS_MODEL = 'mimo-v2.5-tts';

/** 上行 mSBC 解码后的采样率 */
export const ASR_SAMPLE_RATE = 16_000;
/** MiMo TTS 输出采样率（实测确认） */
export const TTS_SAMPLE_RATE = 24_000;

export class MimoError extends Error {}

function apiKey(): string {
  const k = process.env.MIMO_API_KEY?.trim();
  if (!k) {
    throw new MimoError('未设置 MIMO_API_KEY');
  }
  return k;
}

/** 给裸 PCM16 加 44 字节 WAV 头。 */
export function pcmToWav(pcm: Buffer, rate = ASR_SAMPLE_RATE, channels = 1): Buffer {
  const blockAlign = channels * 2;
  const header = Buffer.alloc(44);
  header.write('RIFF', 0, 'ascii');
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write('WAVE', 8, 'ascii');
  header.write('fmt ', 12, 'ascii');
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20);                 // PCM
  header.writeUInt16LE(channels, 22);
  header.writeUInt32LE(rate, 24);
  header.writeUInt32LE(rate * blockAlign, 28);
  header.writeUInt16LE(blockAlign, 32);
  header.writeUInt16LE(16, 34);
  header.write('data', 36, 'ascii');
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, pcm]);
}

type SseObject = Record<string, unknown>;

async function* sse(body: unknown): AsyncGenerator<SseObject> {
  const res = await fetch(API_URL, {
    method: 'POST',
    headers: {
      'api-key': apiKey(),
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    },
    body: JSON.stringify(body),
  });

  if (!res.ok) {
    const detail = await res.text().catch(() => '');
    throw new MimoError(`HTTP ${res.status}: ${detail.slice(0, 500)}`);
  }
  if (!res.body) {
    throw new MimoError('响应没有 body');
  }

  const decoder = new TextDecoder();
  let buf = '';
  for await (const chunk of res.body as unknown as AsyncIterable<Uint8Array>) {
    buf += decoder.decode(chunk, { stream: true });
    let nl = buf.indexOf('\n');
    while (nl >= 0) {
      const line = buf.slice(0, nl).trim();
      buf = buf.slice(nl + 1);
      nl = buf.indexOf('\n');
      if (!line.startsWith('data:')) continue;
      const data = line.slice(5).trim();
      if (data === '[DONE]') return;
      if (!data) continue;
      try {
        yield JSON.parse(data) as SseObject;
      } catch {
        // 跳过畸形的 SSE 行，不中断整条流
      }
    }
  }
}

function deltaText(obj: SseObject): string {
  const choices = obj.choices as Array<{ delta?: { content?: unknown } }> | undefined;
  const c = choices?.[0]?.delta?.content;
  return typeof c === 'string' ? c : '';
}

function deltaAudio(obj: SseObject): string {
  const choices = obj.choices as
    | Array<{ delta?: { audio?: { data?: unknown } } }>
    | undefined;
  const d = choices?.[0]?.delta?.audio?.data;
  return typeof d === 'string' ? d : '';
}

/** 整段 WAV → 文本。 */
export async function asr(wav: Buffer, language = 'auto'): Promise<{ text: string; chunks: number }> {
  const b64 = wav.toString('base64');
  const pieces: string[] = [];
  let chunks = 0;

  for await (const obj of sse({
    model: ASR_MODEL,
    messages: [{
      role: 'user',
      content: [{
        type: 'input_audio',
        input_audio: { data: `data:audio/wav;base64,${b64}` },
      }],
    }],
    asr_options: { language },
    stream: true,
  })) {
    const t = deltaText(obj);
    if (t) {
      pieces.push(t);
      chunks += 1;
    }
  }

  return { text: pieces.join(''), chunks };
}

/**
 * 文本 → PCM16 单声道 24 kHz，**流式**逐块吐出。
 *
 * 实测：首块 7680 字节（0.16 s 音频），首块延迟 0.77–0.88 s。
 * 等整段收完再播，等于把"音频总时长"整个加到端到端延迟上
 * （12 s 的回复就要多等 3.8 s）；边收边播把这一项降到首块延迟。
 */
export const DEFAULT_TTS_VOICE = 'Chloe';

export async function* ttsStream(
  text: string,
  voice = 'Chloe',
  style = 'Calm, natural, conversational.',
): AsyncGenerator<Buffer> {
  for await (const obj of sse({
    model: TTS_MODEL,
    messages: [
      { role: 'user', content: style },
      { role: 'assistant', content: text },
    ],
    audio: { format: 'pcm16', voice },
    stream: true,
  })) {
    const d = deltaAudio(obj);
    if (!d) continue;
    yield Buffer.from(d, 'base64');
  }
}

/** 文本 → PCM16 单声道 24 kHz。整段版本，内部就是收完 ttsStream。 */
export async function tts(
  text: string,
  voice = 'Chloe',
  style = 'Calm, natural, conversational.',
): Promise<{ pcm: Buffer; chunks: number }> {
  const parts: Buffer[] = [];
  for await (const pcm of ttsStream(text, voice, style)) {
    parts.push(pcm);
  }
  return { pcm: Buffer.concat(parts), chunks: parts.length };
}

/**
 * 把 MiMo 包成 {@link TtsProvider}，供编排层注入。
 *
 * 采样率由这里声明（24 kHz），宿主不再另配一份 —— 上行 16 kHz / 下行 24 kHz
 * 是两个不同的值，配错会 1.5 倍速升调。
 *
 * 语调由**情绪**决定：agent 每句开头给一个 `[开心]` 之类的标签，这里翻成风格提示词
 * （见 `emotion.ts`）。不传情绪就按 `calm`。
 */
export function mimoTts(options: {
  readonly voice?: string;
  readonly baseStyle?: string;
  readonly overrides?: Partial<Record<Emotion, string>>;
} = {}): TtsProvider {
  const voice = options.voice ?? DEFAULT_TTS_VOICE;
  const base = options.baseStyle ?? BASE_STYLE;
  const overrides = options.overrides ?? {};
  return {
    name: `mimo:${voice}`,
    sampleRate: TTS_SAMPLE_RATE,
    synthesize: (text: string, emotion?: string) =>
      ttsStream(text, voice, styleFor(asEmotion(emotion), base, overrides)),
  };
}
