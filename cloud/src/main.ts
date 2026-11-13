/**
 * 云端 harness（TS 版）：mSBC 流 → ffmpeg 解码 → silero-vad 判停 → MiMo ASR。
 *
 *   node src/main.ts --replay ../../tmp/voice_1793905201973.sbc
 *   node src/main.ts --listen 8765
 *
 * 与 Python 版（scripts/cloud_harness.py + webrtcvad）的差异：
 * - VAD 从 webrtcvad 换成 silero-vad（ONNX），实测能正确忽略远场回声
 * - 其余链路不变，判停口径仍以"静音 4 秒"为准
 */
import { readFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

import { VoiceAgent } from './agent.ts';
import { AnnounceStore, DEFAULT_ANNOUNCE_MAX } from './announce-store.ts';
import { DEFAULT_HQ_ARM_TIMEOUT_MS, DEFAULT_HQ_SHUTTER_TIMEOUT_MS } from './photo-inbox.ts';
import { aliyunStt } from './aliyun-asr.ts';
import { mimoTts } from './mimo.ts';
import { BASE_STYLE, EMOTIONS } from './emotion.ts';
import { DEFAULT_TTS_VOICE } from './mimo.ts';
import {
  cannedStt,
  echoAgent,
  noopStt,
  silentTts,
  type AgentProvider,
  type SttProvider,
  type TtsProvider,
} from './providers.ts';
import { DshSession } from './session.ts';
import { runAliyunServer } from './aliyun-session.ts';
import { Pipeline, type ReplyMode } from './legacy-pipeline.ts';
import { sleep } from './util.ts';

/**
 * 插件加载：`<path>` 指向一个模块，导出 `stt()` / `tts()` 工厂（或 default）。
 *
 * 这是 `cloud/` 唯一的扩展点 —— 换参考实现不用改本文件，也不用 fork。将来开源时
 * 别人照这个签名写自己的模块即可（见 `docs/（内部待办））。
 */
async function loadProvider<T>(spec: string, exportName: string): Promise<T> {
  const isPath = /^[./]/.test(spec) || /^[A-Za-z]:[\\/]/.test(spec);
  const url = isPath ? pathToFileURL(resolve(spec)).href : spec;
  const mod = (await import(url)) as Record<string, unknown>;
  const factory = (mod.default ?? mod[exportName]) as (() => T) | undefined;
  if (typeof factory !== 'function') {
    throw new Error(`${spec} 必须导出 ${exportName}() 工厂（或 default 导出工厂）`);
  }
  return factory();
}

/** `--stt aliyun|noop|canned|<path>` */
async function buildStt(
  spec: string,
  creds: { akId: string; akSecret: string; appkey: string; region: string; maxSentenceSilenceMs: number },
): Promise<SttProvider> {
  if (spec === 'noop') return noopStt();
  if (spec === 'canned') return cannedStt();
  if (spec === 'aliyun') {
    return aliyunStt(creds, (line) => { console.log(`  [asr] ${line}`); });
  }
  return loadProvider<SttProvider>(spec, 'stt');
}

/** `--tts mimo|silent|<path>` */
async function buildTts(spec: string): Promise<TtsProvider> {
  if (spec === 'silent') return silentTts();
  if (spec === 'mimo') {
    if (!process.env['MIMO_API_KEY']) {
      throw new Error('MIMO_API_KEY 未设置（用 --tts silent 可跳过 TTS）');
    }
    // 风格提示词：MiMo 的 TTS 是「聊天式」的 —— 风格放进 user 消息，要念的文本放进
    // assistant 消息。基础音色 + 五种情绪的变体都由 `emotion.ts` 提供，可用环境变量覆盖。
    return mimoTts({
      voice: process.env['MIMO_TTS_VOICE'] ?? DEFAULT_TTS_VOICE,
      baseStyle: process.env['MIMO_TTS_STYLE'] ?? BASE_STYLE,
      // 每个情绪一个可选覆盖；key 从单一事实来源来，加情绪不用改这里
      overrides: Object.fromEntries(
        Object.keys(EMOTIONS).map((key) => [
          key,
          process.env[`MIMO_TTS_STYLE_${key.toUpperCase()}`],
        ]),
      ),
    });
  }
  return loadProvider<TtsProvider>(spec, 'tts');
}

interface AgentBundle {
  readonly agent: AgentProvider | null;
  /** dsh 专用：进程句柄要留着，退出时 stop() */
  readonly dsh: DshSession | null;
}

/** `--agent dsh|echo|none|<path>` */
async function buildAgent(
  spec: string,
  dsh: { workDir: string; useSdk: boolean; provider: string; model: string; log: (line: string) => void },
  announceStore: AnnounceStore | null,
): Promise<AgentBundle> {
  if (spec === 'none' || spec === '') {
    return { agent: null, dsh: null };
  }
  if (spec === 'echo') {
    return { agent: echoAgent(), dsh: null };
  }
  if (spec === 'dsh') {
    const session = new DshSession({
      workDir: dsh.workDir,
      program: process.env['DSH_BIN'],
      mode: dsh.useSdk ? 'sdk' : 'headless',
      patch: process.env['DSH_PATCH'],
      provider: dsh.provider,
      model: dsh.model,
      log: (l: string) => { console.log(`  [session] ${l}`); },
    });
    return { agent: new VoiceAgent(session, dsh.log, announceStore), dsh: session };
  }
  return { agent: await loadProvider<AgentProvider>(spec, 'agent'), dsh: null };
}

/** 证书从文件读，路径只走环境变量 —— 部署细节不进源码。 */
function loadTls(): { key: Buffer; cert: Buffer } | undefined {
  const keyPath = process.env['MIAI_TLS_KEY'] ?? '';
  const certPath = process.env['MIAI_TLS_CERT'] ?? '';
  if (!keyPath || !certPath) return undefined;
  return { key: readFileSync(keyPath), cert: readFileSync(certPath) };
}

/**
 * 设备鉴权：`MIAI_TOKENS` 指向一个文件，每行 `token` 或 `token:deviceId`。
 * 不配就**不鉴权**（本地开发）；配了就必须首帧 `Hello`。
 */
function loadAuthenticator(): ((hello: { deviceId?: string; token?: string }) => boolean) | undefined {
  const path = process.env['MIAI_TOKENS'] ?? '';
  if (!path) return undefined;
  const tokens = new Set(
    readFileSync(path, 'utf8')
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line !== '' && !line.startsWith('#'))
      .map((line) => line.split(':')[0]!.trim()),
  );
  if (tokens.size === 0) {
    console.error(`MIAI_TOKENS=${path} 里没有任何 token`);
    process.exit(2);
  }
  console.log(`鉴权      ${tokens.size} 个设备 token（来自 ${path}）`);
  return (hello) => typeof hello.token === 'string' && tokens.has(hello.token);
}

