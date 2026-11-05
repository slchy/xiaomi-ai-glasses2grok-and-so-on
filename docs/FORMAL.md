# 形式化规格 — A5A5 帧

记 `L₁(p, t, s) = build(payload=p, ftype=t, seq=s)`，
`P₁(b) = parse(b)` 返回 `(frames, bad_crc, resync, dropped)`。
`𝔹` 为字节，`|x|` 为长度。

长度与 CRC 按小端解释。字段说明见 [a5a5.md](a5a5.md)。

---

## 1. 帧层

### F1 长度

```
∀ p ∈ 𝔹*, |p| ≤ 65535, ∀ t,s ∈ {0..255}:
    |L₁(p,t,s)| = 8 + |p|
```

8 字节头：magic(2) + type(1) + seq(1) + len(2) + crc16(2)。

### F2 往返

```
∀ 上述 p,t,s:
    P₁(L₁(p,t,s)).frames 恰有一帧
    该帧的 payload、type、seq、len 分别为 p、t、s、|p|
    bad_crc = resync = dropped = 0
```

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

主规格还写了「长度不超过 16 的突发错误」和「奇数个比特错误」。
公开测试没有覆盖这两条，这里不把它们写成已证。

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

---

## 2. L2 头

```
L₂(ch, op, p) = bytes([ch, op]) ‖ p
|L₂| = 2 + |p|
parse 还原 (ch, op, p)
```

`ch, op` 各是一个字节。本目录不规定 opcode 的加密语义。

---
