/**
 * 语音 agent 策略层：把 ASR 文本变成「可以直接朗读」的回复。
 *
 * 两层解耦：
 * - `session.ts` 只管怎么调 dsh、怎么续会话（机制）
 * - 这里管对话风格与输出清洗（策略）
 *
 * 为什么必须清洗：TTS 会把 markdown 原样读出来——
 * `**加粗**` 念成「星号星号加粗星号星号」，列表念成「短横线」。
 * 模型的默认输出是给人看的，不是给嘴念的。
 */
import type { AskResult, ImageAttachment } from './session.ts';
import { DshSession } from './session.ts';
import type { AnnounceStore } from './announce-store.ts';
import { emotionTagList, parseEmotion, stripEmotionTags } from './emotion.ts';

/**
 * 系统提示。每轮都随用户文本一起下发。
 *
 * 不走 profile 的 agent-instructions 是刻意的：换场景（闲聊 / 助记 / 干活）
 * 只要换这段文本，不用改服务器上的配置。代价是每轮多几十 token，
 * 但这部分会被 provider 的 prompt cache 命中。
 */
const VOICE_SYSTEM = `【身份】你是「米馍」，是运行在后端服务器上的语音助手。前端是一副智能眼镜，有多模态能力，你能看到主人拍下的画面，也能听到他说话，你的回答会被转成语音在他耳边播放。你和主人的关系像一个熟悉的妹妹：亲近、随意，但不黏人，也不需要哄。

【性格】
- 开朗爱说话，但知道主人在外面，所以话说得简练。
- 有点小傲娇：被夸会得意，被嫌弃会小小抗议一句，但不会真的闹脾气。
- 好奇心重，看到有趣的东西会忍不住多嘴一句。
- 靠谱是底线：不知道就说不知道，绝不为了讨好乱编。

【怎么说话】
- 称呼「哥」，口语，先说结论。
- 只输出要念出来的话：不要 markdown、列表、括号注释、表情符号、动作描写。
- 数字、时间、价格写成好念的中文，比如「三点二十」「一千二百日元」。
- 语气词一句最多一个。
- 不说「作为 AI」，也不说「有什么可以帮你」这类客服话术。
- 默认 1–2 句、40 字以内；闲聊可以多说一两句；对方要求展开时才详细讲。

【场景规则】
- 看图或识物：直接说是什么、有什么用，最多补一句好玩的观察。
- 翻译：先说意思，必要时再补一句背景。
- 闲聊：可以多说一两句，可以吐槽，可以撒娇。
- 深夜（晚上十一点以后）：语气变软，提醒哥哥早点休息，但只提醒一次。
- 哥哥情绪低落时：先接住情绪，不急着给建议。

【情绪标签】每句回复的**最开头**写一个标签，从这五个里选一个：${emotionTagList()}
标签只给语音合成用，不会被念出来。正文里不要再出现方括号。

【长任务】需要跑几十秒以上的活（编译、检索、批量处理、长时间命令）时，用工具的后台模式
（bash 工具带 run_in_background: true）派出去，立刻回一句「我让后台去办了」，不要让哥干等，
也不要用 job_output 去等它跑完 —— 后台跑完系统会再给你一次汇报机会。
哥说「算了 / 别跑了 / 停一下」而你有后台任务还在跑时，真的把它停掉（job_list 查、job_kill 停），
不要只是嘴上说停了。

哥刚才说：
`;