// ------------------------------------------------------------------ 入口
async function runReplay(path: string, pipe: Pipeline, gapMs: number): Promise<number> {
  const data = readFileSync(path);
  console.log(`回放 ${path}  ${data.length} 字节  ${((data.length / 57) * 7.5 / 1000).toFixed(2)} s`);
  console.log(`块 570 B，间隔 ${gapMs} ms\n`);

  const CHUNK = 570;
  for (let off = 0; off < data.length; off += CHUNK) {
    if (pipe.isFinished) {
      console.log(`（已判停，停止回放于 ${off} / ${data.length} 字节）`);
      break;
    }
    pipe.feed(data.subarray(off, off + CHUNK));
    if (gapMs > 0) await sleep(gapMs);
  }

  pipe.closeInput();
  await Promise.race([pipe.wait(), sleep(30_000)]);
  return 0;
}

async function runListen(port: number, pipe: Pipeline): Promise<number> {
  return await new Promise<number>((resolve) => {
    const server = createServer((sock) => {
      console.log(`已连接 ${sock.remoteAddress}:${sock.remotePort}\n`);
      let total = 0;
      let turns = 0;

      // 下行音频直接写回同一条连接：手机边收边播。
      pipe.setSink({
        write: (chunk: Buffer) => {
          if (!sock.destroyed) sock.write(chunk);
        },
        // 多轮：一轮结束**不发 EOF**，连接保持，手机端到 listenMs 自己断。
        end: () => {},
      });

      sock.on('data', (d: Buffer) => {
        total += d.length;
        pipe.feed(d);
      });

      // 轮次循环：一轮结束 → 等回复播完 → 重置 → 继续听
      const loop = async (): Promise<void> => {
        for (;;) {
          await pipe.wait();
          turns += 1;
          if (sock.destroyed) break;

          // 必须等回复播完再恢复收音：否则眼镜扬声器的声音会被自己的
          // 麦克风收进去，下一轮开头就变成「听到自己说话」。
          // 云端拿不到 AudioTrack 的真实播放进度，用音频时长 + 固定余量估算。
          const waitMs = pipe.replyDurationMs + 600;
          console.log(`\n--- 第 ${turns} 轮结束，${(waitMs / 1000).toFixed(1)} s 后继续听 ---`);
          await sleep(waitMs);
          if (sock.destroyed) break;

          pipe.reset();
          console.log('--- 继续听 ---\n');
        }
      };
      void loop();

      sock.on('close', () => {
        console.log(`\n连接结束，共收 ${total} 字节，${turns} 轮`);
        pipe.closeInput();
        server.close();
        resolve(0);
      });

      sock.on('error', (e: Error) => {
        console.error('socket 错误: ' + e.message);
      });
    });

    server.listen(port, '127.0.0.1', () => {
      console.log(`监听 127.0.0.1:${port}（多轮）`);
      console.log(`手机侧先执行： adb reverse tcp:${port} tcp:${port}\n`);
    });
  });
}

