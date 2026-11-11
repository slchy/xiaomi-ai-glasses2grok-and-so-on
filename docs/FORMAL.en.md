# Formal specification — A5A5 frame

[中文](FORMAL.md)

Let `L₁(p, t, s) = build(payload=p, ftype=t, seq=s)`,
and let `P₁(b) = parse(b)` return `(frames, bad_crc, resync, dropped)`.
`𝔹` is a byte, and `|x|` is length.

Length and CRC are little-endian. Field descriptions are in [a5a5.en.md](a5a5.en.md).
Only properties covered by this repository's tests are stated here. The sliding window and retransmission are described in `a5a5.en.md`; the parser does not run them.

---

## 1. Frame layer

### F1 Length

```
∀ p ∈ 𝔹*, |p| ≤ 65535, ∀ t,s ∈ {0..255}:
    |L₁(p,t,s)| = 8 + |p|
```

The 8-byte header is magic(2) + ctrl(1) + seq(1) + len(2) + crc16(2).

### F2 Round trip

```
∀ p,t,s above:
    P₁(L₁(p,t,s)).frames has exactly one frame
    that frame's payload, type, seq, len are p, t, s, |p|
    bad_crc = resync = dropped = 0
```

`type` is the whole ctrl byte.

### F3 magic

```
∀ p,t,s above:  L₁(p,t,s)[0:2] = A5 A5
```

### F4 CRC

`crc16_arc` is CRC-16/ARC and covers only the payload.
Check value: `crc16_arc("123456789") = 0xBB3D`.

```
∀ p ∈ 𝔹⁺, ∀ single-bit flip e inside the payload:
    crc16_arc(p ⊕ e) ≠ crc16_arc(p)
```

Bursts of length at most 16, and errors of odd bit count, are not tested in this repository and are not claimed as proved.

### F5 Parsing always returns

```
∀ b ∈ 𝔹*:  P₁(b) returns four non-negative counts,
            and every frame has total = 8 + len ≤ |b|
```

Each loop iteration advances at least 1 byte, or stops when fewer than 8 bytes remain.
A tail shorter than 8 bytes is not counted in `dropped`. For `A5 A5 00`, `dropped` is 0.
A tail that matches magic but whose declared length runs past the buffer is not counted in `dropped` either.

### F7 A bad CRC does not emit the original frame

```
∀ p,t,s:  after XORing the low CRC byte of L₁(p,t,s) with 0xFF,
    P₁ does not emit a frame with payload = p and len = |p|
```

### F10 ctrl nibble

```
pack_ctrl(type, frx) = (frx << 4) | type
unpack_ctrl takes the low 4 bits and bit4
type ∈ {0..15}, frx ∈ {0,1}
```

After `build` then `parse`, `unpack_ctrl(frame.type)` restores that pair.

### F11 CRC of an empty payload

```
crc16_arc(empty) = 0
L₁(empty, pack_ctrl(1, 0), s) = A5 A5 01 s 00 00 00 00
```

---

## 2. L2 header

```
L₂(ch, op, p) = bytes([ch, op]) ‖ p
|L₂| = 2 + |p|
l2_unpack(L₂(ch, op, p)) = (ch, op, p)
```

`ch` and `op` are one byte each. `op = 2` means the remaining bytes are ciphertext (AES-CTR; see `sdk/core` for the cipher implementation).

### W2 Container tag

```
field = type + 2
tag = varint((field << 3) | 2)
wear_container(type, empty) = tag ‖ 00
```

The tag for type 2 is `22`. The tag for type 14 is `82 01`, not `22`.

### M1 Per-frame cap

```
l2_fits(n, mps) ⟺ n + 2 ≤ mps
```

The default mps is 64512. After negotiation this device uses mps = 32768. An oversized frame is not split.

### V1 MASS_VOICE packet length

```
582 = 12 + 10 × 57
only dialog-audio DATA with MassType 2 and [6:10] = 0 has this shape
a length of 582 does not separate audio from images
```

### I1 id and field number

`WearPacket.id` has no formula relating it to the protobuf field number. The container field number remains `type + 2`.
