# 阶段一：进程资源能力诊断交付附件

版本 `0.3.0-resource-preview.1`（302），基线 PR #3。只完成本阶段，保持 Draft。

- `onan-sentinel-0.3.0-resource-preview.1.apk`：同 preview 包名/签名的调试候选包。
- `onan-sentinel-resource-source.zip`：本次完整构建源码、测试及文档，不含私钥、SDK、构建缓存和历史二进制附件。
- `SHA256SUMS`：本目录 APK、源码 ZIP 和报告的校验值。在本目录执行 `sha256sum -c SHA256SUMS`。
- `reports/`：一次构建原日志、22项相关测试 XML 与 JSON 汇总、APK 签名比对。

[测试报告、操作说明与限制](../../docs/RESOURCE_DIAGNOSTIC.md)。尚未在 K40S 或模拟器运行新版，旧界面截图不作为本次诊断页面证据。没有重跑全套测试、Lint 或截图矩阵。签名不兼容时不卸载旧应用、不清库，改用隔离测试设备。
