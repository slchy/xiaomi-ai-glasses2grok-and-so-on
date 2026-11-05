"""A5A5 帧的确定性检查。规格见 docs/a5a5.md、docs/FORMAL.md；英文见 docs/a5a5.en.md、docs/FORMAL.en.md。

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


def test_ctrl_nibble_roundtrip():
    assert a5a5.pack_ctrl(a5a5.TYPE_DATA, 0) == 3
    assert a5a5.pack_ctrl(a5a5.TYPE_DATA, 1) == 0x13
    assert a5a5.unpack_ctrl(0x13) == (a5a5.TYPE_DATA, 1)
    frame = a5a5.build(b"x", a5a5.pack_ctrl(a5a5.TYPE_DATA, 1), 4)
    frames, bad, resync, dropped = a5a5.parse(frame)
    assert (bad, resync, dropped) == (0, 0, 0)
    assert a5a5.unpack_ctrl(frames[0]["type"]) == (a5a5.TYPE_DATA, 1)


def test_empty_ack_is_eight_zero_tail():
    frame = a5a5.build(b"", a5a5.pack_ctrl(a5a5.TYPE_ACK, 0), 2)
    assert frame == b"\xa5\xa5\x01\x02\x00\x00\x00\x00"
    assert a5a5.crc16_arc(b"") == 0


def test_wear_tag_follows_type():
    expect = {
        1: b"\x1a",
        2: b"\x22",
        13: b"\x7a",
        14: b"\x82\x01",
        18: b"\xa2\x01",
        20: b"\xb2\x01",
        23: b"\xca\x01",
    }
    for packet_type, tag in expect.items():
        assert a5a5.wear_tag(packet_type) == tag
        assert a5a5.wear_container(packet_type) == tag + b"\x00"
    assert a5a5.wear_tag(14) != a5a5.wear_tag(2)


def test_mps_is_a_hard_cap():
    assert a5a5.l2_fits(32766, a5a5.DEVICE_MPS)
    assert not a5a5.l2_fits(32767, a5a5.DEVICE_MPS)
    assert a5a5.l2_fits(64510, a5a5.DEFAULT_MPS)
    assert not a5a5.l2_fits(64511, a5a5.DEFAULT_MPS)
    assert a5a5.DEVICE_TX_WINDOW == 3
    assert a5a5.LOCAL_TX_WINDOW == 32


def test_mass_voice_packet_layout():
    assert a5a5.VOICE_HEADER_LEN + a5a5.MSBC_FRAMES_PER_PACKET * a5a5.MSBC_FRAME_LEN == a5a5.VOICE_PACKET_LEN
    body = bytes([a5a5.MSBC_SYNC]) + b"\x00" * (a5a5.MSBC_FRAME_LEN - 1)
    payload = a5a5.VOICE_HEADER + body * a5a5.MSBC_FRAMES_PER_PACKET
    assert a5a5.voice_payload_ok(payload)
    broken = bytearray(payload)
    broken[a5a5.VOICE_HEADER_LEN] = 0
    assert not a5a5.voice_payload_ok(bytes(broken))


def test_mass_header_fields():
    end = a5a5.parse_mass(b"\x00\x03")
    assert end["command"] == a5a5.MASS_CMD_END
    start = a5a5.parse_mass(b"\x00\x01\x00\x01\xc8")
    assert start["command"] == a5a5.MASS_CMD_START and start["param"] == 0xC8
    voice = a5a5.parse_mass(a5a5.VOICE_HEADER)
    assert voice["command"] == a5a5.MASS_CMD_DATA
    assert voice["mass_type"] == 2 and voice["total_len"] == 0 and voice["chunk_len"] == 570
    image = bytearray(a5a5.VOICE_HEADER)
    image[4:6] = (3).to_bytes(2, "big")
    image[6:10] = (1000).to_bytes(4, "big")
    parsed = a5a5.parse_mass(bytes(image))
    assert parsed["mass_type"] == 3 and parsed["total_len"] == 1000
    assert len(a5a5.VOICE_HEADER) == 12


def test_incomplete_frame_is_not_dropped():
    header = b"\xa5\xa5\x03\x00" + (100).to_bytes(2, "little") + b"\x00\x00"
    frames, bad, resync, dropped = a5a5.parse(header)
    assert frames == [] and (bad, resync, dropped) == (0, 0, 0)


def test_downlink_header_is_little_endian():
    digest = bytes(range(16))
    header = a5a5.downlink_header(1, 2, digest, 0x01020304)
    assert len(header) == 22
    assert header[0] == 0 and header[1] == 0x12
    assert header[2:18] == digest
    assert header[18:22] == (0x01020304).to_bytes(4, "little")


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
