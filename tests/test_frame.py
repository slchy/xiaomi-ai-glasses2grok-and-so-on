"""A5A5 帧的确定性检查。规格见 docs/a5a5.md、docs/FORMAL.md。

    python tests/test_frame.py
"""
from __future__ import annotations

import os
import random
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "python"))

import a5a5


def test_crc_vector():
    assert a5a5.crc16_arc(b"123456789") == 0xBB3D


def test_F1_F2_F3():
    payloads = [b"", b"\x00", b"\xa5\xa5abc", b"\x11" * 22, b"\x22" * 29, b"\xff" * 300, b"\x10" * 65535]
    for p in payloads:
        for t in (0, 1, 2, 3, 255):
            for s in (0, 7, 255):
                frame = a5a5.build(p, t, s)
                assert len(frame) == 8 + len(p), "F1"
                assert frame[:2] == b"\xa5\xa5", "F3"
                frames, bad, resync, dropped = a5a5.parse(frame)
                assert (bad, resync, dropped) == (0, 0, 0), "F2 counts"
                assert len(frames) == 1, "F2 count"
                got = frames[0]
                assert got["payload"] == p and got["type"] == t and got["seq"] == s
                assert got["len"] == len(p) and got["total"] == 8 + len(p)


def test_F4_single_bit():
    payload = bytes(range(32))
    expect = a5a5.crc16_arc(payload)
    for i in range(len(payload)):
        for bit in range(8):
            mutated = bytearray(payload)
            mutated[i] ^= 1 << bit
            assert a5a5.crc16_arc(bytes(mutated)) != expect


def test_F5_returns_and_short_tail_not_dropped():
    frames, bad, resync, dropped = a5a5.parse(b"\xa5\xa5\x00")
    assert frames == [] and (bad, resync, dropped) == (0, 0, 0)

    frames, bad, resync, dropped = a5a5.parse(b"\x00" * 10 + b"\xa5\xa5")
    assert frames == [] and resync == 1 and dropped == 10

    rng = random.Random(0)
    for n in range(80):
        blob = bytes(rng.getrandbits(8) for _ in range(n))
        frames, bad, resync, dropped = a5a5.parse(blob)
        assert bad >= 0 and resync >= 0 and dropped >= 0
        for frame in frames:
            assert frame["total"] == 8 + frame["len"] <= len(blob)


def test_F7_bad_crc_drops_frame():
    payload = b"hello"
    frame = bytearray(a5a5.build(payload, 3, 1))
    frame[6] ^= 0xFF
    frames, bad, _, _ = a5a5.parse(bytes(frame))
    assert bad == 1
    assert not any(item["payload"] == payload and item["len"] == len(payload) for item in frames)


def test_L2_roundtrip():
    payload = b"abc"
    for channel in range(16):
        for opcode in range(4):
            packed = a5a5.l2_pack(channel, opcode, payload)
            assert len(packed) == 2 + len(payload)
            assert a5a5.l2_unpack(packed) == (channel, opcode, payload)


def _run() -> int:
    tests = [(n, f) for n, f in sorted(globals().items()) if n.startswith("test_")]
    failed = []
    for name, fn in tests:
        try:
            fn()
            print("  PASS %s" % name)
        except AssertionError as exc:
            print("  FAIL %s  %s" % (name, exc))
            failed.append(name)
        except Exception as exc:  # noqa: BLE001
            print("  ERR  %s  %r" % (name, exc))
            failed.append(name)
    print("%d passed, %d failed" % (len(tests) - len(failed), len(failed)))
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(_run())
