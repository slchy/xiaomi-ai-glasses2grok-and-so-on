#!/usr/bin/env node
/**
 * MCP server：把「结束本次对话」暴露成 dsh 的工具 `mcp__session__end_session`。
 *
 * 为什么不用关键词表：退出意图是**语义**问题，不是字符串匹配问题。
 * 关键词表既漏（「退下吧」「不用了」「先到这」）又误伤（「结束了」是正常语句），
 * 而且每加一种说法就要改代码。交给模型判断本身，才和「拍照由模型决定」一致。
 *
 * 这里只做一件事：POST 本机控制口，让 harness 挂个「本轮说完就结束」的旗子。
 * 真正断连接、播告别语都在 harness 侧 —— 模型自己说的话就是告别语，
 * 不再有硬编码的 EXIT_REPLY。
 *
 * 协议：MCP stdio = 每行一条 JSON-RPC 2.0。
 */
import process from 'node:process';

const CTL = process.env.SESSION_CTL_URL ?? 'http://127.0.0.1:8767/end_session';

const TOOL = {
  name: 'end_session',
  description:
    '结束这次语音对话，挂断与用户眼镜的连接。'
    + '**只在用户明确要求结束 / 道别 / 让你退下时调用**，'
    + '例如「退出」「结束吧」「再见」「不聊了」「先这样」「退下吧」「关掉」。'
    + '调用前**先把告别的话说完** —— 你说完这句，连接就会被关闭。'
    + '不要因为用户只是暂时不需要某个东西、或表达了否定（如「不用了谢谢」指某件事）就调用；'
    + '拿不准时不要调用，继续正常回答。',
  inputSchema: { type: 'object', properties: {}, additionalProperties: false },
};

const send = (obj) => {
  process.stdout.write(`${JSON.stringify(obj)}\n`);
};

async function callTool(id, name) {
  if (name !== TOOL.name) {
    send({
      jsonrpc: '2.0',
      id,
      result: { content: [{ type: 'text', text: `未知工具：${name}` }], isError: true },
    });
    return;
  }
  try {
    const res = await fetch(CTL, { method: 'POST' });
    if (!res.ok) {
      throw new Error(`HTTP ${res.status}：${(await res.text()).slice(0, 200)}`);
    }
    send({
      jsonrpc: '2.0',
      id,
      result: {
        content: [{
          type: 'text',
          text: '已安排好：这句话播完就断开连接。不要再追加内容。',
        }],
      },
    });
  } catch (e) {
    send({
      jsonrpc: '2.0',
      id,
      result: { content: [{ type: 'text', text: `结束会话失败：${e.message}` }], isError: true },
    });
  }
}

function handle(msg) {
  const { id, method, params } = msg;
  switch (method) {
    case 'initialize':
      send({
        jsonrpc: '2.0',
        id,
        result: {
          protocolVersion: params?.protocolVersion ?? '2025-06-18',
          capabilities: { tools: {} },
          serverInfo: { name: 'miai-session', version: '1.0.0' },
        },
      });
      return;
    case 'notifications/initialized':
    case 'notifications/cancelled':
      return;
    case 'ping':
      send({ jsonrpc: '2.0', id, result: {} });
      return;
    case 'tools/list':
      send({ jsonrpc: '2.0', id, result: { tools: [TOOL] } });
      return;
    case 'tools/call':
      void callTool(id, params?.name);
      return;
    default:
      if (id !== undefined) {
        send({ jsonrpc: '2.0', id, error: { code: -32601, message: `未知方法 ${method}` } });
      }
  }
}

let buf = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (d) => {
  buf += d;
  let nl = buf.indexOf('\n');
  while (nl >= 0) {
    const line = buf.slice(0, nl).trim();
    buf = buf.slice(nl + 1);
    nl = buf.indexOf('\n');
    if (!line) continue;
    try {
      handle(JSON.parse(line));
    } catch {
      // 畸形行直接丢，别让整个工具进程退出
    }
  }
});
