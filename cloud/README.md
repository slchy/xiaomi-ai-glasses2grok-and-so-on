# cloud/ — 已迁出

**云端语音栈已经搬到独立仓库：[`dsh-pet-wearable`](../../dsh-pet-wearable)。**

这里是本项目的**研究线**（眼镜协议协议分析 + SDK + 手机端）。云端语音后端是**产品线**，
两边零代码依赖，只通过线协议通信，所以分仓维护。

## 为什么分

| | 这个仓库 | dsh-pet-wearable |
|---|---|---|
| 关心什么 | 能不能连上、协议怎么解 | 语音好不好用、宠物像不像活的 |
| 主要资产 | Kotlin SDK、协议分析笔记、手机端 App | 6000 行语音栈、宠物核心、dsh 插件 |
| 变更节奏 | 摸到新命令才动 | 调参数是日常 |

## 迁走了什么

用 `git subtree split -P cloud` 提取（51 个 commit 的历史全部保留在新仓）：

- `src/` —— 传输、mSBC 解码、能量门、判停、ASR/TTS、对话决策、情绪、档案
- `tests/` —— 单测与回放
- `smart_turn/` —— Python 判停 sidecar
- `mcp/` —— `take_photo` 等 MCP 工具
- `harness.py` —— 早期原型（保留在研究线的历史里，新仓未带）
- 文档 → 新仓 `docs/`（`CLOUD-ARCHITECTURE.md` 等四份）

## 这里的手机端还依赖什么

手机 App（`sdk/sample`）只做一件事：**把眼镜的 mSBC 原样推给云端**（TCP 5 字节帧）。
协议定义见新仓 `src/frame.ts` / `src/transport.ts`。

改设备侧（协议、SDK、App）留在本仓库；改语音/对话/宠物去新仓库。
