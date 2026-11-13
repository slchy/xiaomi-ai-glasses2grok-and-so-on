# 许可证与作用域说明 / Licensing & Scopes

[English](#english) | [中文](#中文)

---

<a id="中文"></a>
## 中文说明

本项目包含多种不同性质的工程组件，为了在保障协议事实开放性的同时防范商业侵蚀，按目录与模块精细划分适用不同的许可证：

### 一、 作用域划分

| 模块 / 路径 | 适用许可证 | 说明与限制 |
|---|---|---|
| **1. 协议规范与基础帧参考**<br>`docs/`<br>`python/`<br>`tests/test_frame.py` | **[CC0 1.0 Universal](LICENSES/CC0-1.0.txt)**<br>(Public Domain) | 协议事实、帧定义（A5A5）、CRC 校验及规范文档奉献至公有领域，供任何人免许可费自由参考与学习。 |
| **2. Android SDK 核心驱动**<br>`sdk/core-proto/`<br>`sdk/core-api/`<br>`sdk/core/`<br>`sdk/transport/`<br>`sdk/facade/`<br>`sdk/ktx/` | **[PolyForm Noncommercial 1.0.0](LICENSES/PolyForm-Noncommercial-1.0.0.txt)**<br>(Noncommercial License) | 包含纯手写 Kotlin 状态机、加解密、SAR 滑窗、蓝牙 SPP 连接与事件调度，以及随 `io.github.slchy.glasses2grok:sdk` 一起发布的契约层 `core-api` 与工具层 `core-proto`。<br>**仅限个人非商业研究、学术实验与自用 Agent 对接，严禁任何形式的商业产品化、付费分发、广告营利或收费服务。** |
| **3. 示例与调用代码**<br>`sdk/sample/`<br>README 中的调用示例 | **[MIT License](LICENSES/MIT.txt)** | 供开发者自由复制、修改与参考如何调用 SDK API，无任何使用负担。 |
| **4. 云端语音后端**<br>`cloud/` | **[MIT License](LICENSES/MIT.txt)** | 手机 ↔ 大模型的自建后端：mSBC 解码、判停、ASR/Agent/TTS 编排、原始档案与离线补播。<br>凭据不入库（`.env` / `tokens.txt` 均在 `.gitignore` 里），仓库只提供空模板 `.env.example`。 |

### 二、 商标与设备免责声明 (Disclaimer)

1. **非官方声明**：本项目为独立研究者的第三方开源成果，**与小米科技（Xiaomi）或蜂巢科技（Superhexa）无任何附属、赞助或认可关系**。
2. **商标权属**：所有提及的商标名称（如 "小米"、"Mijia"、"Superhexa" 等）版权均归其各自所有者所有，在此仅用于客观技术兼容性说明。
3. **凭据自备（BYOD 原则）**：本 SDK 仅提供纯粹的传输链路与通信状态机，不提供、不代取、不保存任何硬件访问凭据（Token）。使用本项目的研究者须自行确保对所连接的硬件设备拥有合法的所有权与操作权限。
4. **无担保**：在适用法律允许的最大范围内，本项目按“现有状态（AS-IS）”提供，不提供任何明示或暗示的担保，作者不对因使用本项目导致的硬件损坏、数据丢失或法律纠纷承担责任。

---

<a id="english"></a>
## English Description

This project adopts a tiered multi-licensing architecture to keep protocol specifications openly accessible while strictly safeguarding implementation drivers against unauthorized commercial exploitation:

### 1. Scope Breakdown

| Component / Path | Applicable License | Terms & Scope |
|---|---|---|
| **1. Protocol Specs & Reference**<br>`docs/`<br>`python/`<br>`tests/test_frame.py` | **[CC0 1.0 Universal](LICENSES/CC0-1.0.txt)**<br>(Public Domain) | Protocol facts, frame format (A5A5), CRC checks, and documentation are dedicated to the public domain for unencumbered study and reference. |
| **2. Android SDK Core Drivers**<br>`sdk/core-proto/`<br>`sdk/core-api/`<br>`sdk/core/`<br>`sdk/transport/`<br>`sdk/facade/`<br>`sdk/ktx/` | **[PolyForm Noncommercial 1.0.0](LICENSES/PolyForm-Noncommercial-1.0.0.txt)**<br>(Noncommercial License) | Original Kotlin state machines, encryption, SAR sliding window, Bluetooth SPP transport, and event dispatchers, plus the contract layer (`core-api`) and plumbing (`core-proto`) shipped with `io.github.slchy.glasses2grok:sdk`.<br>**Strictly restricted to non-commercial research, personal experimentation, and self-hosted study. Any commercial exploitation, paid redistribution, or commercial integration is strictly prohibited.** |
| **3. Samples & Boilerplates**<br>`sdk/sample/`<br>Snippets in README | **[MIT License](LICENSES/MIT.txt)** | Sample application and glue code are released under MIT for friction-free reference. |
| **4. Cloud Voice Backend**<br>`cloud/` | **[MIT License](LICENSES/MIT.txt)** | Self-hosted phone ↔ LLM backend: mSBC decoding, endpointing, ASR/Agent/TTS orchestration, raw-session archiving and offline replay.<br>No credentials are shipped (`.env` / `tokens.txt` are gitignored); only the empty template `.env.example` is included. |

### 2. Trademark & Non-Affiliation Disclaimer

1. **Non-Affiliation**: This is an independent open-source research initiative and is **not affiliated with, endorsed by, or sponsored by Xiaomi Inc. or Superhexa Technology**.
2. **Trademarks**: All brand and product names mentioned (including "Xiaomi", "Mijia", "Superhexa") are trademarks or registered trademarks of their respective owners, used here solely for technical compatibility identification.
3. **BYOD (Bring Your Own Device / Token)**: This SDK functions strictly as a communication transport driver. It does not extract, generate, or store device access credentials (Tokens). Researchers must ensure they possess lawful ownership and authorization for the target hardware.
4. **No Warranty**: To the maximum extent permitted by applicable law, this project is provided "AS-IS" without warranty of any kind. The authors disclaim all liabilities for any damages, hardware malfunctions, or legal claims arising from use of this software.