/**
 * 唤醒词。单独成句时**不是一轮对话**，直接丢掉。
 *
 * 实测：「小爱同学。」和「我眼前的是什么？」会被服务端拆成两个 SentenceEnd，
 * 第二个才是真正的命令。Smart Turn 对「小爱同学。」给 0.957（它确实是完整的一句话），
 * 所以模型治不了这个，只能靠这条领域规则。
 */
/** 服务端断句静音阈值。阿里云默认 800 ms；早先压到 500，比默认还激进。 */
const DEFAULT_SENTENCE_SILENCE_MS = 800;

/** Smart Turn 判为「没说完」时再多等多久 */
const SMART_TURN_EXTEND_MS = Number(process.env['SMART_TURN_EXTEND_MS'] ?? 700);

/** 读一个数字型环境变量，非法值退回默认 */
function envNumber(name: string, fallback: number): number {
  const raw = process.env[name];
  if (!raw) return fallback;
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : fallback;
}

/**
 * 读 `cloud/.env`（KEY=VALUE，忽略 # 注释与空行）。
 *
 * 不引依赖：格式就这么点。**已存在的环境变量优先**，所以命令行临时覆盖仍然有效。
 */
function loadDotEnv(): void {
  const path = join(process.cwd(), '.env');
  let text: string;
  try {
    text = readFileSync(path, 'utf8');
  } catch {
    return;
  }
  let count = 0;
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) {
      continue;
    }
    const eq = line.indexOf('=');
    if (eq <= 0) {
      continue;
    }
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"'))
      || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    if (process.env[key] === undefined) {
      process.env[key] = value;
      count += 1;
    }
  }
  if (count > 0) {
    console.log(`凭据    ${path}（${count} 项）`);
  }
}

