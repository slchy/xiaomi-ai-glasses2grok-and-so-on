/**
 * 阿里云 NLS 实时语音识别连通性探测。
 *
 *   node scripts/aliyun-probe.mjs <pcm-file|sbc-file> [--rate 1]
 *
 * 只验证三件事，不改动 cloud/src 里的任何代码：
 *   1. CreateToken 能不能拿到 token
 *   2. WebSocket 能不能连上、StartTranscription 能不能被接受
 *   3. 中间结果 / 句末事件长什么样、多久到
 *
 * 音频按「实时速率」推送（默认 1 倍速），因为服务端 VAD 的行为依赖真实时序；
 * 一次性灌完会让它判停得很奇怪。
 */
import { spawnSync } from 'node:child_process';
import { createHmac, randomUUID } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';

const AK_ID = process.env['ALIYUN_AK_ID'];
const AK_SECRET = process.env['ALIYUN_AK_SECRET'];
const APPKEY = process.env['ALIYUN_APPKEY'];
const REGION = process.env['ALIYUN_NLS_REGION'] ?? 'cn-shanghai';
const FFMPEG = process.env['FFMPEG'] ?? 'ffmpeg';

const SAMPLE_RATE = 16_000;
/** 每块 100 ms，对应 NLS 推荐的推送节奏 */
const CHUNK_BYTES = (SAMPLE_RATE * 2 * 100) / 1000;

const t0 = Date.now();
const ts = () => `${((Date.now() - t0) / 1000).toFixed(2)}s`;

/**
 * NLS 要求 message_id 是 **32 位纯十六进制** —— 带连字符的 UUID 会被拒：
 * `Gateway:MESSAGE_INVALID:Invalid message id 'afd1a494-4cb2-...'`
 */
const msgId = () => randomUUID().replace(/-/g, '');

function die(msg) {
  console.error(`✗ ${msg}`);
  process.exit(1);
}

// ---------------------------------------------------------------- 签名
/** RFC 3986 编码：encodeURIComponent 不转义 !'()*，阿里云要求转义 */
const pct = (s) => encodeURIComponent(s)
  .replace(/\+/g, '%20')
  .replace(/\*/g, '%2A')
  .replace(/%7E/g, '~');

async function createToken() {
  const params = {
    AccessKeyId: AK_ID,
    Action: 'CreateToken',
    Format: 'JSON',
    RegionId: REGION,
    SignatureMethod: 'HMAC-SHA1',
    SignatureNonce: randomUUID(),
    SignatureVersion: '1.0',
    Timestamp: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
    Version: '2019-02-28',
  };
  const canonical = Object.keys(params)
    .sort()
    .map((k) => `${pct(k)}=${pct(params[k])}`)
    .join('&');
  const stringToSign = `POST&${pct('/')}&${pct(canonical)}`;
  const signature = createHmac('sha1', `${AK_SECRET}&`).update(stringToSign).digest('base64');
  const body = `${canonical}&Signature=${pct(signature)}`;

  const endpoint = `https://nls-meta.${REGION}.aliyuncs.com/`;
  const started = Date.now();
  const res = await fetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });
  const text = await res.text();
  console.log(`  CreateToken  ${Date.now() - started} ms  HTTP ${res.status}`);
  if (!res.ok) die(`CreateToken 失败：${text.slice(0, 400)}`);
  const json = JSON.parse(text);
  if (!json.Token?.Id) die(`响应里没有 Token.Id：${text.slice(0, 300)}`);
  return json.Token;
}

// ---------------------------------------------------------------- 音频
function loadPcm(path) {
  const raw = readFileSync(path);
  // mSBC 直接把字节喂给 ffmpeg 解码；已经是 PCM 就原样用
  if (path.endsWith('.sbc')) {
    const r = spawnSync(FFMPEG, [
      '-hide_banner', '-loglevel', 'error',
      '-f', 'sbc', '-i', 'pipe:0',
      '-f', 's16le', '-ar', String(SAMPLE_RATE), '-ac', '1', 'pipe:1',
    ], { input: raw, maxBuffer: 256 * 1024 * 1024 });
    if (r.status !== 0) die(`ffmpeg 解码失败：${r.stderr?.toString().slice(0, 300)}`);
    return r.stdout;
  }
  return raw;
}

