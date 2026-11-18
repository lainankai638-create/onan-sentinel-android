# v0.3.0-preview.1 测试报告

日期：2026-11-18（Asia/Shanghai）。稳定基线 PR #2：`98e24b454dfc7912b4203a73859485ebe8a20f07`。本轮分支 `codex/v0.3.0-features`。**候选功能已实现并通过以下限定验证；Redmi K40S、24h 与耗电退出验收未完成，保持 Draft。**

## 执行结果与证据层级

| 项目 | 实际执行结果 | 构造输入 / 静态检查 | 尚待目标机 |
| --- | --- | --- | --- |
| APK / 构建 | assembleDebug、assembleDebugAndroidTest 成功；Lint 0错误 / 9警告 | JDK17、Gradle8.9、AGP8.7.3、Kotlin2.0.21；日志在附件 | HyperOS安装及运行 |
| 主机测试 | **91项通过，0失败/错误/跳过**；原52项全部复跑，新增39项 | JUnit + Robolectric API34/native SQLite；假Binder、构造格式/库/桶/权限 | 不把这些算实机验证 |
| 八页 UI | Android14 AOSP x86_64 模拟器，真实 APK 导航测试通过，八页截图 | 页面标题/入口/按钮，真实本地状态，无演示数据注入 | K40S交互、TalkBack、流畅度 |
| 屏宽/字号 | **最终APK六组全部通过，48张实际截图** | 320/360/393dp，各1×浅色与2×深色；八页导航及无文字省略断言 | 字体/厂商渲染、触摸与辅助功能 |
| 进程快照 | 官方 Shizuku 13.6.0，ADB shell UID2000；**最终APK实际返回263项可见进程**，UI显示采样成功（较早构建为262项） | UID/包多归属、用户/双开、未知格式、输出边界、复用一次ps与代次测试 | HyperOS实际列表、AppOps输出、后台持续性 |
| NetworkStats | 在临时模拟器授予Usage Access后实际查询自身UID；Wi-Fi/移动网络均**无桶**，UI显示不可用，非零值 | 12项覆盖禁用、无权限、空桶/真零、共享UID、溢出/负数、取消/重新启用/撤权 | 真实非零流量、共享系统UID、VPN开/关与系统同口径对照 |
| 覆盖升级 | 模拟器 P0 code300 → code301 使用 `adb install -r`；schema2不变；**32条旧事件逐字段一致**，新启动后34条；最终APK查询后38条仍保留原32条，quick_check=ok | 原schema1→2的6项迁移回归全部通过 | 用户手机证书与真实历史尚未取得，不能宣称已验证 |
| 报告 | 半开范围、目标/来源、有效采样间隔、闭合会话、gap交集、时钟倒退、ZIP/JSONL有效性、脱敏与失败清理主机测试通过 | 任意原始文本不进入新报告；外部提供商写入不持DB锁 | 真实历史对照、系统文件选择器保存/取消与不同提供商 |
| 24h / 耗电 / Doze | **未执行** | 不用合成时间或模拟器代替真实24h | 三组真实设备对照；电池、温度、网络、VPN条件记录 |

所有模拟器操作都指定 `emulator-5554`；没有连接或操作用户手机，没有卸载、清理手机SQLite或更改手机VPN。测试器只执行 `UiAcceptanceTest` 与 `LiveSourcesTest`，**未运行会清库的旧 StoreInstrumentedTest**。临时模拟器的字体/显示/动画和 Usage Access 配置只用于测试，不进入产品代码。

## 主机逐组结果

| 测试组 | 通过项数 | 输入与目的 |
| --- | ---: | --- |
| PolicyTest | 9 | 固定命令/目标与模式解析 |
| Android14FormatTest | 9 | No operations + Default mode、AOSP输出与不支持分支 |
| LegacyCompatibilityTest | 3 | 原始数据库只读边界 |
| StoreTest | 8 | 持久化、事务、容量、取消导出与不阻塞写入 |
| CollectionSafetyTest | 4 | 迟到结果、无响应调用、暂停/恢复代次 |
| ShizukuBridgeTest | 4 | 连接/授权/重试假适配器 |
| MigrationTest | 6 | schema1→2、逐字段保留、拒绝未知结构、备份/空间 |
| SourceCoverageTest | 7 | 独立来源状态与空白期 |
| FailureReasonTest | 2 | 存储失败原因 |
| VisibleFeaturesTest | 24 | 快照/归属/中文/批次复用/调用交接/分页/报告/PID证据长度 |
| NavigationTest | 3 | 四入口八页真实控件、明确的空状态与限制 |
| NetworkExperimentTest | 12 | 能力、权限、桶边界、UID去重、失效结果丢弃 |
| 合计 | **91** | 原52 + 新39；逐条XML及机器可读清单在 reports/ |

