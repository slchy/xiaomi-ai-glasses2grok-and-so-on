/**
 * 会话档案的延迟报告：`events.jsonl` → 逐轮「用户说完 → 听到第一个字」分解。
 *
 * ```bash
 * node scripts/latency-report.ts ../docs/archive/20261114-015500-203.0.113.20_2421
 * node scripts/latency-report.ts <root> --all      # root 下每个会话目录各出一份
 * ```
 *
 * 纯读，不改档案。分段口径与缺仪表语义见 `src/latency-report.ts`。
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { computeTurns, formatReport, parseEvents } from '../src/latency-report.ts';

/** 读出单个会话目录的报告文本；没有 `events.jsonl` 时返回一段说明。 */
function reportOne(dir: string): string {
  const file = join(dir, 'events.jsonl');
  let text: string;
  try {
    text = readFileSync(file, 'utf8');
  } catch (error) {
    return `# ${dir}\n\n读不到 events.jsonl：${(error as Error).message}`;
  }
  return formatReport(computeTurns(parseEvents(text)), dir);
}

/** 入口。 */
function main(argv: readonly string[]): number {
  const target = argv.find((a) => !a.startsWith('--'));
  if (!target) {
    console.error('用法: node scripts/latency-report.ts <会话目录|档案根目录> [--all]');
    return 2;
  }

  const all = argv.includes('--all');
  let ok: boolean;
  try {
    ok = statSync(target).isDirectory();
  } catch {
    console.error(`目录不存在: ${target}`);
    return 2;
  }
  if (!ok) {
    console.error(`不是目录: ${target}`);
    return 2;
  }

  const reports: string[] = [];
  if (!all) {
    reports.push(reportOne(target));
  } else {
    const entries = readdirSync(target).sort();
    for (const name of entries) {
      const dir = join(target, name);
      try {
        if (!statSync(dir).isDirectory()) {
          continue;
        }
      } catch {
        continue;
      }
      reports.push(reportOne(dir));
    }
    if (reports.length === 0) {
      console.error(`目录下没有会话: ${target}`);
      return 2;
    }
  }

  console.log(reports.join('\n\n'));
  return 0;
}

process.exit(main(process.argv.slice(2)));
