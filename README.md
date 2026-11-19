# 欧南 · 系统网络哨兵：进程资源能力诊断候选版

本分支 `codex/process-resource-diagnostic` 基于 PR #3 的 `7171564bf0e2d3cac2ee23ad3ad18b9a2fff771b`，版本 **0.3.0-resource-preview.1 / 302**。新增「总览 → 资源能力诊断」手动入口：最多 4 个可见代表进程、两次元数据采样、CPU 差值与 RSS、逐项来源/失败原因、本地确认后脱敏 JSON 导出。仍为 `cn.onan.sentinel.preview`，沿用 preview 签名，数据库 schema 2 不变。

本轮 **22 项相关主机测试通过，一次 APK 构建成功**；未重新执行全套测试、Lint 或截图矩阵，尚未在 Redmi K40S 上验证。详见 [本轮操作、验收及测试报告](docs/RESOURCE_DIAGNOSTIC.md) 与 [APK、测试 XML、构建日志及校验文件](artifacts/resource-diagnostic/)。以下内容保留为 PR #3 的功能基线说明，其中版本与测试结论仅属于该基线。

## PR #3 功能基线（0.3.0-preview.1）

在 PR #2 的 P0 基线 `98e24b454dfc7912b4203a73859485ebe8a20f07` 上继续实现 R03-03、R03-05、R03-06、R03-07。独立分支 `codex/v0.3.0-features`，Draft PR 基于 `codex/v0.3.0`；不修改 PR #2、PR #1 或 main，不发布正式版。P0 原说明保存在 [历史文档](docs/history/V030_P0_README.md)。

| 项目 | 本候选版 |
| --- | --- |
| applicationId / namespace | `cn.onan.sentinel.preview` / `cn.onan.sentinel` |
| versionName / versionCode | `0.3.0-preview.1` / **301**（P0 为 300） |
| 数据库 | 原 `sentinel_v020.db`，仍为 **schema 2**；不重建、不清理 |
| 证书 SHA-256 | `22b00efcde1f683588919a99893917074063928ba845bea6fa9af2a70cace4ce` |
| 目标平台 | Redmi K40S / Android 14 / HyperOS 1.0.15.0.ULMCNXM |
| SDK | min 26 / target 34 / compile 35 |

## 可操作功能

- Material 3 四底部入口：总览、应用、时间线、报告。八个业务页面有真实状态、查询和操作；浅色、深色及跟随系统主题。总览显示来源最近成功、空白期和失败原因；技术代码保留在详情中。
- 手动刷新全机**可见**进程快照，显示 PID、UID、用户、进程名与包候选；分类、搜索和分页。包信息不可得时显示未知，共享 UID 保留全部候选；PID 变化不能认定重启。后台三个固定组件每轮复用一次 ps 快照。
- 应用详情显示可用包版本与历史进程/AppOps 记录；敏感行为页**只显示操作模式，访问记录未启用**。allow 不是实际访问。
- 时间线和报告使用半开时间范围、固定应用与来源筛选。本地导出 ZIP 包内包含中文 `概览.md` 和 `明细.jsonl`，保存前展示统计与脱敏提示。显示有效采样、各来源最长采样间隔、已闭合全局会话时长、进程变化与来源空白期。
- NetworkStatsManager 实验默认关闭，开启后仍仅手动查询最近 24 小时；用户自行在系统页面授予使用情况访问权。仅查询固定组件 UID 与哨兵自身 UID，按 UID 去重，不把共享身份流量独占归给某个包。无桶、权限拒绝、系统不支持不显示为零。查询桶范围、重复查询与 VPN 归属限制均明确提示。

进程快照只在前台用户刷新时读取，完整列表不入库；后台继续只关注 `securitycenter`、`guardprovider`、`powerkeeper`。网络统计是独立可关闭的实验来源，**不是常驻网络监控或抓包**。按应用筛选报告仅匹配固定组件的持续采样；网络统计需选“全部应用”，保留 UID 组匿名身份，避免共享 UID 误归属。

## 安全与兼容性

没有 INTERNET、VPNService、任意 Shell、Root、系统设置写入、日志上传、内容采集或 TLS 拦截。Shizuku UserService 只接受 shell UID 2000；命令仍为固定 `ps -A -w -o UID,PID,NAME` 和白名单 `cmd appops get --user 0`。UI 快照服务与后台服务使用不同组件身份，离开 UI 不会关闭后台采集服务。

新增 QUERY_ALL_PACKAGES 只为将未知进程 UID 对应到全部包候选；不读取应用内容、不上传安装清单。PACKAGE_USAGE_STATS 通过用户主动授权的 app-op 探测，不假定普通运行时权限能授予。Manifest 对这两项有局部 Lint 解释；其余 Lint 仍阻断错误。

沿用本工作区此前交付的 preview 签名；交付 APK 的签名与 P0 APK 一致。**没有读取用户手机上已安装 APK 的证书或真实数据库**，因此不能直接宣称已验证用户设备无损升级。若手机确为此前同签名 P0 APK，可按 Android 覆盖更新路径测试；签名不匹配时不要卸载、不要清库，在专用模拟器/测试设备隔离验收。详见 [兼容性说明](docs/V030_COMPATIBILITY_REVIEW.md)。私钥不提交到公开仓库。

本轮没有新 schema。原 schema 1→2 事务备份迁移及回归测试保留；schema 2 历史原样读取。新采样仍受既有保留策略约束（主库 32 MiB、事件 20,000 条、辅助表有界），容量淘汰会留摘要；不能把迁移无损等同于无限期保留。原 `cn.onan.sentinel` 沙箱和 `evidence.db` 不访问。清理入口仅响应用户明确确认，升级不触发清理。

## 构建

JDK 17、Gradle 8.9 Wrapper、AGP 8.7.3、Kotlin 2.0.21；SDK platform 35、build-tools 34.0.0、platform-tools。配置 `ANDROID_HOME` 或私有 `local.properties`。

从源码自行构建时，需要本地 `.local-signing/debug.keystore`。若已有预览签名文件，直接沿用；**不要重新生成覆盖**。首次在专用测试环境构建可生成自己的测试密钥：

```sh
mkdir -p .local-signing
test -e .local-signing/debug.keystore || keytool -genkeypair -keystore .local-signing/debug.keystore -storepass android -keypass android -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 3650
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

自行生成的密钥不等于交付密钥，不能据此覆盖已安装交付 APK。APK 输出 `app/build/outputs/apk/debug/app-debug.apk`。发布附件、逐项测试 XML、截图及 SHA-256 在 [artifacts/v0.3.0-preview.1](artifacts/v0.3.0-preview.1)。源码 ZIP 不含签名私钥或本机 SDK。

## 验收状态

本候选版完成上述功能实现，不等于完整 v0.3.0 已通过退出验收。[测试报告](TESTING.md) 区分主机、模拟器与待实机验证；[阶段验收清单](docs/V030_VISIBLE_ACCEPTANCE.md) 给出每个模块的操作步骤和降级行为。

尚待 Redmi K40S：真实升级逐条历史比对、HyperOS 格式、24h/Doze、耗电三组对照、VPN 开关下系统统计比较、TalkBack 人工操作与 UI 性能。实际敏感访问记录、动态增加关注对象、抓包/PCAP、云同步及 AI 控制未实现，属于后续范围。进程启动身份没有内核 starttime 证据，故不报告确定重启。