// ---------------------------------------------------------------- 主流程
async function main() {
  const path = process.argv[2];
  if (!path || !existsSync(path)) die('用法: node scripts/aliyun-probe.mjs <pcm|sbc>');
  if (!AK_ID || !AK_SECRET || !APPKEY) die('缺少 ALIYUN_AK_ID / ALIYUN_AK_SECRET / ALIYUN_APPKEY');
  const speed = Number(process.argv.includes('--rate')
    ? process.argv[process.argv.indexOf('--rate') + 1] : '1') || 1;

  console.log(`region=${REGION}  appkey=${APPKEY.slice(0, 4)}****`);
  const pcm = loadPcm(path);
  const secs = pcm.length / (SAMPLE_RATE * 2);
  console.log(`音频 ${pcm.length} 字节  ${secs.toFixed(2)} s  推送速率 ${speed}x\n`);

  const token = await createToken();
  console.log(`  Token 拿到，过期时间 ${new Date(token.ExpireTime * 1000).toISOString()}\n`);

  const url = `wss://nls-gateway-${REGION}.aliyuncs.com/ws/v1?token=${encodeURIComponent(token.Id)}`;
  const ws = new WebSocket(url);

  let started = false;
  let firstResultAt = 0;
  /** 服务端在 TranscriptionStarted 里分配，StopTranscription 必须带回去 */
  let taskId = null;
  const events = [];

  const minimal = process.argv.includes('--minimal');

  const sendStart = () => {
    const payload = minimal
      ? { format: 'pcm', sample_rate: SAMPLE_RATE }
      : {
        format: 'pcm',
        sample_rate: SAMPLE_RATE,
        enable_intermediate_result: true,
        enable_punctuation_prediction: true,
        enable_inverse_text_normalization: true,
      };
    const header = {
      appkey: APPKEY,
      message_id: msgId(),
      // 与参考实现 SDK 一致：task_id 由客户端生成，且 name 必须有
      task_id: (taskId = msgId()),
      namespace: 'SpeechTranscriber',
      name: 'StartTranscription',
    };
    // context 是参考实现 SDK 必带字段（defaultContext），缺了服务端会回
    // 误导性的 `Missing message name!`
    const context = { sdk: { name: 'nls-nodejs-sdk', version: '0.0.1', language: 'nodejs' } };
    console.log(`  发送 StartTranscription（${minimal ? '最小参数' : '完整参数'}）`);
    console.log(`  ${JSON.stringify({ header, payload, context })}`);
    ws.send(JSON.stringify({ header, payload, context }));
  };

  ws.addEventListener('open', () => {
    console.log(`  ${ts()} WebSocket 已连接`);
    sendStart();
  });

  ws.addEventListener('error', (e) => {
    console.error(`  ${ts()} WebSocket 错误: ${e.message ?? e}`);
  });

  ws.addEventListener('message', (ev) => {
    const text = typeof ev.data === 'string' ? ev.data : ev.data.toString();
    let msg;
    try {
      msg = JSON.parse(text);
    } catch {
      console.log(`  ${ts()} 非 JSON: ${text.slice(0, 200)}`);
      return;
    }
    const name = msg.header?.name ?? '(无 name)';
    const status = msg.header?.status;
    events.push(name);

    if (name === 'TranscriptionStarted') {
      started = true;
      taskId = msg.header?.task_id ?? null;
      console.log(`  ${ts()} ✓ TranscriptionStarted  task_id=${taskId}`);
      void pump();
      return;
    }

    const result = msg.payload?.result;
    if (name === 'TranscriptionResultChanged') {
      if (!firstResultAt) {
        firstResultAt = Date.now();
        console.log(`  ${ts()} ★ 首个中间结果（距开始推送 ${((firstResultAt - t0) / 1000).toFixed(2)} s）`);
      }
      console.log(`  ${ts()} 中间  ${JSON.stringify(result)}`);
      return;
    }
    if (name === 'SentenceEnd') {
      console.log(`  ${ts()} ◆ 句末  index=${msg.payload?.index}  ${JSON.stringify(result)}`);
      return;
    }
    if (name === 'TranscriptionCompleted') {
      console.log(`  ${ts()} ✓ 识别结束  status=${status}`);
      return;
    }
    console.log(`  ${ts()} ${name}  full=${JSON.stringify(msg)}`);
  });

  ws.addEventListener('close', (ev) => {
    console.log(`\n  ${ts()} 连接关闭 code=${ev.code} reason=${ev.reason || '(无)'}`);
    console.log(`  事件统计: ${events.join(' → ')}`);
    if (!started) process.exitCode = 1;
    process.exit(process.exitCode ?? 0);
  });

  async function pump() {
    const gap = (CHUNK_BYTES / (SAMPLE_RATE * 2) * 1000) / speed;
    for (let off = 0; off < pcm.length; off += CHUNK_BYTES) {
      if (ws.readyState !== 1) break;
      ws.send(pcm.subarray(off, Math.min(off + CHUNK_BYTES, pcm.length)));
      await new Promise((r) => setTimeout(r, gap));
    }
    console.log(`  ${ts()} 音频推送完毕，发送 StopTranscription`);
    ws.send(JSON.stringify({
      header: {
        appkey: APPKEY,
        message_id: msgId(),
        ...(taskId ? { task_id: taskId } : {}),
        namespace: 'SpeechTranscriber',
        name: 'StopTranscription',
      },
    }));
    // 给服务端一点时间把最后一句话吐完
    setTimeout(() => {
      if (ws.readyState === 1) ws.close();
    }, 8000);
  }

  // 兜底超时
  setTimeout(() => {
    console.error(`\n✗ ${ts()} 超时（90 s），强制退出`);
    try { ws.close(); } catch { /* 已关 */ }
    process.exit(1);
  }, 90_000);
}

void main();
