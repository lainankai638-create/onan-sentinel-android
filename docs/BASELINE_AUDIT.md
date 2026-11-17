# v0.1.1 旧工程审计

检索日期：2026-11-18（Asia/Shanghai）。

来源：https://github.com/lainankai638-create/onan-sentinel-android/tree/c5b14b5bb2d754015ce087a5cd18fbef3dfd8f91

分支：main；提交：`c5b14b5bb2d754015ce087a5cd18fbef3dfd8f91`，提交标题 `Import Onan Sentinel Android 0.1.1`。原快照保存在 `legacy-reference/v0.1.1`。远端 main 未被本次开发改写。

| 项目 | 检查结果 |
| --- | --- |
| 工程完整性 | Gradle Kotlin DSL、Wrapper jar/脚本/配置、Android manifest、5 个 Kotlin 源文件、AIDL、资源、5 项旧测试均可读取 |
| 构建版本 | Gradle 8.9、AGP 8.7.3、Kotlin 2.0.21、Shizuku 13.1.5；与本版基础工具链一致 |
| 应用标识 | `cn.onan.sentinel`，versionCode 2，versionName 0.1.1，minSdk 29，targetSdk 34，compileSdk 35 |
| Shizuku | 受保护 Provider；Activity 内绑定 UserService；固定 ps 命令，传入正则约束包名；手动快照；退出 Activity 时释放 |
| 后台 | SampleJob 使用 JobScheduler 进行 UID 流量快照；不等于本次要求的可见前台长期进程/AppOps 监测 |
| 数据库 | `evidence.db` schema 1；evidence(id,time,target,kind,grade,source,data)，target/time 索引；无破坏性迁移，但有手动 clear 与按天 prune |
| 签名 | 仓库无可用更新密钥；README 明确 v0.1.0 调试密钥丢失、v0.1.1 换过调试签名；不能推断当前手机签名 |
| 既有记录 | TESTING.md 记载构建成功、5 项 JUnit、0 lint errors/3 warnings、APK hash；无原始日志/APK文件，标为历史声明 |
| 既有抓包相关功能 | PCAPdroid 调用、CSV/PCAP 导入、附原始文件 ZIP；本次不扩展，参考快照保留，本次 APK 不包含这些入口 |

## v0.2.0 改动与兼容策略

保留 namespace、Gradle/Wrapper 版本和 Shizuku Provider 机制；将 UI 绑定的单次采集重构为前台服务持有连接。命令接口进一步收紧为整数白名单，新增 AppOps 部分模式采样、主用户过滤、输出/超时上限和撤权后结果丢弃。

采集数据库新建为 `sentinel_v020.db`，不复用/升级/清理 `evidence.db`。`LegacyEvidenceReader` 只用 OPEN_READONLY 验证固定 schema 与行数，未发现旧文件时不会创建它；未知 schema 不尝试修复。合成测试使用原 DDL，检查新库操作前后旧文件 SHA-256 字节一致。此检查不是手机真实数据库验收，也不是跨包数据导入。

未安装 APK、未卸载旧版、未重签旧 APK、未修改旧库或用户设备设置。新的调试密钥只签署 `.preview` 包。旧 README 的卸载建议不属于本次操作说明；本次要求保留旧版。

## 仍需要的证据

真实已安装 APK 的签名证书 SHA-256、可用更新密钥、真实数据库副本、Redmi K40S 设备上的授权/保活/耗电记录。取得前不发布原包名覆盖升级版。