## 本轮发现并修复

- 首轮 Lint 检出菜单数字ID、常量及API26主题属性问题：改为资源ID、Material常量并去掉仅API27可用的属性。QUERY_ALL_PACKAGES 和 PACKAGE_USAGE_STATS 是实现所需的明确能力，在 Manifest 局部解释 Lint 例外，不关闭全局检查。
- 连续网络查询回归暴露 BoundedCallLane 的任务已返回但 busy 尚未释放竞态：增加完成同步及单个交接槽。执行中的任务仍以 busy 门禁拒绝新任务；超时未响应中断时不继续排队。新增立即连续成功/失败调用回归。
- 网络实验在关闭、离屏或重新开启后，旧 ticket 不可再显示或写入；共享 UID 只查询一次/网络类型。空桶保持 null 计数，不伪造0。
- 超过4096字符的组件PID证据整次标为不可用，避免后续入库截断被误读为完整列表；新增1001个PID的边界回归。最终APK只在前一截图构建上加入这一证据长度检查，UI源码未改。

Lint 的9条警告为目标SDK34（按Android14设备范围）、4条依赖更新提示、旧API备份兼容提示及3条可用磁盘空间建议。未为清除提示而扩大目标平台或自动清理其他应用缓存。

## 模拟器限制与实际失败记录

宿主没有 `/dev/kvm`，使用官方API34默认x86_64镜像、软件CPU/SwiftShader。启动约323秒，构建并发期间出现过 **System UI / Process system ANR**；P0冷启动UserService也发生过连接超时，形成真实空白期。没有掩盖这些记录，也没有据此声称目标机性能合格。

首次360dp浅色仪器测试在宿主重新构建期间被Android以 `timeout publishing content providers` 终止，结果为 `Process crashed`，没有布局断言结果；原始失败及上下文保存在 `reports/initial-attempts/`。停止并发构建、更新最终APK后重跑，不把失败尝试删除或计为通过。

待宿主构建结束后，候选版八页导航通过；实际Shizuku快照测试通过。实时来源仪器测试耗时115.03秒（包含软件模拟器冷启动等待），不是采样延迟基准。没有小米securitycenter/guardprovider/powerkeeper，因此不可能在该模拟器验证这些组件的真实访问或流量。

最终APK另行执行 `LiveSourcesTest` 通过，耗时118.643秒，实际快照263项；两个网络类型均为NO_BUCKETS。原始日志 `reports/live-sources-final.log`，实际截图/结果文字 `screenshots/live-final/`。网络非零流量与VPN对照仍未验证。

## 版本与校验

- applicationId：`cn.onan.sentinel.preview`；versionName：`0.3.0-preview.1`；versionCode：301；schema：2。
- 签名证书SHA-256：`22b00efcde1f683588919a99893917074063928ba845bea6fa9af2a70cace4ce`，与此前交付P0实物一致；用户手机已安装证书未直接提取。
- APK SHA-256：`b15962c055d7e01588709724186dede3431f36928d4408a22df4048a1f0d4ab3`。
- 包含 APK、仪器测试APK、源码ZIP、逐项XML/JSON、构建/Lint日志、升级比对与实际截图：[证据目录](artifacts/v0.3.0-preview.1)。最终文件校验见该目录 SHA256SUMS。

## 独立验收入口

[阶段验收与明确未实现内容](docs/V030_VISIBLE_ACCEPTANCE.md)；[兼容与隔离测试方案](docs/V030_COMPATIBILITY_REVIEW.md)。如手机签名不一致，保留安装和数据库，使用专用模拟器或测试设备；不要求卸载解决。实际访问记录、动态增加关注对象、抓包、云同步与AI控制没有实现，不把路线图或静态页面当作成果。

屏宽/字号最终矩阵：320dp浅色1×、320dp深色2×、360dp浅色1×、360dp深色2×、393dp浅色1×、393dp深色2×，**6/6通过**；每组检查8个页面，共48张截图。位置 `screenshots/final-matrix/`，每组保留独立原始仪器日志。自动断言验证入口/标题及TextView无省略；人工检查了总览、报告及大小字号样本。它不替代K40S实机触摸/TalkBack验收，也不是UI流畅度基准。
