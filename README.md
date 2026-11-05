# xiaomi-ai-glasses2grok-and-so-on

[English](README.en.md)

把小米 AI 眼镜接到自己的语音代理。上行语音在 SPP 的 MASS_VOICE（channel 3），下行播放走 A2DP。HFP/SCO 已连接，但这条链路不用。

本仓库目前仅公开 A5A5 帧协议实现，业务端代理位于私有实现。

帧层协议见 [docs/a5a5.md](docs/a5a5.md)，已测性质见 [docs/FORMAL.md](docs/FORMAL.md)。实现在 `python/a5a5.py`。

```bash
python tests/test_frame.py
```

```python
from a5a5 import build, parse

frame = build(b"hi", ftype=3, seq=0)
frames, bad_crc, resync, dropped = parse(frame)
```

调用时把 `python/` 放进 `PYTHONPATH`。

许可：[CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/)。
