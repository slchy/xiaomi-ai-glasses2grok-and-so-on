/**
 * `isBackchannelOnly` 的用例验证。
 *
 * ```bash
 * cd cloud && node tests/backchannel-verify.ts
 * ```
 *
 * 用例分两组：**必须判为旁听反馈**（不能开一轮）和**必须放行**（照常进 agent）。
 * 后者更重要 —— 误判会把用户的真实请求吞掉，而且是静默吞掉。
 */
import { isBackchannelOnly } from '../src/backchannel.ts';

interface Case {
  readonly text: string;
  readonly backchannel: boolean;
  readonly note: string;
}

const CASES: readonly Case[] = [
  // ---- 必须判为旁听反馈
  { text: '嗯。', backchannel: true, note: '单个语气词' },
  { text: '嗯嗯。', backchannel: true, note: '叠用' },
  { text: '嗯嗯嗯', backchannel: true, note: '多叠' },
  { text: '啊，哦。', backchannel: true, note: '混合语气词' },
  { text: '嗯嗯，我知道了我知道了。', backchannel: true, note: '实测原句' },
  { text: '我知道了。', backchannel: true, note: '纯回执' },
  { text: '知道了知道了。', backchannel: true, note: '回执叠用' },
  { text: '呃…明白了。', backchannel: true, note: '语气词 + 回执' },
  { text: '收到，收到。', backchannel: true, note: '回执叠用' },

  // ---- 必须放行（误判静默吞请求，代价更高）
  { text: '好的。', backchannel: false, note: '可能是对确认请求的应答' },
  { text: '好。', backchannel: false, note: '同上' },
  { text: '行。', backchannel: false, note: '同上' },
  { text: '对。', backchannel: false, note: '同上' },
  { text: '嗯，拍照。', backchannel: false, note: '语气词后有实义请求' },
  { text: '明白了，那第二点呢？', backchannel: false, note: '有后续请求' },
  { text: '我知道了，帮我查一下天气。', backchannel: false, note: '后半是请求' },
  { text: '哎，别别别别退。', backchannel: false, note: '实测原句' },
  { text: '你在干什么？', backchannel: false, note: '正常提问' },
  { text: '你是谁？', backchannel: false, note: '正常提问' },
  { text: '这台电脑配置怎么样？', backchannel: false, note: '正常提问' },
  { text: '', backchannel: false, note: '空串不是旁听反馈' },
  { text: '。。。', backchannel: false, note: '只有标点' },
];

let failed = 0;

console.log('=== isBackchannelOnly 用例 ===');
for (const c of CASES) {
  const actual = isBackchannelOnly(c.text);
  const ok = actual === c.backchannel;
  if (!ok) {
    failed += 1;
  }
  const want = c.backchannel ? '旁听' : '放行';
  const got = actual ? '旁听' : '放行';
  console.log(
    `  ${ok ? 'ok  ' : 'FAIL'}  ${JSON.stringify(c.text).padEnd(28)}`
    + ` 期望 ${want} / 实际 ${got}   — ${c.note}`,
  );
}

console.log();
if (failed > 0) {
  console.log(`✗ ${failed} 条不符合预期`);
  process.exit(1);
}
console.log(`✓ 全部 ${CASES.length} 条符合预期`);