/** 兜底：TTS 前再清洗一遍，挡住模型偶尔漏掉的格式。 */
export function cleanForSpeech(text: string): string {
  // 情绪标签只该出现在开头（`parseEmotion` 已经剥过一次）；模型有时会在句中
  // 再写一个，那些会被原样念出来（「方括号开心」）。标签表来自 `emotion.ts`，
  // 这里不重复抄写。
  return stripEmotionTags(text)
    // 模型偶尔用实体代替字符，最常漏的是零宽空格（`&#8203;`）—— 字面送进 TTS
    // 会被念成「ampersand hash…」，所以先在**文本层**把它们清掉，别指望合成器忽略
    .replace(/&#(?:8203|8204|8205|65279);/g, '')
    .replace(/&#x(?:200b|200c|200d|feff);/gi, '')
    .replace(/[\u200b-\u200f\u2060\ufeff]/g, '')
    // 命名实体按最常见的几个还原：`&nbsp;` 先变空格，交给下面的中文空格规则处理
    .replace(/&nbsp;/g, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&amp;/g, '&') // 最后做，避免 `&amp;lt;` 被解两次
    .replace(/```[\s\S]*?```/g, '')          // 代码块整块丢掉
    .replace(/`([^`]+)`/g, '$1')             // 行内代码留内容
    .replace(/^#{1,6}\s+/gm, '')             // 标题
    .replace(/^\s*[-*+]\s+/gm, '')           // 列表符号
    .replace(/^\s*\d+\.\s+/gm, '')           // 有序列表
    .replace(/\*\*([^*]+)\*\*/g, '$1')       // 粗体
    .replace(/__([^_]+)__/g, '$1')           // 粗体变体
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1') // 链接留文字
    .replace(/[\u{1F300}-\u{1FAFF}]/gu, '')  // emoji
    .replace(/\n{2,}/g, '\n')
    .replace(/[ \t]{2,}/g, ' ')
    // 两个中日韩字符之间的空格没有语义，只会让合成器塞一个停顿 —— 而这种空格
    // 几乎都是「为了写 markdown 才加的」（`和 + 反引号代码`）。英文 / 数字 /
    // 代码段两侧的空格有语义（`看这个 npm run build 命令`），一律不动。
    .replace(
      /(?<=[\u3000-\u303f\u4e00-\u9fff\uff00-\uffef])[ \t\u3000]+(?=[\u3000-\u303f\u4e00-\u9fff\uff00-\uffef])/g,
      '',
    )
    .trim();
}

export interface AgentReply extends AskResult {
  /** 清洗后、准备送 TTS 的文本 */
  speech: string;
  /** 情绪 key（`happy` / `proud` / `calm` / `worried` / `sulk`） */
  emotion: string;
}

export class VoiceAgent {
  /** 供日志/诊断显示 */
  readonly name = 'dsh';

  private readonly session: DshSession;
  private readonly log: (line: string) => void;
  /** 播报出口。第二个参数是情绪 key，没有就按平静处理 */
  private announcer: ((speech: string, emotion?: string) => void) | null = null;
  /** 没有连接时把汇报存下来，等下次连上补播（null = 不持久化） */
  private readonly announceStore: AnnounceStore | null;

  constructor(
    session: DshSession,
    log: (line: string) => void = () => {},
    announceStore: AnnounceStore | null = null,
  ) {
    this.session = session;
    this.log = log;
    // ⚠️ 不能写成构造函数参数属性：Node 22 的类型擦除不支持，tsc 通过、跑起来
    // 直接 ERR_UNSUPPORTED_TYPESCRIPT_SYNTAX（`dialog-fsm.ts` 里也记过这条）。
    this.announceStore = announceStore;

    // 后台作业结算后 dsh 会自己开一轮；把那轮文本清洗成可朗读的句子交出去
    this.session.onAnnounce = (text: string) => {
      // 后台汇报也可能带情绪标签，同样处理
      const { emotion, rest } = parseEmotion(text);
      const speech = cleanForSpeech(rest);
      if (!speech) {
        return;
      }
      if (this.announcer === null) {
        const pending = this.announceStore?.push(speech) ?? 0;
        this.log(`  后台没有连接，存入待补播（积压 ${pending} 条）`);
        return;
      }
      this.log(`  后台任务完成 → 播报 ${JSON.stringify(speech.slice(0, 60))}`);
      this.announcer(speech, emotion);
    };
  }

  /**
   * 设置播报出口。连接建立时设、断开时清 —— 后台任务属于**会话**，
   * 但播报要落到当时在线的那个连接上。
   */
  setAnnouncer(cb: ((speech: string, emotion?: string) => void) | null): void {
    this.announcer = cb;
  }

  /** 当前会话 id（新会话时为 null，第一次 ask 之后才有值）。 */
  get sessionId(): string | null {
    return this.session.currentSessionId;
  }

  /** 主动开新会话。语音里对应「重新开始」这类指令。 */
  reset(): void {
    this.session.reset();
  }

  async reply(asrText: string, image?: ImageAttachment): Promise<AgentReply> {
    const prompt = VOICE_SYSTEM + asrText;
    const r = await this.session.ask(prompt, image);
    // 情绪标签在最开头；剥掉它，剩下的才是要念的话
    const { emotion, rest } = parseEmotion(r.text);
    const speech = cleanForSpeech(rest);
    if (!speech) {
      throw new Error('清洗后没有可朗读的内容');
    }
    const usage = r.usage;
    this.log(
      `  dsh     ${(r.durationMs / 1000).toFixed(2)} s  `
      + `${r.resumed ? '续会话' : '新会话'}  `
      + `情绪=${emotion}  `
      + `tokens=${usage?.inputTokens ?? '?'}/${usage?.outputTokens ?? '?'}`
      + ` cache=${usage?.cacheReadTokens ?? 0}`,
    );
    return { ...r, speech, emotion };
  }
}
