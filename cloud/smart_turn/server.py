#!/usr/bin/env python3
"""Smart Turn v3.2 侧车：常驻进程，按帧收 PCM，回一个「这句说完了吗」的概率。

为什么单独起一个 Python 进程，而不是在 Node 里做：

  Smart Turn 的输入是 Whisper 风格的 log-mel（80×800），要先做 **400 点**
  rFFT（400 = 2^4 × 5^2，不是 2 的幂，手写混合基 FFT 容易出错）。
  参考实现把这份特征提取用 numpy 写成了一份自包含实现（见 whisper_features.py），
  直接复用比在 TS 里重写可靠得多。

协议（stdin/stdout，二进制，小端）：

  请求  [u32 len][int16 LE PCM，16 kHz 单声道]
  响应  [f64 概率]        概率越接近 1 表示「这一轮说完了」

进程常驻：模型只加载一次，单次推理约 10–100 ms。
"""
from __future__ import annotations

import os
import struct
import sys

import numpy as np
import onnxruntime as ort

from whisper_features import compute_whisper_log_mel_features

_HERE = os.path.dirname(os.path.abspath(__file__))
_DEFAULT_MODEL = os.path.join(_HERE, "..", "models", "smart-turn-v3.2-cpu.onnx")
_MODEL_PATH = os.environ.get("SMART_TURN_MODEL", _DEFAULT_MODEL)

_MAX_SAMPLES = 16_000 * 8  # 模型固定吃 8 秒


def log(msg: str) -> None:
    # stdout 是协议通道，日志只能走 stderr
    print(f"[smart-turn] {msg}", file=sys.stderr, flush=True)


def load_session() -> "ort.InferenceSession":
    so = ort.SessionOptions()
    so.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    so.inter_op_num_threads = 1
    so.intra_op_num_threads = 1
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    s = ort.InferenceSession(_MODEL_PATH, sess_options=so)
    log(f"模型已加载 {_MODEL_PATH}")
    return s


def predict(session: "ort.InferenceSession", pcm: bytes) -> float:
    audio = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0

    # 参考实现约定：不足 8 秒就**在前面补零**，让真实音频对齐到末尾；
    # 超过 8 秒则从开头截断，只保留最近的 8 秒。
    if audio.size > _MAX_SAMPLES:
        audio = audio[-_MAX_SAMPLES:]
    if audio.size < _MAX_SAMPLES:
        audio = np.pad(audio, (0, _MAX_SAMPLES - audio.size), mode="constant")

    feats = compute_whisper_log_mel_features(audio, do_normalize=True)
    inputs = np.expand_dims(feats, axis=0)  # (1, 80, 800)
    out = session.run(None, {"input_features": inputs})
    return float(np.asarray(out[0]).reshape(-1)[0])


def read_exact(stream, n: int) -> bytes | None:
    buf = b""
    while len(buf) < n:
        chunk = stream.read(n - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf


def main() -> int:
    session = load_session()
    stdin = sys.stdin.buffer
    stdout = sys.stdout.buffer

    while True:
        head = read_exact(stdin, 4)
        if head is None:
            return 0
        (size,) = struct.unpack("<I", head)
        if size == 0 or size > 16_000 * 2 * 60 * 4:
            log(f"异常帧长 {size}，退出")
            return 1
        body = read_exact(stdin, size)
        if body is None:
            return 0
        try:
            prob = predict(session, body)
        except Exception as e:  # noqa: BLE001 - 单次失败不该杀掉常驻进程
            log(f"推理失败: {e}")
            prob = -1.0
        stdout.write(struct.pack("<d", prob))
        stdout.flush()


if __name__ == "__main__":
    sys.exit(main())
