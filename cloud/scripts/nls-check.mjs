/**
 * 阿里云 NLS 权限交叉验证。
 *
 *   node scripts/nls-check.mjs
 *
 * 目的：当实时识别握手报 40000002 时，用其它接口判断问题出在
 *   - Token/凭据（TTS 和一句话识别都会失败），还是
 *   - 只是「实时语音识别」这个功能没在项目里开通（其它接口正常）
 *
 * 全程只发最小请求，不传业务音频。
 */
import { createHmac, randomUUID } from 'node:crypto';

const AK_ID = process.env['ALIYUN_AK_ID'];
const AK_SECRET = process.env['ALIYUN_AK_SECRET'];
const APPKEY = process.env['ALIYUN_APPKEY'];
const REGION = process.env['ALIYUN_NLS_REGION'] ?? 'cn-shanghai';

const pct = (s) => encodeURIComponent(s)
  .replace(/\+/g, '%20').replace(/\*/g, '%2A').replace(/%7E/g, '~');

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
  const canonical = Object.keys(params).sort()
    .map((k) => `${pct(k)}=${pct(params[k])}`).join('&');
  const stringToSign = `POST&${pct('/')}&${pct(canonical)}`;
  const signature = createHmac('sha1', `${AK_SECRET}&`).update(stringToSign).digest('base64');
  const res = await fetch(`https://nls-meta.${REGION}.aliyuncs.com/`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: `${canonical}&Signature=${pct(signature)}`,
    });
  const text = await res.text();
  if (!res.ok) throw new Error(`CreateToken ${res.status}: ${text.slice(0, 300)}`);
  const j = JSON.parse(text);
  if (!j.Token?.Id) throw new Error(`无 Token.Id: ${text.slice(0, 200)}`);
  return j.Token;
}

async function checkTts(token) {
  const url = `https://nls-gateway-${REGION}.aliyuncs.com/stream/v1/tts`;
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-NLS-Token': token },
    body: JSON.stringify({
      appkey: APPKEY,
      text: '测试',
      format: 'wav',
      voice: 'xiaoyun',
      sample_rate: 16_000,
      volume: 50,
      speech_rate: 0,
      pitch_rate: 0,
    }),
  });
  const ct = res.headers.get('content-type') ?? '';
  const body = await res.text();
  const isAudio = ct.includes('audio') || body.startsWith('RIFF');
  console.log(`\n[语音合成]  HTTP ${res.status}  content-type=${ct}  ${isAudio ? '✓ 返回了音频' : ''}`);
  if (!isAudio) console.log(`  body: ${body.slice(0, 300)}`);
  return res.status === 200 && isAudio;
}

async function checkOneShot(token, pcm) {
  const q = new URLSearchParams({
    appkey: APPKEY,
    format: 'pcm',
    sample_rate: '16000',
  });
  const url = `https://nls-gateway-${REGION}.aliyuncs.com/stream/v1/asr?${q}`;
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'X-NLS-Token': token, 'Content-Type': 'application/octet-stream' },
    body: pcm,
  });
  const body = await res.text();
  console.log(`\n[一句话识别]  HTTP ${res.status}`);
  console.log(`  body: ${body.slice(0, 300)}`);
  try {
    const j = JSON.parse(body);
    return j.status === 20000000;
  } catch {
    return false;
  }
}

async function main() {
  if (!AK_ID || !AK_SECRET || !APPKEY) {
    console.error('缺少 ALIYUN_AK_ID / ALIYUN_AK_SECRET / ALIYUN_APPKEY');
    process.exit(2);
  }
  console.log(`REGION = ${REGION}`);
  console.log(`APPKEY = ${APPKEY}`);
  console.log(`AK_ID  = ${AK_ID.slice(0, 4)}****${AK_ID.slice(-2)}  (len=${AK_ID.length})`);

  const token = await createToken();
  console.log(`\n[CreateToken]  ✓  expires ${new Date(token.ExpireTime * 1000).toISOString()}`);

  const ttsOk = await checkTts(token.Id);
  // 一句话识别：用 0.3 秒静音判定权限即可，不必真语音
  const silence = Buffer.alloc(16_000 * 2 * 0.3);
  const asrOk = await checkOneShot(token.Id, silence);

  console.log('\n─────────────────────────');
  console.log(`语音合成      ${ttsOk ? '✓ 通了' : '✗ 失败'}`);
  console.log(`一句话识别    ${asrOk ? '✓ 通了' : '✗ 失败'}`);
  console.log('\n判读：');
  if (ttsOk && asrOk) {
    console.log('  AppKey 与账号权限都正常 → 只是「实时语音识别」没在项目里开通。');
  } else if (!ttsOk && !asrOk) {
    console.log('  AppKey / 账号权限有问题 → 去控制台确认服务已开通、AppKey 属于当前账号。');
  } else {
    console.log('  部分接口可用 → 按上面逐项核对项目里勾选的功能。');
  }
}

void main().catch((e) => {
  console.error(`✗ ${e.message}`);
  process.exit(1);
});
