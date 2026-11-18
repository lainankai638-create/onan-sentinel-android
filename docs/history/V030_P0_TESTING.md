# v0.3.0 P0 候选版测试报告

日期：2026-11-18（Asia/Shanghai）。基线：`0a4b12fca2a23c56564ed6134a2c1f805e69afad`；按用户指定公开分支：`codex/v0.3.0`（本地初始实现分支名为 `codex/v0.3.0-p0`）。结论：**P0 代码与主机回归已完成本轮验证；v0.3.0 全功能、实机升级与 24h 退出验收未完成，不可视为正式发布。**

## 执行与证据分层

| 项目 | 实际执行并通过 | 静态审查 | 模拟或构造输入 | 尚未实机执行 |
| --- | --- | --- | --- | --- |
| 构建 | assembleDebug、assembleDebugAndroidTest 成功 | manifest 权限/版本核对 | 不适用 | 安装与用户操作 |
| 测试 | 52 项，0 失败/错误/跳过 | 新旧状态与库边界复查 | JUnit + Robolectric Android 34/native SQLite，假 Shizuku 适配器 | 真实 Shizuku Binder/HyperOS |
| 来源空白期 | 单源恢复、权限/格式失败、停止拥有者隔离通过 | 来源×目标六组合、原全局字段兼容 | 合成时间/证据样本 | 真实断连、撤权、休眠与重启 |
| 迁移 | 6 项迁移测试通过 | SQLiteOpenHelper 升级事务、私有备份、保留规则 | 原 schema DDL，20,000 行数据，空间探针拒绝、事务异常、未知 schema | 用户真实 DB、物理断电、真实磁盘耗尽、恢复演练 |
| 有界调用 | 4 项安全控制 + 4 项桥接测试通过 | 两个共享零队列线程池、代次与授权校验 | 忽略中断的假调用、迟到回调、假授权/重连 | 目标机线程/内存长期曲线 |
| 导出/存储故障 | 阻塞 Writer 不持 DB 锁、失败临时文件清理、满页事务回滚通过 | 游标/临时快照分块输出 | Writer 故障、max_page_count 限制 | 实际 SAF 提供商、大文件设备性能 |
| Lint | 0 errors / 7 warnings | 见完整 lint-results-debug.txt | 不适用 | UI/无障碍实机验收 |
| 24h/耗电 | 未执行 | 已提供方案与记录字段 | 原合成 24h 基线测试仍只测逻辑 | 全部真实对照试验 |

完整任务：`:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest`，最终日志 `BUILD SUCCESSFUL in 1m 2s`。这不是 24 小时运行时间。最终测试 XML 时间戳约为北京时间 12:54，原 XML 使用 UTC；每项耗时保留在 XML。没有独立目标设备实验起止记录。

## 测试组成

| 测试类 | 通过数量 | 验证内容 |
| --- | --- | --- |
| PolicyTest | 9 | 原白名单、解析/脱敏、基线、合成时间循环 |
| Android14FormatTest | 9 | 原 Android 14 文本格式回归，非厂商实机样本 |
| LegacyCompatibilityTest | 3 | 原版 evidence.db 合成样本只读、不修改/不创建 |
| StoreTest | 8 | 原持久化、事务、空白期、保留、阻塞导出、脱敏 |
| CollectionSafetyTest | 4 | 代次、暂停/停止丢弃、100 次重试不新增假调用、单通道隔离 |
| ShizukuBridgeTest | 4 | 旧回调、授权撤销、恢复/超时退避、拒绝 root/无授权绑定 |
| MigrationTest | 6 | 旧值/备份、满行数、空间不足、失败回滚/重试、未知结构/版本、自增高水位 |
| SourceCoverageTest | 7 | 分来源恢复、起点语义、保留上限、统计时钟回退、导出失败、SQLite 满页回滚、旧 Service 退出隔离 |
| FailureReasonTest | 2 | 保留可行动错误码；拒绝泄露任意异常文本 |

