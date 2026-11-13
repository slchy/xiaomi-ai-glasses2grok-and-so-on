# silero-vad 模型

`silero_vad.onnx`（2 327 524 字节）**不进版本库**——二进制文件不适合放 git。

## 获取

```bash
curl -L -o models/silero_vad.onnx \
  https://cdn.jsdelivr.net/gh/snakers4/silero-vad@master/src/silero_vad/data/silero_vad.onnx
```

- **来源**：`snakers4/silero-vad`（MIT 许可）
- **为什么用 jsdelivr**：GitHub raw 和 raw.githubusercontent 在本机实测连不上，jsdelivr 可用
- **部署到 VPS 时**：如果 VPS 也连不上 GitHub，同样用上面的 jsdelivr 地址；或者从本机 `scp`

## 校验

    node -e "const o=require('onnxruntime-node');o.InferenceSession.create('models/silero_vad.onnx').then(s=>console.log(s.inputNames,s.outputNames))"

应输出：

    [ 'input', 'state', 'sr' ] [ 'output', 'stateN' ]

## v5 的两个接口陷阱

见 `src/vad.ts` 顶部注释。简要：

1. 输入是 `[1, 576]` 不是 `[1, 512]` —— 前面 64 个采样点是上一帧的 context
2. 值域是 `[-1, 1]`（int16 / 32768），不是 int16 原始值

喂错了**不会报错**，只会输出恒定 0.0005 左右——看起来像模型坏了。
