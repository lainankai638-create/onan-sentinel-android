# P0 Shizuku 连接与首次有效采样

基线为 PR #4 的 `b7bd73c01e5640a688f4cfdd6d7bde39075184d2`。Draft PR 以 PR #4 的头分支为比较基准，便于单独审查本轮差异。《欧南·系统网络哨兵——揪咪技术借鉴与架构优化可行性研究报告》正文未在本会话可访问的 Pages 或基线仓库中定位，实施范围以任务中列明的第 11 节要求为准。

## 修复前后

| 环节 | 修复前 | 修复后 |
| --- | --- | --- |
| 主 Binder、授权 | 客户端授权检查可返回缓存。健康状态查询可能主动解绑 | 主 Binder 死亡优先显示不可用。客户端报告与服务端核实分别显示。健康查询只读 |
| UserService | 绑定中和未绑定可见，失败原因可能被退避状态覆盖 | 区分绑定中、绑定失败和 Binder 已连接，保留本次失败原因 |
| 首次采样及重连 | 总览使用全局运行文案及跨会话的来源最近状态 | 以 `collection_runs` 中当前会话、当前代次和指定目标与来源的最新结果判断。首轮失败、部分成功、采样中断和重连待采样分别显示 |
| 在途结果 | 每个来源可重新获取租约，批次可能跨连接 | 一个批次固定使用同一租约。提交前复核控制令牌、连接代次和服务端授权。新代次重置采样基线与调度等待 |

本轮沿用原有 `OK` 和 `PARTIAL` 有效样本判定，并尊重当前来源的空白期。历史来源记录保留在单独区域。AppOps 只反映模式，进程快照只反映观察时点。

## Redmi K40S 验收 K1～K4

待独立审查及用户授权后执行。设备为 Redmi K40S / Android 14 / HyperOS，记录系统版本、Shizuku 版本、测试包名、签名指纹、APK SHA-256 和各操作时刻。仅安装隔离测试包 `cn.onan.sentinel.preview.p0health`。

| 编号 | 操作 | 预期 |
| --- | --- | --- |
| K1 主服务与授权 | 首次打开时保持 Shizuku 停止，随后用无线调试启动，分别检查未授权和授权状态。点击启动 | 依次看到主服务不可用、待授权、服务端核实和 UserService 绑定进展。Binder 已连接时只能显示待有效采样或实际样本结果 |
| K2 首轮与按来源诊断 | 把间隔设为 1 分钟，核对 3 个目标各自的进程快照和 AppOps。记录可复现的首次失败或单来源失败 | 首次失败无成功时间。单来源成功仅标记对应目标与来源。设备无法自然复现的失败分支记录为待实机，参照自动化结果 |
| K3 主 Binder 中断与重连 | 已有成功样本时从 Shizuku 管理界面停止服务，记录状态。重新启动 Shizuku，观察重新绑定至首个新样本 | 主服务不可用覆盖旧授权状态。重连后先等待新样本，旧成功只在历史中可见。新有效时间晚于恢复动作 |
| K4 撤权、恢复与页面刷新 | 在采样期间撤销测试包授权，反复回到总览并切换页面，再重新授权。若 Shizuku 撤权时结束了应用进程，重新打开后检查授权并手动启动。用应用时间线对照连接和采样记录 | 显示授权失效或重新授权要求，中断后无旧代次写入。刷新不额外产生连接或采样记录。恢复后按新代次各来源重新建立有效状态 |

## 回滚和风险

审查前可关闭 Draft PR。源码回滚可从固定基线另开分支，或仅在本轮分支按反序撤销本轮提交。实机验收如需退出，先停止隔离测试包的监控。原预览包和旧版包各自使用独立应用数据。

Shizuku 13.1.5 SDK 的服务端 AIDL 权限查询通过既有主 Binder 进行。某些 Shizuku 版本会在撤权时强停客户端，届时需要重新打开应用。若服务端无法回答，界面将状态保留为未核实并暂停采样。服务端权限复核与本地 SQLite 写入无法构成跨进程原子操作，撤权发现时机仍需在 Redmi K40S 核实。调度存在轮询间隔，HyperOS 的后台调度和具体 Shizuku 版本行为待实机验收。

## 隔离测试 APK

- 包名 `cn.onan.sentinel.preview.p0health`，版本 `0.3.0-resource-preview.1-p0health`，versionCode 302。
- APK SHA-256 为 `c19fd6606c51b945f3e6512750428844d750116c388db1ebf5e2a3f439a2ca05`。
- `apksigner verify --verbose --print-certs` 校验通过。签名方案为 APK v2，RSA 2048 位，自签测试证书 `CN=Onan Sentinel P0 Test, O=Onan Sentinel, C=CN`，证书 SHA-256 为 `6521d8fdfa4961f8db71a90249cb425822f74cb8ab0ca37ac4aa5ac7283c861e`。
- 与 PR #4 APK 对比，应用 ID 命名空间调整后的 APK 权限列表一致。私钥未提交。
- APK、校验和、签名报告和隔离构建脚本均在 [交付目录](../artifacts/p0-shizuku-health/)。

使用 JDK 17、Gradle 8.9、Android SDK 35、Build Tools 34.0.0。构建使用源码提交 `7372f97`，调试签名文件按仓库既有配置位于 `.local-signing/debug.keystore`。隔离构建命令如下。

```sh
./gradlew -I artifacts/p0-shizuku-health/build-isolated.init.gradle :app:testDebugUnitTest :app:assembleDebug
```

## 测试与核验

- 基线回归提交 `c4d8120` 只改测试。运行 `:app:testDebugUnitTest --tests '*ShizukuBridgeTest'`，6 项中 4 项通过、2 项按预期失败。旧实现把已撤销的缓存授权仍报告为已授权，主 Binder 死亡后读取健康状态触发了 1 次解绑。
- 修复版测试涵盖首次失败、单一来源可用、撤权、主 Binder 死亡并仍有缓存授权、代次切换后的旧结果拒绝、重连等待有效样本，以及诊断刷新时绑定、解绑和采样调用计数。
- APK 已做离线身份、签名、校验和及权限核验。未安装 APK，未执行 Android instrumentation、Redmi K40S 实机验收、真实 Shizuku 撤权与重连、HyperOS 后台调度和原应用数据验收。
- 修复版 `:app:testDebugUnitTest` 运行 13 个测试套件，共 115 项通过，0 失败、0 错误、0 跳过。
- 基线与修复版的 JUnit XML 均已提交。 [测试报告](../artifacts/p0-shizuku-health/reports/)
