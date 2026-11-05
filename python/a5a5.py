"""A5A5 帧。

布局（小端）:
    magic(2) = A5 A5
    ctrl(1)      低 4 位 type，bit4 = frx
    seq(1)
    len(2)       payload 长度
    crc16(2)     CRC-16/ARC(payload)
    payload
    总长 = 8 + len
"""
from __future__ import annotations

MAGIC = b"\xa5\xa5"
HDR_LEN = 8

TYPE_NAK = 0
TYPE_ACK = 1
TYPE_CMD = 2
TYPE_DATA = 3


def pack_ctrl(l1_type: int, frx: int = 0) -> int:
    """线字节 = (frx << 4) | type。type 占低 4 位，frx 只占 bit4。"""
    if not 0 <= l1_type <= 0x0F or frx not in (0, 1):
        raise ValueError("type 须在 0..15，frx 须为 0 或 1")
    return (frx << 4) | l1_type


def unpack_ctrl(byte: int) -> tuple[int, int]:
    """返回 (type, frx)。bit5–7 不计入 frx。"""
    return byte & 0x0F, (byte >> 4) & 1


def crc16_arc(data: bytes) -> int:
    """CRC-16/ARC：反射多项式 0xA001，init 0，xorout 0。"""
    crc = 0
    for b in data:
        crc ^= b
        for _ in range(8):
            if crc & 1:
                crc = (crc >> 1) ^ 0xA001
            else:
                crc >>= 1
    return crc & 0xFFFF


def build(payload: bytes, ftype: int = 3, seq: int = 0) -> bytes:
    hdr = MAGIC + bytes([ftype]) + bytes([seq]) + len(payload).to_bytes(2, "little")
    return hdr + crc16_arc(payload).to_bytes(2, "little") + payload


def parse(data: bytes):
    """返回 (frames, bad_crc, resync, dropped)。

    剩余长度不足 8 时直接结束，这截字节不计入 dropped。
    """
    frames = []
    bad_crc = 0
    resync = 0
    i = 0
    n = len(data)
    dropped = 0
    while i + HDR_LEN <= n:
        if data[i : i + 2] != MAGIC:
            k = data.find(MAGIC, i + 1)
            if k < 0:
                dropped += n - i
                break
            dropped += k - i
            resync += 1
            i = k
            continue
        ftype = data[i + 2]
        seq = data[i + 3]
        length = int.from_bytes(data[i + 4 : i + 6], "little")
        crc_field = int.from_bytes(data[i + 6 : i + 8], "little")
        total = HDR_LEN + length
        if i + total > n:
            dropped += n - i
            break
        payload = data[i + HDR_LEN : i + total]
        if crc16_arc(payload) != crc_field:
            bad_crc += 1
            i += 1
            continue
        frames.append(
            {
                "off": i,
                "type": ftype,
                "seq": seq,
                "len": length,
                "crc": crc_field,
                "payload": payload,
                "total": total,
            }
        )
        i += total
    return frames, bad_crc, resync, dropped


def l2_pack(channel: int, opcode: int, payload: bytes) -> bytes:
    return bytes([channel, opcode]) + payload


def l2_unpack(data: bytes):
    if len(data) < 2:
        raise ValueError("L2 太短: %d" % len(data))
    return data[0], data[1], data[2:]
