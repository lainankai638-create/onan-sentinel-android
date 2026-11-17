# v0.2.0 测试与交付报告

日期：2026-11-18（Asia/Shanghai）。本次结论：**隔离测试版可编译，主机测试通过；尚未完成目标手机验收，不能认定达到 24 小时连续监测或原包升级条件。**

## 已执行结果

环境：Linux x86_64，完整 Temurin JDK 17，Gradle 8.9，AGP 8.7.3，Kotlin 2.0.21，Android platform 35 / Build Tools 34.0.0。使用本地 SDK 和代理下载公开依赖；没有连接 Redmi K40S。

执行任务：`:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest`。

| 检查 | 实际结果 | 证据与范围 |
| --- | --- | --- |
| Debug APK 编译 | 通过 | build.log：BUILD SUCCESSFUL；最终 APK 可验证签名 |
| JVM 策略测试 | 9/9 通过 | 固定命令、非法参数、进程精确匹配/主用户、格式失败、AppOps 脱敏/拒绝、基线重置、合成 24h 时间循环 |
| Android 14 格式回归 | 9/9 通过 | AOSP 源码格式合成样本；空记录+默认模式、错误优先/矛盾输出、UID 前缀歧义、运行/持续时间后缀、空格进程名、厂商未知格式、模式变化与基线重置 |
| SQLite 主机测试 | 8/8 通过 | Android 34 Robolectric + native SQLite；重开持久化、异常停止、空白期、事务回滚、导出不阻塞采集、脱敏、清理保护、20,100 条压力写入与保留上限 |
| 旧库保留测试 | 3/3 通过 | v0.1.1 DDL 合成样本；新库操作/清理前后旧库 SHA-256 一致；未知 schema 拒绝；无旧库时不创建 |
| Android Lint | 0 errors / 5 warnings | targetSdk 34 非最新版、两项测试依赖可升级、旧 API 备份配置提示、中文动态字符串本地化提示；完整报告随附 |
| 测试 APK 编译 | 通过 | 仅证明设备测试代码可编译，**未执行 connectedAndroidTest** |
| APK 签名 | 通过 | apksigner 验证 APK Signature Scheme v2，单个 RSA 2048 测试证书 |
| APK ZIP 对齐 | 通过 | zipalign -c -v 4 |
| APK 实际权限 | 已检查 | 仅 FGS、FGS_SPECIAL_USE、POST_NOTIFICATIONS、Shizuku API_V23；无 INTERNET 或私密内容权限 |

总计 **29 项主机测试，0 失败、0 错误、0 跳过**。合成 24h 循环只验证比较逻辑，不代表应用运行了 24 小时。测试 XML、Lint 文本、构建日志在 `artifacts/v0.2.0/reports/`。

## 交付物身份

- 基线：`c5b14b5bb2d754015ce087a5cd18fbef3dfd8f91`（公开仓库 main 的 v0.1.1）。
- 包名：`cn.onan.sentinel.preview`；版本：`0.2.0-preview` / code 200；minSdk 26 / targetSdk 34。
- APK SHA-256：`33ec0155c2e3cc0325a6a6608cad9702773664478ea72782758daaf8145de9bf`。
- 测试证书 SHA-256：`22b00efcde1f683588919a99893917074063928ba845bea6fa9af2a70cace4ce`。
- APK 为可调试隔离测试包，不是生产签名升级包。私钥、SDK、Gradle 缓存和 local.properties 不进入仓库/源码压缩包。
- 自行按 README 生成的测试密钥与本次 APK 签名不同；需要更新已安装的 preview 时应保留相应测试密钥与数据。原应用签名从未更改。

## 已实现但未通过实机验证

前台启动/通知/暂停/停止、Shizuku 连接与授权状态、UserService 超时与恢复、目标进程与 AppOps 采样、真实空白期、SQLite 保留、界面筛选、SAF 脱敏导出均已有实现。主机测试覆盖纯逻辑和数据库，不能代替系统 Binder、真实 Shell、前台服务生命周期和厂商系统行为。

| 实机项目 | 状态 | 未验证原因 |
| --- | --- | --- |
| Redmi K40S 连续运行 24h | 未执行 | 无目标设备与实际 24h 采样记录 |
| HyperOS 进程/AppOps 输出兼容 | 未执行 | 没有该系统的实际命令输出；未知格式会报告不支持 |
| Shizuku 断线/撤权/恢复 | 未执行 | 没有实际 Shizuku Binder 会话；仅静态检查及基线/空白期逻辑测试 |
| Android 14 FGS/通知/锁屏/Doze | 未执行 | 无真实系统生命周期运行结果 |
| 实机持久化/系统杀进程 | 未执行 | 主机 SQLite 测试已通过，但不是目标机验证 |
| 耗电 | 未执行 | 无 24h 对照基线；未宣称低功耗达标 |
| 真实旧数据/原包升级 | 未执行且不可交付升级包 | 缺原签名和真实数据库；预览包不能跨沙箱读取旧数据 |

验收步骤与记录模板见 [DEVICE_ACCEPTANCE.md](docs/DEVICE_ACCEPTANCE.md)。未对用户手机安装、卸载、清库或调整设置。

## 当前无法保证或不在本版范围内

无法保证厂商系统下不间断运行；前台服务与 Shizuku 都可能被系统或用户停止。进程状态只是周期性可见快照，AppOps 只覆盖可读取的部分操作模式，不能证明具体数据读取内容、网络外传或恶意行为。完整抓包、云端同步、自主 AI 控制未实现。未尝试绕过这些限制。

## 构建中已解决的问题

初次构建暴露 AIDL 方法编号、主机 JDK 缺少 jlink、代理 CA 信任和 Robolectric 下载锁目录不可写，均已修正后重新完整运行上述任务。复查发现的导出持锁、空白期下界遗漏、第二空间进程混入主用户问题已修正并加入回归测试。历史失败日志不作为最终测试结果。

## PR #1 本次格式修复

对照 Android 14 AOSP 源码，确认 `No operations.` 后追加 `Default mode: allow` 是合法格式；该分支在 AOSP 对指定单个操作的查询输出，当前无操作参数的白名单命令通常不会进入该分支，HyperOS 实际输出仍待核对。新增 9 项回归测试先在原解析器运行，结果 7 失败/2 通过（`reports/regression-before.log`），再进行最小修复。

生产代码仅修改 `Policy.kt`：严格识别空记录与可选默认模式；不再把无前缀 AppOps 行猜测为 PACKAGE，改标 UNSCOPED；进程行只拆前两个数字字段，允许 NAME 含空格。未知或矛盾输出不转换为“空记录成功”，不保存原始附加信息。既有数据库、历史证据、命令白名单、包名、版本号和测试签名保持不变；没有旧版数据迁移。之前保存的 PACKAGE 标签不可用于确认实际作用域。

修复后重新执行本报告全部构建、29 项测试及 Lint。APK 与源码 ZIP 已更新；此次 APK 替代原 SHA-256 为 `1cdd15e4dd0b919a56c50832d5655320203cd91728a4611e50664033c11c5ce9` 的预览构建。样本来源、HyperOS 可能差异与边界见 [ANDROID14_FORMAT_REVIEW.md](docs/ANDROID14_FORMAT_REVIEW.md)。PR 保持 Draft，不合并 main；实机验收仍未执行。
