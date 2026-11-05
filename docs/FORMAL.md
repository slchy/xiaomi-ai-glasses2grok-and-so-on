# 形式化规格 — A5A5 帧

记 `L₁(p, t, s) = build(payload=p, ftype=t, seq=s)`，
`P₁(b) = parse(b)` 返回 `(frames, bad_crc, resync, dropped)`。
`𝔹` 为字节，`|x|` 为长度。

长度与 CRC 按小端解释。字段说明见 [a5a5.md](a5a5.md)。
这里只写本仓库测试覆盖的性质。滑动窗口和重传见 `a5a5.md`，解析器不执行它们。

---

## 1. 帧层

### F1 长度

```
∀ p ∈ 𝔹*, |p| ≤ 65535, ∀ t,s ∈ {0..255}:
    |L₁(p,t,s)| = 8 + |p|
```

8 字节头：magic(2) + ctrl(1) + seq(1) + len(2) + crc16(2)。

### F2 往返

```
∀ 上述 p,t,s:
    P₁(L₁(p,t,s)).frames 恰有一帧
    该帧的 payload、type、seq、len 分别为 p、t、s、|p|
    bad_crc = resync = dropped = 0
```

`type` 是完整的 ctrl 字节。

### F3 magic

```
∀ 上述 p,t,s:  L₁(p,t,s)[0:2] = A5 A5
```

### F4 CRC

`crc16_arc` 是 CRC-16/ARC，覆盖范围只有 payload。
校验值：`crc16_arc("123456789") = 0xBB3D`。

```
∀ p ∈ 𝔹⁺, ∀ payload 内的单比特翻转 e:
    crc16_arc(p ⊕ e) ≠ crc16_arc(p)
```

长度不超过 16 的突发错误、奇数个比特错误，本仓库没有测试，不记为已证。

### F5 解析总会返回

```
∀ b ∈ 𝔹*:  P₁(b) 返回四个非负计数，且 frames 里每帧 total = 8 + len ≤ |b|
```

循环每次至少前进 1 字节，或在剩余长度不足 8 时结束。
不足 8 字节的尾部不计入 `dropped`。例如 `A5 A5 00` 的 `dropped = 0`。

### F7 错误的 CRC 不产出原帧

```
∀ p,t,s:  把 L₁(p,t,s) 的 CRC 低字节异或 0xFF 之后，
    P₁ 不产出 payload = p 且 len = |p| 的帧
```

### F10 ctrl 半字节

```
pack_ctrl(type, frx) = (frx << 4) | type
unpack_ctrl 取出低 4 位和 bit4
type ∈ {0..15}, frx ∈ {0,1}
```

`build` 再 `parse` 之后，`unpack_ctrl(帧.type)` 还原这对值。

### F11 空 payload 的 CRC

```
crc16_arc(空) = 0
L₁(空, pack_ctrl(1, 0), s) = A5 A5 01 s 00 00 00 00
```

---

## 2. L2 头

```
L₂(ch, op, p) = bytes([ch, op]) ‖ p
|L₂| = 2 + |p|
l2_unpack(L₂(ch, op, p)) = (ch, op, p)
```

`ch`、`op` 各是一个字节。`op = 2` 表示剩余字节是密文。算法不在本仓库。

### W2 容器 tag

```
field = type + 2
tag = varint((field << 3) | 2)
wear_container(type, 空) = tag ‖ 00
```

type 2 的 tag 是 `22`。type 14 的 tag 是 `82 01`，不是 `22`。

### M1 单帧上限

```
l2_fits(n, mps) ⟺ n + 2 ≤ mps
```

默认 mps = 64512。这台设备协商后的 mps = 32768。超限不拆帧。
