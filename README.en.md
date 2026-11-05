# xiaomi-ai-glasses2grok-and-so-on

[中文](README.md)

Connect Xiaomi AI Glasses to your own voice agent. Uplink speech is SPP MASS_VOICE (channel 3). Downlink playback is A2DP. HFP/SCO is connected and is not used on this path.

This repository currently publishes only the A5A5 frame protocol implementation. The application agent lives in a private implementation.

The frame protocol is in [docs/a5a5.en.md](docs/a5a5.en.md). Tested properties are in [docs/FORMAL.en.md](docs/FORMAL.en.md). The implementation is `python/a5a5.py`.

```bash
python tests/test_frame.py
```

```python
from a5a5 import build, parse

frame = build(b"hi", ftype=3, seq=0)
frames, bad_crc, resync, dropped = parse(frame)
```

Put `python/` on `PYTHONPATH` before the call above.

License: [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/).
