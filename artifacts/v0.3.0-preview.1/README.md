# v0.3.0-preview.1 交付文件

本目录是开发候选附件，不是正式发布。包名 `cn.onan.sentinel.preview`，versionCode 301，schema 2。

- `onan-sentinel-0.3.0-preview.1.apk`：供明确知悉预览状态的兼容升级/隔离验收。
- `onan-sentinel-0.3.0-preview.1-source.zip`：完整工程源码、Gradle Wrapper及文档；不含签名私钥、SDK、本机路径配置或历史数据库。
- `onan-sentinel-0.3.0-preview.1-androidTest.apk`：仪器测试器。只在临时模拟器上指定安全测试类；不要在个人设备运行默认整套测试，旧 `StoreInstrumentedTest` 会清理历史。
- `SHA256SUMS`：本目录全部交付文件的SHA-256，执行 `sha256sum -c SHA256SUMS` 核对。
- `reports/host-test-results.json` 和 `reports/TEST-*.xml`：91项主机测试逐条结果。
- `reports/build-delivery.log`、`lint-results-debug.txt`、`apk-*`：最终构建、Lint、包身份、权限与签名；较早构建日志仅供追溯。
- `reports/emulator-upgrade.json`：P0到候选的模拟器32条旧事件逐字段比对；不是用户手机真实数据库迁移结果。
- `screenshots/final-matrix/`：最终APK在320/360/393dp、浅色1×与深色2×下的八页面截图与仪器测试日志。
- `screenshots/live-final/`：最终APK实际263项进程快照与NetworkStats无桶结果；对应 `reports/live-sources-final.log`。
- `screenshots/live/`：早期同UI候选（APK SHA `51fda5a8e6180854c5ed8736b525ec6c4cf02cf76ef512f52d74bae61037a44b`）的实际262进程快照、NetworkStats无桶结果；完整最终验证状态以根目录 [TESTING.md](../../TESTING.md) 为准。
- `reports/initial-attempts/`：没有删掉的模拟器启动失败证据。无KVM软件模拟器不提供手机流畅度、耗电或24h结论。

签名证书SHA-256：`22b00efcde1f683588919a99893917074063928ba845bea6fa9af2a70cace4ce`。与此前交付P0的证书一致；未直接检查用户手机证书。签名不匹配时**不要卸载、不要清库**，用专用模拟器或测试设备隔离验收。

Android实现提交见 `reports/delivery-review.json`；源码包附 `SOURCE_REVISION.txt`，说明Android实现与最终交付文档的关系。仓库PR最新提交包含全部附件，保持Draft等待Redmi K40S验收。
