# miaiglasses2grokbot

把小米 AI 眼镜接到自己的语音代理。麦克风和播放走系统蓝牙：HFP 采集，A2DP 播放。

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
