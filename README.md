# 欧南 · 系统网络哨兵 v0.3.0 P0 候选版

本轮实现用户确认的 **P0 可靠性与数据库迁移改造**，不是 v0.3.0 全部路线图功能，也不是已通过实机验收的正式版。起点为 v0.2.0 草稿 PR #1 的 `0a4b12fca2a23c56564ed6134a2c1f805e69afad`。开发分支 `codex/v0.3.0-p0`，不改写 v0.2.0 分支、不合并 main。

| 项目 | 当前候选版 |
| --- | --- |
| applicationId / namespace | `cn.onan.sentinel.preview` / `cn.onan.sentinel` |
| versionName / versionCode | `0.3.0-p0-preview` / 300 |
| min / target / compile SDK | 26 / 34 / 35 |
| 数据库 | 保留 `sentinel_v020.db` 文件名，schema 1→2 |
| APK 签名 | 沿用此前交付 preview 测试密钥；不是原版 `cn.onan.sentinel` 签名 |
| 目标验收设备 | Redmi K40S / Android 14 / HyperOS 1.0.15.0.ULMCNXM |

## 本轮实现

- 分开记录 Binder、授权、UserService、通知渠道和每个采集来源状态；每次绑定有新代次，迟到/重复回调被拒绝，连接失败退避至最多 60 秒。
- 进程/AppOps 各一个进程内共享的调用通道，最多两个探针线程，不排队；取消无法终止远端 Binder 时保持忙碌状态，拒绝继续堆积任务。暂停、停止、代次变化或撤权后在途结果不作为成功样本入库。远端在途命令不保证瞬时取消，仍受原有命令超时约束。
- `来源×目标` 独立状态、尝试记录和空白期；一个来源恢复不关闭其他来源空白期。区分已知停止时刻、发现不可用时刻和未知中断下界，权限不足/格式未知单独呈现。
- schema 1→2 先在应用私有 `noBackupFilesDir` 创建一致的逻辑备份，逐字段摘要比对并检查完整性，再进行事务迁移。原 events/meta 内容不改写；未知结构/版本或空间不足拒绝迁移，不删库重建。
- 旧事件导出标为 `legacy_schema_1`，不补造历史 UID、精确停止时刻或来源覆盖率。保留策略继续生效：迁移事务本身不删旧行，之后的新采样/异常恢复写入仍可能触发原有容量淘汰，并记账。
- 流式 JSONL 导出先生成私有脱敏临时文件，不把整个事件集装入内存，也不持有数据库锁等待外部文档提供商。外部写入失败删除临时文件；进程被杀遗留的脱敏临时文件在下次启动清理。
- 报告增加来源状态、保留范围内最长采样间隔/空白期、时钟回退提示、清理记录。不是全历史事件捕获率。设备上下文保存系统构建 fingerprint 的 SHA-256、SDK、版本和采样间隔；用户电池/网络设置仍需人工记录。

## 保持的安全边界

固定目标仍为 securitycenter、guardprovider、powerkeeper；固定只读命令仍为 `ps -A -w -o UID,PID,NAME` 和 `cmd appops get --user 0 <白名单目标>`。只接受 Shizuku shell UID 2000，无 Root、无 `sh -c`、无修改系统/AppOps、无 INTERNET 或私密内容权限。数据库和导出只保存净化元数据，不自动上传。

原版 `cn.onan.sentinel` 与 `evidence.db` 不受本次改造操作。新增迁移只针对 preview 沙箱的 `sentinel_v020.db`。没有在用户设备上安装、卸载、清理数据或更改设置。

## 升级与备份

仅与此前同证书的 preview 构建具备签名更新条件；是否在目标机无损升级尚待实测。证书 SHA-256：`22b00efcde1f683588919a99893917074063928ba845bea6fa9af2a70cace4ce`。

迁移备份保留到用户停止监控并明确选择“删除本测试包的 schema 1 迁移备份”。备份不是跨包导入，也不是自动降级工具。不要卸载旧版来解决签名问题；schema 2 不支持直接用 v0.2.0 降级打开。清理当前历史不自动清理迁移备份，备份仍含迁移前本地历史。

容量：主库上限 32 MiB、events 最多 20,000 条；来源尝试最多约 2,000 条、已关闭来源空白期最多约 2,000 条，超限回收至 1,000 条，另保留最多六个开放空白期与 100 条清理摘要。主库、日志、备份与导出临时文件共同占空间，不能把 32 MiB 当作总占用上限。迁移预检需要至少旧主库大小×3+8 MiB 的可用空间；导出预检需要 64 MiB 余量，不自动清除用户文件腾空间。

## 构建

完整 JDK 17、Gradle 8.9 Wrapper、AGP 8.7.3、Kotlin 2.0.21、Shizuku 13.1.5。设置 `ANDROID_HOME` 或私有 `local.properties`，安装 `platforms;android-35`、`build-tools;34.0.0`、`platform-tools`。

第一次自行构建时为自己的 preview 生成测试密钥；以后保留该文件。该自建密钥与本次交付密钥不同，不能据此声称能更新已安装的交付 APK。私钥不进入公开源码。

```sh
mkdir -p .local-signing
keytool -genkeypair -keystore .local-signing/debug.keystore -storepass android -keypass android -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 3650
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。主机测试使用 Robolectric Android 34/native SQLite；仪器测试 APK 仅编译，不应在个人数据设备随意运行清理测试。

## 未完成与验收

完整中文四入口/八页面、进程 UID 归属增强、NetworkStats 实验、Markdown 范围报告不是本轮 P0 范围；v0.4.0 敏感访问与跳转、v0.5.0 网络分析未开发。未执行 Redmi K40S 真实升级、撤权/恢复、Doze、24h、耗电或 UI 实机验收。

文档：[P0 设计与边界](docs/V030_P0_DESIGN.md)、[P0 实机验收](docs/V030_P0_ACCEPTANCE.md)、[本次测试报告](TESTING.md)。v0.2.0 历史说明与测试报告保存在 `docs/history/`，历史结果不等于本次结果。上游项目调研及文档中的实机反馈声明未独立复验，不计为已通过验收。
