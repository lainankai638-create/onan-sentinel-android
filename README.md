# 欧南·系统网络哨兵 v0.2.0

这是基于用户提供的 v0.1.1 仓库、面向长期监测重构的 **v0.2.0 隔离测试版**，不是已经验证的原应用升级包。

| 标识 | 本工程 |
| --- | --- |
| Kotlin namespace | `cn.onan.sentinel` |
| APK applicationId | `cn.onan.sentinel.preview` |
| versionName / code | `0.2.0-preview` / `200` |
| 最低 / 目标 / 编译 SDK | 26 / 34 / 35 |
| 目标验收设备 | Redmi K40S，Android 14，HyperOS 1.0.15.0.ULMCNXM |
| 签名 | 新的测试签名；从未使用或修改旧签名 |
| 数据库 | 测试包私有目录中的 `sentinel_v020.db`，schema 1 |

## 旧工程检索结果

最初本地及包名搜索未找到工程。用户随后提供公开仓库，现已找回并检查 [lainankai638-create/onan-sentinel-android](https://github.com/lainankai638-create/onan-sentinel-android) 的 `main`，基线提交 `c5b14b5bb2d754015ce087a5cd18fbef3dfd8f91`，版本 `0.1.1` / code `2`。源码、Gradle、Wrapper、Shizuku UserService、SQLite DDL、5 项旧单元测试及历史 `TESTING.md` 均已取得。完整原始快照保存在 `legacy-reference/v0.1.1/`，不参与本次 APK 编译；其中旧安装说明不适用于本次交付。

**仍缺失**：v0.1.0 独立源码、旧 APK 实物、可用于更新现装版本的签名密钥/证书指纹、真实数据库样本及原始构建日志。旧版 README 明确声明 v0.1.0 调试密钥未保留，v0.1.1 使用过新的调试签名。历史 TESTING.md 的构建成功和 APK hash 仅作为既有记录，不能当成本次重测结果。没有覆盖旧应用、卸载旧包、清理旧数据库、重新签署旧 APK，也没有对真实手机执行安装。

旧库为 `evidence.db` schema 1。本版新增独立 `sentinel_v020.db`，保留旧库不迁移、不修剪；提供只读结构/行数检查。隔离包不能跨 Android 沙箱读取旧包数据。使用旧 DDL 的合成样本测试只能证明结构兼容与保留策略，不能证明现装旧版升级已通过。旧抓包/CSV/PCAP/附原始文件导出和 JobScheduler 页面被留在参考快照，本次 APK 聚焦用户要求的长期只读监测，不暴露这些入口。

正式使用 `cn.onan.sentinel` 前，必须取得旧代码/数据库结构和原签名，编写明确迁移并在备份副本上验收。不要直接修改 applicationId 后安装，也不要为解决签名冲突卸载旧版。

## 已实现的边界

- 用户手动启动的 Android 前台服务，持续发布运行通知，可暂停、恢复、停止；没有开机自启、隐藏后台启动或系统限制绕过。
- Shizuku Binder 回调与运行时授权检查；仅接受 ADB shell UID 2000，拒绝 root 模式。
- Shizuku UserService 只接收两个枚举整数。固定只读命令为 `ps -A -w -o UID,PID,NAME` 和 `cmd appops get --user 0 <固定目标>`，不经过 `sh -c`。进程结果按数值 UID 过滤主用户，其他用户/第二空间不会归入 user 0。
- 固定目标：`com.miui.securitycenter`、`com.miui.guardprovider`、`com.miui.powerkeeper`。新增目标需要修改源码白名单并重编译；UI 不提供任意命令或任意包名输入。
- 默认 5 分钟采样，可选择 1 / 5 / 15 分钟。每 15 秒检查连接；暂停时不采样。单命令超时 4 秒，Binder 等待上限 7 秒，输出上限 64 KiB。用户暂停/停止后不保存在途结果。
- 进程样本只保存匹配目标的 PID。`NOT_OBSERVED_IN_PS` 仅表示该次可见列表未出现，不能证明进程绝对不存在。PID 变化是观察到的变化，不等于精确进程启动时间或恶意活动。
- AppOps 仅解析可识别的操作模式，明确带前缀的行标为 `UID`，其余行标为 `UNSCOPED`，不猜测包级/UID 归属，状态标为 `PARTIAL`。支持 `No operations.` 后附 `Default mode: allow` 等已知默认模式，但不推断操作名或访问行为。不保存归因标签、原始命令输出或相对访问时间；因此只能比较模式变化，不能还原完整调用历史、读取内容或判断数据外传。
- SQLite 保存墙钟时间、单调时间、目标、事件类型、来源、状态和证据；支持异常、启动/观察时间线、证据筛选和 JSONL 导出。
- 数据库主文件上限 32 MiB、最多 20,000 条，约 24 MiB 使用量或行数到阈值时删除较旧记录；回滚日志可能临时占额外空间。清理必须停止监控并手动确认；不会操作旧包数据。
- 无 INTERNET、音频、短信、通话、位置、外部存储读取权限；不自动上传、不接入分析 SDK、不抓包、不注入、不拦截 TLS。

## 空白期与真实性

`GAP_BEGIN` / `GAP_END` 表示采集不可用区间。连接丢失、授权缺失、暂停、停止、调度延迟、全部证据源失败均有状态记录。各目标/证据源独立失败也保留不可用样本。恢复后重新建立基线，不跨空白期伪称连续变化。

本进程被系统直接杀死时无法在死亡瞬间写入数据库。下次打开时，若发现未关闭会话，记录 `UNCLEAN_STOP_DETECTED`，以最后持久化采样时间作为空白期起点的保守下界，并明确实际停止时刻未知。尚未重新打开时数据库仍可能保留未关闭会话，不能解释为还在运行。

没有持有长期唤醒锁。Doze、HyperOS 保活策略、用户强制停止和无线调试关闭均可能中断采样。前台服务不保证 24 小时连续运行；Android 14 用户也可以关闭/隐藏通知。通知被禁用后检查到即停止。若磁盘/SQLite 写入失败，停止采集并通过运行状态/通知显示错误，不静默重建库。

## 构建

环境：完整 JDK 17、Android SDK platform 35、Build Tools 34.0.0、Gradle 8.9（Wrapper）、AGP 8.7.3、Kotlin 2.0.21、Shizuku API/provider 13.1.5。首次构建需要访问 Google Maven、Maven Central 和 Gradle 分发站点。

1. 安装 Android Studio 或 Android command-line tools，并接受 SDK 许可：

   ```sh
   sdkmanager "platforms;android-35" "build-tools;34.0.0" "platform-tools"
   ```

2. 通过 `ANDROID_HOME` 指向 SDK，或在本地创建 `local.properties`（不包含在交付中）：

   ```properties
   sdk.dir=/your/path/to/Android/sdk
   ```

3. 仅第一次为隔离测试包生成本地测试密钥，后续构建保留此文件。不要使用原版密钥：

   ```sh
   mkdir -p .local-signing
   keytool -genkeypair -keystore .local-signing/debug.keystore -storepass android -keypass android -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 3650
   ```

4. 构建和检查：

   ```sh
   ./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
   ```

   Windows 使用 `gradlew.bat`。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`；单元测试 HTML 位于 `app/build/reports/tests/testDebugUnitTest/index.html`。Release 不配置任何签名，不能当作原版升级包。

测试代码中的 Robolectric 使用 Android 14 框架与 native SQLite；这是主机测试，不是实机运行。`StoreInstrumentedTest` 会清理 **preview 包** 的测试数据，仅在专用测试设备/模拟器运行。

## 手机上使用

1. 保留 v0.1.0。安装隔离 APK，确认包名以 `.preview` 结尾。
2. 在 Shizuku 中用无线调试启动服务；不要用 root 模式。
3. 打开“欧南·网络哨兵（隔离测试）”，点击授权 Shizuku，再允许通知，再点击启动。
4. 查看来源状态及证据。某组件不存在、命令格式不同或权限受限时，应显示失败原因，不应补造结果。
5. 导出通过系统文档选择器由用户选择目的地。报告包含分钟级时间、目标别名、操作模式和空白期；删除 PID 和精确时间，但不保证完全匿名。选择本地目录可避免用户所选云文档提供商自行同步。
6. 停止并确认后可清理本测试包历史。自动保留策略也会删除旧记录，需要长期留存时手动导出。

详见 `docs/ARCHITECTURE.md`、`docs/ANDROID14_FORMAT_REVIEW.md`、`docs/DEVICE_ACCEPTANCE.md` 和交付测试报告。格式回归样本来自 Android 14 AOSP 输出逻辑，不是 HyperOS 实机采集。不能将主机测试或模拟时间跨度当作 24 小时实机验收。
