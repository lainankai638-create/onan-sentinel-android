# 进程资源能力诊断：阶段一交付与测试报告

日期：2026-11-19（Asia/Shanghai）。基线 PR #3：`7171564bf0e2d3cac2ee23ad3ad18b9a2fff771b`。独立分支 `codex/process-resource-diagnostic`；Draft PR 的 base 为 `codex/v0.3.0-features`。本阶段结束后不自动进入下一阶段。

## 交付与兼容性

| 项目 | 本候选版 |
| --- | --- |
| applicationId | `cn.onan.sentinel.preview`，与此前交付 preview 相同 |
| versionName / versionCode | `0.3.0-resource-preview.1` / **302**，基线为 301 |
| 证书 SHA-256 | `22b00efcde1f683588919a99893917074063928ba845bea6fa9af2a70cace4ce` |
| APK SHA-256 | `92ecd2d04612829978dace688606efa67b7b60362771dad4a42a5f00cc4767ee` |
| 数据库 | 原 schema 2；本轮未修改 EventStore、建表或迁移逻辑，诊断不写历史库 |
| 附件 | [APK、完整构建源码 ZIP、校验文件和原始结果](../artifacts/resource-diagnostic/) |

交付 APK 的包名和证书与仓库已有 PR #3 APK 一致，可用于该签名 preview 的覆盖更新。没有连接用户手机，不能声称已经核验手机上安装包的证书、历史数据或升级效果。如果系统提示签名不兼容，停止安装，保留旧应用和数据，在专用测试设备或隔离模拟器测试；不卸载、不清库。源码不附私钥，自建密钥不能替代交付签名。

## 已完成的实现与边界

- 「总览 → 资源能力诊断」为独立页面，用户按按钮才执行一次诊断。采用现有 Shizuku 只读 UserService，限定运行身份为 ADB shell UID 2000；独立组件避免诊断页面关闭时解绑后台监控服务。无新的后台轮询。
- 只执行已有固定 `ps -A -w -o UID,PID,NAME` 以发现候选。最多选择采集服务自身、关注组件名称候选、system_server、普通用户 0 应用各一个，去重；不存在的角色跳过。发现快照可能包含全机可见进程，但只为至多 4 个代表读取资源，不持久化完整列表。
- AIDL 新增无参数 `resources()`，不接受命令、路径或 PID。资源读取仅允许服务内部选出的正整数 PID 的 `/proc/<PID>/stat`、`status`，使用只读打开、禁止末级符号链接、每文件上限 16 KiB；无任意 Shell 扩展。
- 进程名称/PID/发现 UID 来自 ps；`stat` 提供 comm、PPID、utime、stime、num_threads 和用于身份比较的 starttime；`status` 提供真实 UID 和 VmRSS。每项显示来源、实际值或不可用原因。comm 可能截断，名称不证明包归属。部分指标可读取不代表全部可读取。
- 两轮间等待约 3 秒，按实际每进程观察间隔计算 CPU。每轮用两次 stat 夹住 status，发现 starttime 改变时拒绝身份关联；两轮需 PID、真实 UID、starttime 一致、原始计数有效且未倒退、实际间隔正值、sysconf 时钟频率有效。首样 CPU 为不可计算，无虚假 0%。
- CPU = `(Δutime + Δstime) / _SC_CLK_TCK / 实际秒数 × 100%`。100% 为一个逻辑核，多线程可超过 100%。实际时间使用 CLOCK_BOOTTIME / elapsedRealtimeNanos，包含可能的休眠；未假设 Hz=100。内核计数粒度、顺序读延迟和休眠会影响短区间值，不作为精密性能基准。
- RSS 明确使用 VmRSS 的 kB（KiB），不是 stat 页数、PSS、独占内存或泄漏判定。若内核线程无 VmRSS、SELinux/procfs 拒绝、进程退出、格式不支持，保留明确不可用原因，不补零。跨文件身份无法确认时，即使读到数值也标注未验证。
- 结果只在页面内存。离开页面使会话失效并解绑该独立服务，拒收迟到结果；IPC 有超时与有界执行通道，超时不宣称 Binder 底层调用已强制取消。授权或服务代次变化后拒收结果。不会自动重新采样。
- 本地导出前需用户确认并选择文件位置；仅 JSON，不附加历史数据库。移除名称、PID、UID、PPID、starttime，使用 PROCESS_N；移除绝对单调时间，日期取整到分钟，保留相对间隔、资源计数、来源和失败原因。数值仍可能具有敏感性，不自动上传。请在系统文件选择器中选本地存储。

核心源码：[ResourceDiagnostics.kt](../app/src/main/java/cn/onan/sentinel/ResourceDiagnostics.kt)、[ResourceDiagnosticActivity.kt](../app/src/main/java/cn/onan/sentinel/ResourceDiagnosticActivity.kt)、[ReadOnlyUserService.kt](../app/src/main/java/cn/onan/sentinel/ReadOnlyUserService.kt)。不读取短信、通信内容、音频、cmdline、environ、maps、smaps 或进程内存；无新增权限、Root、VPNService、系统设置修改、网络上传、PCAPdroid 或安全规则。