原有 29 项重新执行，新增 23 项；不是沿用旧结果。SQLite 满页使用 `PRAGMA max_page_count` 注入限制，不是填满宿主磁盘；升级中断通过事务中抛异常模拟，不是真实断电。旧库样本是合成数据，不含用户私人记录。

## 构建环境与 APK

Linux x86_64；完整 JDK 17；Gradle 8.9（Wrapper）、AGP 8.7.3、Kotlin 2.0.21；SDK platform 35 / Build Tools 34.0.0；Shizuku API/provider 13.1.5。直接依赖版本固定，Wrapper 分发 SHA-256 固定；尚未引入完整传递依赖校验锁文件，不宣称构建可逐字节复现。

- 包名：`cn.onan.sentinel.preview`；版本：`0.3.0-p0-preview` / code 300；min 26 / target 34。
- APK SHA-256：`7c81f8b07be32121afb93e6b0093d2db5301329ca021539206ca0cc501966b0f`。
- 证书 SHA-256：`22b00efcde1f683588919a99893917074063928ba845bea6fa9af2a70cace4ce`，与 v0.2.0 交付一致，未改变签名。
- APK 与源码 ZIP、逐项测试 XML、Lint、构建日志、签名/权限/ZIP 对齐输出、SHA256SUMS 放在 `artifacts/v0.3.0-p0/`。
- Lint 7 项警告：目标 API 34 非最新版、两个测试依赖可升级、旧 API 备份声明提示、动态中文字符串提示、两项 UsableSpace 建议。空间检查采用保守可用空间，不自动回收其他缓存；这些警告未作为实机通过证据。
- 仪器测试 APK 仅构建，未执行 connectedAndroidTest。未连接/安装任何用户手机；未替换原版应用或操作真实数据库。

## 实现与剩余门槛

已实现：绑定代次/恢复退避、失效结果拒收、探针线程/排队上限、来源独立状态/空白、事务迁移前校验备份、失败可见、旧记录来源标记、清理记账、流式脱敏导出与保留范围统计。

尚未达到的 P0 验收门槛：目标机升级、真实 Binder/撤权/通知/Doze、实际磁盘与掉电故障、24h/耗电对照。现有主机测试不能证明这些通过。等待实机反馈；出现问题继续修复，不合并 main。用户上传研究文档的“初步实机观察”没有对应实验数据，本报告不提升为独立复验。

完整中文导航/八页面、进程归属增强、NetworkStats、范围 Markdown 报告不在本轮 P0；敏感访问、跳转调查与抓包均未加入。详见 [P0 设计](docs/V030_P0_DESIGN.md) 和 [实机验收表](docs/V030_P0_ACCEPTANCE.md)。

## 升级数据边界

迁移事务保留原 events/meta 值及自增高水位，新增表不回填过去事件事实。迁移后的异常会话恢复与新采样会继续容量淘汰规则，因此满额库在后续写入时可能清理历史并留下 retention_log；私有迁移备份仍保留原 schema 1 数据。该备份只在用户停止监控并明确删除时清理。不能直接用 v0.2.0 降级打开 schema 2，不提供自动卸载/重置作为回退。

## 公开推送前复核

本次推送准备仅更新分支名称、兼容性说明和交付校验清单，未改变生产代码或 APK，未把已有 52 项结果描述成重新跑过。已重新校验全部测试 XML、构建日志、交付文件 hash，以及 v0.2.0/v0.3.0 两份 APK 的包名和签名。原 events/meta/index DDL 与 v0.2.0 基线一致。

**当前手机的已安装证书与真实数据库没有提供，因此真实历史数据迁移仍为未验证。** 现有 6 项迁移测试使用原 schema 的合成样本。详细结论与签名不兼容时的专用设备/模拟器隔离方案见 [V030_COMPATIBILITY_REVIEW.md](docs/V030_COMPATIBILITY_REVIEW.md)，机器可读复核结果见 `artifacts/v0.3.0-p0/reports/delivery-review.json`。