async function main(): Promise<void> {
  loadDotEnv();
  const argv = process.argv.slice(2);

  const opt = (flag: string, def: string): string => {
    const i = argv.indexOf(flag);
    return i >= 0 && i + 1 < argv.length ? (argv[i + 1] ?? def) : def;
  };

  const replay = opt('--replay', '');
  const listen = Number(opt('--listen', '0'));
  const ffmpeg = opt('--ffmpeg', 'ffmpeg');
  const gapMs = Number(opt('--gap-ms', '20'));
  const workDir = opt('--work-dir', join(homedir(), 'voice-workspace'));
  /** 冒烟用：把一张本地图片交给 dsh，打印它的描述后退出 */
  const askImage = opt('--ask-image', '');

  // ---- 三个可替换件：都能按名字选内置实现，或给一个模块路径
  const sttSpec = opt('--stt', process.env['MIAI_STT'] ?? '');
  const ttsSpec = opt('--tts', process.env['MIAI_TTS'] ?? '');
  /**
   * Agent 选型：`--agent dsh|echo|none|<path>`。
   * 裸 `--agent`（后面不跟值）等价 `dsh`，`--echo` 是旧写法，继续支持。
   */
  const agentSpec = ((): string => {
    if (argv.includes('--echo')) return 'echo';
    const i = argv.indexOf('--agent');
    if (i < 0) return process.env['MIAI_AGENT'] ?? 'none';
    const next = argv[i + 1];
    return next !== undefined && !next.startsWith('--') ? next : 'dsh';
  })();

  const mode: ReplyMode = agentSpec === 'dsh'
    ? 'agent'
    : agentSpec === 'echo' ? 'echo' : 'none';

  if (!replay && !listen && !askImage) {
    console.error('用法: node src/main.ts --replay <file.sbc> | --listen <port> | --ask-image <file>');
    console.error('  可替换件: --stt aliyun|noop|canned|<path>  --tts mimo|silent|<path>');
    console.error('            --agent dsh|echo|none|<path>（裸 --agent 等价 dsh）');
    console.error('  其他:     [--work-dir <dir>] [--ffmpeg <path>] [--gap-ms 20]');
    process.exit(2);
  }

  let agent: AgentProvider | null = null;
  let dshSession: DshSession | null = null;
  /**
   * 离线时的后台汇报队列。放 `~/.voice-agent/` 旁边（和会话 id 一个目录），
   * 路径可用 `MIAI_ANNOUNCE_FILE` 覆盖；空串 = 不持久化。
   */
  const announceStore = AnnounceStore.open({
    file: process.env['MIAI_ANNOUNCE_FILE'] ?? join(homedir(), '.voice-agent', 'announce.jsonl'),
    maxItems: envNumber('MIAI_ANNOUNCE_MAX', DEFAULT_ANNOUNCE_MAX),
    log: (line) => { console.log(line); },
  });
  try {
    ({ agent, dsh: dshSession } = await buildAgent(agentSpec, {
      workDir,
      useSdk: argv.includes('--dsh-sdk'),
      provider: opt('--provider', 'deepseek-official'),
      model: opt('--model', 'deepseek-flash'),
      log: (line) => { console.log(line); },
    }, announceStore));
  } catch (error) {
    console.error(`agent 装配失败: ${(error as Error).message}`);
    process.exit(2);
  }
  if (dshSession) {
    console.log(`agent     dsh  workDir=${workDir}  dsh=${process.env['DSH_BIN'] ?? 'dsh'}  mode=${argv.includes('--dsh-sdk') ? 'sdk' : 'headless'}`);
    console.log(`会话      ${dshSession.currentSessionId ?? '（新会话）'}\n`);
  } else if (agent) {
    console.log(`agent     ${agent.name}\n`);
  }

  // ---------------------------------------------------------------- 图片 -> dsh 冒烟
  if (askImage) {
    if (!agent) {
      console.error('--ask-image 需要 --agent（图片只能交给 dsh 看）');
      process.exit(2);
    }
    const buf = readFileSync(askImage);
    const ext = askImage.toLowerCase().split('.').pop() ?? '';
    const mime = ext === 'png' ? 'image/png'
      : ext === 'webp' ? 'image/webp'
        : ext === 'gif' ? 'image/gif'
          : 'image/jpeg';
    console.log(`图片    ${askImage}  ${buf.length} B  ${mime}`);
    const t0 = Date.now();
    const r = await agent.reply('这张图里有什么？用一两句话口语化描述。', {
      mimeType: mime,
      data: buf.toString('base64'),
    });
    console.log(`dsh     ${((Date.now() - t0) / 1000).toFixed(2)} s`);
    console.log(`回答    ${JSON.stringify(r.text)}`);
    dshSession?.stop();
    process.exit(0);
  }

  // ---------------------------------------------------------------- 流式分支
  // `--stt` 显式给了就走流式路径；`--asr aliyun` 是旧写法，继续认。
  const asrBackend = opt('--asr', 'mimo');
  const resolvedStt = sttSpec === '' ? (asrBackend === 'aliyun' ? 'aliyun' : '') : sttSpec;
  if (resolvedStt !== '') {
    if (!listen) {
      console.error('流式识别只支持 --listen（没有 --replay 语义）');
      process.exit(2);
    }
    if (!agent) {
      console.error('流式识别需要一个 agent（收到 SentenceEnd 就要接一轮对话）');
      process.exit(2);
    }
    const akId = process.env['ALIYUN_AK_ID'] ?? '';
    const akSecret = process.env['ALIYUN_AK_SECRET'] ?? '';
    const appkey = process.env['ALIYUN_APPKEY'] ?? '';
    const region = process.env['ALIYUN_NLS_REGION'] ?? 'cn-shanghai';
    if (resolvedStt === 'aliyun' && (!akId || !akSecret || !appkey)) {
      console.error('STT=aliyun 需要 ALIYUN_AK_ID / ALIYUN_AK_SECRET / ALIYUN_APPKEY');
      process.exit(2);
    }
    const sentenceSilenceMs = Number(
      opt('--sentence-silence', String(DEFAULT_SENTENCE_SILENCE_MS)),
    );
    // 只有阿里云实现关心 region/appkey；换成别的实现时别把无关信息印出来
    const sttBanner = resolvedStt === 'aliyun'
      ? `region=${region}  appkey=${appkey.slice(0, 4)}****  silence=${sentenceSilenceMs} ms`
      : '(外部/内置实现，无需凭据)';
    console.log(`STT      ${resolvedStt}  ${sttBanner}\n`);

    const stt = await buildStt(resolvedStt, {
      akId,
      akSecret,
      appkey,
      region,
      maxSentenceSilenceMs: sentenceSilenceMs,
    });
    const tts = await buildTts(ttsSpec === '' ? 'mimo' : ttsSpec);
    console.log(`可替换件 STT=${stt.name}  TTS=${tts.name}（${tts.sampleRate} Hz）\n`);

    const smartTurnPython = process.env['SMART_TURN_PYTHON'] ?? '';
    const smartTurnScript = process.env['SMART_TURN_SCRIPT'] ?? '';
    const smartTurnEnabled = process.env['SMART_TURN'] !== '0'
      && smartTurnPython !== '' && smartTurnScript !== '';

    // 增益先算好并打出来：调音时最想知道「现在到底是几倍、门限在哪」
    const ttsGain = envNumber('MIAI_TTS_GAIN', envNumber('TTS_GAIN', 1));
    const micGain = envNumber('MIAI_MIC_GAIN', envNumber('MIC_GAIN', 1));
    const peakDbfs = envNumber('VOICE_PEAK_DBFS', -58);
    console.log(
      `增益上行 ${micGain}×（能量门 ${peakDbfs} dBFS，按放大后算）  下行 ${ttsGain}×\n`,
    );

    const code = await runAliyunServer({
      port: listen,
      host: opt('--host', '127.0.0.1'),
      ffmpegPath: ffmpeg,
      agent,
      stt,
      tts,
      smartTurn: smartTurnEnabled
        ? {
          pythonPath: smartTurnPython,
          scriptPath: smartTurnScript,
          quietMs: envNumber('SMART_TURN_QUIET_MS', 350),
          extendMs: SMART_TURN_EXTEND_MS,
          maxExtensions: envNumber('SMART_TURN_MAX_EXT', 3),
          threshold: envNumber('SMART_TURN_THRESHOLD', 0.5),
        }
        : undefined,
      gate: {
        thresholdDbfs: peakDbfs,
        holdMs: envNumber('VOICE_HOLD_MS', 2000),
        prerollMs: envNumber('VOICE_PREROLL_MS', 800),
      },
      playbackTailMs: 600,
      /**
       * 下行增益。**默认应当是 1.0**：MiMo 的输出已经是满刻度（实测峰值 −0.5 dBFS），
       * 任何 >1 的增益都会把元音峰值压进软限幅 —— ×3 时波峰因数从 14.7 dB 掉到
       * 7.3 dB，听感是"扁、闷、语调怪"。要更响请调手机媒体音量，不要加这里。
       */
      ttsGain,
      /**
       * 上行增益。眼镜 mic 电平低，4.0 时小声说话才能推开能量门
       * （门限 `VOICE_PEAK_DBFS` 按**放大后**的电平算，见 `uplink-pipeline.ts`）。
       */
      micGain,
      // 调音用：`MIAI_LEVEL_LOG=2000` 每 2 s 打一行上行峰值（0 = 关）
      levelLogMs: envNumber('MIAI_LEVEL_LOG', 0),
      /**
       * 原始档案目录（空 = 不存）。一条连接一个目录，存原始 mSBC、放大前的 PCM、
       * 下行 PCM 和事件时间线 —— 出问题后能完整重放，见 `archive.ts`。
       * 旧的 `DUMP_DIR`（按轮次存、只覆盖播放期）已被它取代。
       */
      archiveDir: process.env['MIAI_ARCHIVE_DIR'] ?? '',
      archiveMaxMb: envNumber('MIAI_ARCHIVE_MAX_MB', 80),
      archiveKeep: envNumber('MIAI_ARCHIVE_KEEP', 20),
      photoDir: process.env['PHOTO_DIR'] ?? '',
      // 默认避开 `--listen` 自己，否则两条监听撞端口（EADDRINUSE）
      controlPort: envNumber('PHOTO_CTL_PORT', listen + 2),
      // 高清链路要等手机建 P2P + 设备写相册，20 s 可能不够（`MIAI_PHOTO_TIMEOUT_MS` 可调）
      photoTimeoutMs: envNumber('MIAI_PHOTO_TIMEOUT_MS', 20_000),
      // 高清照要人按快门，两段分开计：等手机就绪 / 等用户按下去
      hqArmTimeoutMs: envNumber('MIAI_HQ_ARM_TIMEOUT_MS', DEFAULT_HQ_ARM_TIMEOUT_MS),
      hqShutterTimeoutMs: envNumber('MIAI_HQ_SHUTTER_TIMEOUT_MS', DEFAULT_HQ_SHUTTER_TIMEOUT_MS),
      imageQuestion: '这张图里有什么？用一两句话口语化描述。',
      announceStore,
      tls: loadTls(),
      authenticate: loadAuthenticator(),
      idleTimeoutMs: envNumber('MIAI_IDLE_TIMEOUT_MS', 45_000),
      serve: argv.includes('--serve') || process.env['MIAI_SERVE'] === '1',
    });
    dshSession?.stop();
    process.exit(code);
  }

  const pipe = new Pipeline(ffmpeg, mode, agent);
  await sleep(200);       // 等 VAD 初始化

  const code = replay
    ? await runReplay(replay, pipe, gapMs)
    : await runListen(listen, pipe);

  // 常驻进程要显式关，否则 node 会因为子进程 stdin 仍打开而不退出
  dshSession?.stop();
  process.exit(code);
}

void main();
