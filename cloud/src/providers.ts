/**
 * 三个可替换件的契约：**STT / TTS / Agent**。
 *
 * 为什么单独一个文件：别人得能换掉其中任意一个而不动编排代码。这里只放**接口**，
 * 不 import 任何具体实现的 SDK —— 具体实现在 `aliyun-asr.ts` / `mimo.ts` / `agent.ts` 里。
 *
 * 三者的边界：
 * - STT：PCM16 **16 kHz 单声道** → 文本（流式，边喂边出中间结果）
 * - TTS：文本 → PCM16 **采样率由 provider 自己声明**（上行 16k / 下行 24k 不相等，
 *   这个坑踩过：混用会 1.5 倍速升调）
 * - Agent：文本（+ 可选图片）→ 一句话回复
 */

/** 给 Agent 的图片附件（与 `session.ts` 的 `ImageAttachment` 同构）。 */
export interface AgentImage {
  readonly mimeType: string;
  readonly data: string;
}

/** Agent 回复。只保留编排层真正需要的字段。 */
export interface AgentReplyView {
  /** 原始文本（日志/调试用） */
  readonly text: string;
  /** 清洗后、准备送 TTS 的文本 */
  readonly speech: string;
  /**
   * 情绪（`happy` / `proud` / `calm` / `worried` / `sulk`）。
   *
   * agent 在回复开头写 `[开心]` 这类标签，这里给出规范化后的 key；TTS provider
   * 据此换语调。缺省按 `calm` 处理。
   */
  readonly emotion?: string;
}

/**
 * Agent（LLM）。**给定一句用户话术，返回一句话回复。**
 *
 * 内置实现：`VoiceAgent`（dsh 子进程）。替换示例：任意 HTTP LLM 客户端。
 * 注意 `speech` 要和 `text` 分开 —— 前者要能被 TTS 正确读出来（去 markdown、
 * 去表情），后者保留原样用于展示和日志。
 */
export interface AgentProvider {
  readonly name: string;
  reply(userText: string, image?: AgentImage): Promise<AgentReplyView>;
  /**
   * 可选：agent 在**没有任何一轮**的情况下主动要说的内容（后台任务完成汇报）。
   *
   * 传 `null` 表示当前没有连接可播报。实现方（`VoiceAgent`）负责把原始文本
   * 清洗成可朗读的句子；编排层只管什么时候播、能不能播。
   */
  setAnnouncer?(cb: ((speech: string) => void) | null): void;
}

/** STT 会话：一次连接一个，流式喂 PCM。 */
export interface SttSession {
  /** 喂一块 PCM16 16 kHz mono。 */
  feed(pcm: Buffer): void;
  /** 「这段说完了」：请求参考实现出最终结果（不必关闭连接）。 */
  hangup(): void;
  close(): void;
}

export interface SttHandlers {
  /** 中间结果（边说边出字），可选。 */
  onPartial?: (text: string) => void;
  /** 一句说完。`index` 是本次连接内的句序。 */
  onSentence: (text: string, index: number) => void;
  onError?: (error: Error) => void;
}

/** STT provider：每次连接 `open()` 一个新会话。 */
export interface SttProvider {
  readonly name: string;
  /** 期望的输入采样率，固定 16 kHz（mSBC 解码后的速率）。 */
  open(handlers: SttHandlers): SttSession;
}

/** TTS provider：文本 → PCM16 mono 流。 */
export interface TtsProvider {
  readonly name: string;
  /** 输出采样率。**必须由 provider 声明**，不要在宿主里另配一份。 */
  readonly sampleRate: number;
  /**
   * @param emotion 情绪 key（见 `emotion.ts`）。provider 自己决定怎么用：MiMo 拿它
   *   拼风格提示词。不认识/不传就按默认语气合成。
   */
  synthesize(text: string, emotion?: string): AsyncIterable<Buffer>;
}

// ------------------------------------------------------------------ 调试用实现

/** 不识别任何语音：喂进来的东西直接丢掉。用于只测 TTS/播放的链路。 */
export function noopStt(): SttProvider {
  return {
    name: 'noop',
    open: () => ({ feed: () => {}, hangup: () => {}, close: () => {} }),
  };
}

/** 不出声：synthesize 产出一段静音。用于只测 STT/Agent 的链路。 */
export function silentTts(sampleRate = 24_000, seconds = 0.5): TtsProvider {
  return {
    name: 'silent',
    sampleRate,
    async* synthesize() {      const bytes = Math.round(sampleRate * 2 * seconds);
      yield Buffer.alloc(bytes);
    },
  };
}

/**
 * 回显 Agent：把用户说的话原样念回去。
 *
 * 用途：不接任何 LLM 就能验「STT 出文本 → 一轮 → TTS → 下发」整条链路。
 */
export function echoAgent(): AgentProvider {
  return {
    name: 'echo',
    reply: async (userText) => ({ text: userText, speech: userText }),
  };
}

/**
 * 罐头 STT：不收真实音频，累计到 `afterBytes` 就吐一句固定话。
 *
 * 用途：不动嘴、不接参考实现，也能把一轮对话跑完（配合 `silentTts` + `echoAgent`）。
 * **只用于开发/冒烟**，别拿它当默认值。
 */
export function cannedStt(
  sentence = '你好，这是一句测试。',
  afterBytes = 32 * 1024,
): SttProvider {
  return {
    name: 'canned',
    open: (handlers) => {
      let seen = 0;
      let fired = false;
      return {
        feed: (pcm) => {
          if (fired) {
            return;
          }
          seen += pcm.length;
          if (seen >= afterBytes) {
            fired = true;
            handlers.onSentence(sentence, 1);
          }
        },
        hangup: () => {},
        close: () => {},
      };
    },
  };
}
