# Android SDK

`sdk/` 的**生成副本**：由私有开发树导出，测试用 token 已替换为 mock，
真机回放夹具与分析过程记录不包含在内。对账信息见上级目录 `EXPORT-MANIFEST.txt`。

模块：`core-proto`（plumbing）/ `core-api`（契约）/ `core`（引擎）/
`transport`（Android SPP）/ `facade`（公开门面）/ `ktx`（Flow）/ `sample`（示例 App）。

```bash
./gradlew build        # Windows: gradlew.bat build
```

许可见上级目录 [LICENSE.md](../LICENSE.md)。