## 实际执行结果

停止工作前已经启动的一次构建最终完成；恢复后复用其 APK 与 XML，没有再次运行 Gradle。

| 验证 | 结果 | 证据与限制 |
| --- | --- | --- |
| ResourceDiagnosticsTest | **18 通过，0 失败/错误/跳过** | JUnit/Robolectric API 34，构造 proc 输入及时间，不是 K40S 输出 |
| ShizukuBridgeTest | **4 通过，0 失败/错误/跳过** | 假 Binder/授权回调，覆盖受 AIDL 变更影响的现有连接逻辑 |
| assembleDebug | **成功，一次构建，1m49s** | JDK17 / Gradle8.9；编译警告为既有 deprecated API，不影响构建 |
| APK 签名/身份 | apksigner verify 成功，versionCode302 | 与 PR #3 附件证书对比；没有读取手机已装 APK |
| diff 检查 | git diff --check 通过 | 未修改数据库实现和迁移代码 |
| 全套历史测试 / Lint / 48截图矩阵 | **本轮未执行** | 按限定工作量仅跑相关测试和一次必要构建 |
| 模拟器 / K40S / 真实权限断线 / SAF 导出 | **本轮未执行，待实机验证** | 不把 Robolectric 页面测试当成 Android 实际运行截图 |
| 24h / 耗电 / 手机历史逐条比对 | **本轮未执行** | 手动能力验证不产生长期性能结论 |

原始逐项名称与结果位于 [reports/](../artifacts/resource-diagnostic/reports/)。18 项新测试涵盖：stat 含空格/括号与字段偏移；截断/负计数/错误 PID；RSS 来源与单位；缺字段、单位错误、重复行及真实零；CPU 首样；实际间隔差值；真实零和多核百分比；PID 复用/UID 改变；无效间隔/Hz/计数倒退；权限拒绝；跨文件身份未验证；单轮内 PID 复用；代表数量和去重；两轮上限（最多 24 次文件读及一次等待）；导出脱敏与失败原因；失败结果无伪造样本；固定文件/命令边界；手动页面且不创建历史数据库。

实际 Gradle 任务（工作区包装器只配置工具链/网络，不改源码）：

```sh
./gradlew --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest \
  --tests cn.onan.sentinel.ResourceDiagnosticsTest \
  --tests cn.onan.sentinel.ShizukuBridgeTest \
  :app:assembleDebug
```

源码构建所需 SDK/JDK 和签名准备见 [README 构建说明](../README.md#构建)。签名文件是本地先决条件，公开源码不含私钥；新生成的测试密钥仅限隔离测试设备。本轮没有运行 README 中基线的全量命令。

## Redmi K40S 操作与验收标准（均待实机）

1. 保留已有 preview 和历史数据，正常覆盖安装本 APK；签名冲突即停，不卸载。升级后查看原历史记录仍可查询。本轮诊断无写库，后台监控已有保留策略仍可能正常淘汰历史。
2. Shizuku 使用 ADB / 无线调试运行并授权哨兵。打开「总览 → 资源能力诊断」；进入页面时应显示尚未诊断，不自动读取资源。可在监控暂停状态测试，无需开启后台监控。
3. 点「手动开始两次采样」。约 3 秒采样间隔外还可能有服务连接等待；最多 4 个代表。逐项核对名称/PID/UID/PPID、原始 CPU ticks、RSS KiB、线程数的来源与状态。任何不可读项应展示原因；不能以“完成两轮”推断全指标成功。
4. 第一轮 CPU 必须不可计算；第二轮只有同一身份的两份有效计数才能出现百分比。真正不变的有效计数可为 0%；缺读、身份变化、权限拒绝不能显示虚假 0%。system_server 或关注组件被限制时，应留下失败证据，不改权限。
5. 再次诊断需手动点击。采集中返回其他页面，不能自动重启采样；重新进入后须手动开始。单独停止 Shizuku/撤权后测试，应明确不可用或授权变化，不接受迟到的成功数据；此操作会使原监控出现真实空白期，不会清库。
6. 完成或明确失败后点「导出脱敏诊断 JSON…」，确认后选择本地文件。检查无真实名称、PID/UID/PPID、启动计数和绝对单调时间；资源指标、来源、失败原因仍在。取消不应写出文件。只需将用户愿意提供的脱敏报告用于后续能力判断。

## 已知限制与未实现

HyperOS 的 SELinux、procfs 限制和可见进程范围尚无目标机证据；不能保证能读 system_server 或其他应用全部字段。多数指标来自顺序读取，并非原子快照；starttime 检查减少 PID 复用误配，但不能提供绝对身份保证。部分进程名称来自截断 comm，代表角色基于名称/UID 启发式而非完整包归属认证。

PSS、全机持续资源监控、图表、资源历史入库、抓包、安全规则和第二阶段未实现。本轮没有实际运行截图、SAF 实机操作或新版安装验收；已有 PR #3 截图只属于基线。仍保持 Draft，等待用户安装后完成有限实机能力验证。
