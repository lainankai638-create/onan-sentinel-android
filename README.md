# 欧南·系统网络哨兵 0.1.1

独立 Android 应用。面向 Redmi K40S、Android 14 和澎湃 OS 1.0.15.0.ULMCNXM。内置两个档案，自动读取设备上的实际版本。

| 档案 | 包名 | 提供的目标版本 |
|---|---|---|
| 手机管家 | `com.miui.securitycenter` | `10.5.2-260718.0.1` |
| 系统安全组件 | `com.miui.guardprovider` | `2.1.5-20240906.0` |

## 0.1.1 修复

0.1.0 的安装包缺少 Shizuku Binder 接收入口。本版已补上受系统权限保护的 ShizukuProvider，并在总览显示收到连接、等待授权及只读服务连接等不同状态。

如果手机已经安装 0.1.0，先导出想保留的记录，再卸载旧版并安装新版。旧版的调试签名密钥未保留，本版使用新的调试签名，无法覆盖安装。卸载会清除旧版的本地数据库，已导出到手机文件夹的报告可继续查看。

## 手机上怎么用

1. 打开 APK 安装并启动。安装器如提示，请按手机上的安装来源提示操作。APK 使用调试签名，用于这一版本的手机试装。
2. 保持快连运行。在总览打开「使用情况访问设置」，授权本应用。切到「组件」，打开手机管家或系统安全组件，点「立即记录流量快照」。总览可预约约每 15 分钟的后台 UID 流量采样，执行时间由 Android 调度。停止采样可在总览操作。
3. 在 Shizuku 中确认服务已启动，返回总览选择「连接 Shizuku」。打开目标组件详情，点「记录后台进程快照」。在执行测试安装、更新或安全扫描时，也可以用「标记刚发生的事件」记录时间。
4. 深度抓包时，自行停止快连，进入对应档案的「针对该组件分析」，确认后请求 PCAPdroid 聚焦抓包。用「查询 PCAPdroid 抓包状态」检查，并在 PCAPdroid 确认筛选的目标。采集结束后，在 PCAPdroid 停止抓包，从连接页面导出 CSV，再回哨兵选择「导入 PCAPdroid 连接 CSV」。日常模式无需切换 VPN。
5. 若已有经典 PCAP，可在对应档案中导入。建议对照同会话的 PCAPdroid CSV 来判断连接的包或 UID。离线 PCAP 会显示源和目的端点、端口、协议、包数与帧字节。经典 PCAP 的方向是包里的源到目的，设备侧上下行、远端、所属应用都需要另找证据。
6. 到「报告」选组件和时间段，预览脱敏结果后保存 Markdown 或 JSON。需要附带原始抓包时，预览对话框选「其他导出」，再选 PCAP 或 PCAPNG，保存包含报告和原始文件的 ZIP。保存时可选择手机本地目录。原始文件会保留抓包内容，报告脱敏不会修改附件。
7. 自行选定要交给 GPT 的文件。界面可调整保留期限或清除 SQLite 中全部记录。已经保存到外部位置的文件需要在原位置管理。

## 权限及兼容性

| 接口或权限 | 用途与边界 |
|---|---|
| 使用情况访问 | `NetworkStatsManager` 查询指定 UID 的 Wi-Fi、移动网络当天累计字节。系统统计可能延迟、分桶粗、无可用数据或受 VPN 记账影响。同 UID 多包只提供一份聚合值。 |
| 网络状态 | 观察系统向本应用可见的 VPN 网络。发现活动 VPN 会拦住启动抓包；请求前仍须确认，因为网络可见性和同时切换存在限制。 |
| Shizuku | 用公开 UserService 执行固定的只读 `ps`。仅回传匹配目标包名前缀的进程行；进程快照只覆盖采样当时。MIUI 的 shell 输出和 Shizuku 状态可能影响结果。 |
| PackageManager | 查看可见包的版本、UID、最近安装更新、声明权限及授权标志。默认两包通过 manifest 声明可见。自定义包可能不可见，且同 UID 可见包列表可能不完整。 |
| PCAPdroid | 使用公开 Activity 接口请求本地 VPN 抓包及查询状态，指定 `app_filter`，关闭 TLS 解密和自动阻止私有 DNS。实际抓包范围以 PCAPdroid 和系统行为为准。 |
| 系统文件选择器 | 仅访问用户选定的输入文件和输出位置。应用本身未声明互联网权限。 |
| 设备重启和强制停止 | 后台定时任务应重新开启。系统省电策略可延后任务。流量快照与进程快照使用各自采集来源。 |

UID 统计只适合在同一天内比较连续快照。Wi-Fi 和移动网络分别记录，缺桶显示为无可用数据。CSV 会话按连接首次观测时间筛选，整条连接的计数可能跨出区间。导入同一文件两次会得到两个会话。普通 PCAP 流以有方向的 IP/端口/协议组合归并，同一组合重复使用也会归在一起。IPv6 扩展头之后的端口当前不解析。CSV 主机名来自 PCAPdroid 的 Info，来源可能是 DNS、TLS 或其他可见元数据。域名缺失和共享 UID 均不代表没有联网。

## 功能状态

| 状态 | 功能 |
|---|---|
| 已实现 | 五个页面、独立档案、定时及手动 UID 流量快照、手动 Shizuku 进程快照、手动事件标记、按组件和时间筛选 |
| 已实现 | 发起 PCAPdroid 聚焦抓包、查询状态、离线导入 PCAPdroid CSV 和经典 PCAP、会话归档、最近记录 |
| 已实现 | Markdown、JSON、含原始抓包的 ZIP，预览、默认端点脱敏、本地保留和删除 |
| 已实现 | 从同一 CSV 会话寻找重复间隔与连接开始时段的峰值线索，证据按已确认、推测、无法获取展示 |
| 未实现 | 进程启动、停止与服务活动的连续自动记录，系统扫描自动识别，已验证的反诈功能识别 |
| 未实现 | PCAPNG 离线解析、原始 PCAP UID/应用扩展解析、哨兵内主动 TLS SNI/DNS 解析、CSV 格式报告输出 |
| 待实机验证 | 小米两包的 UID 归属及真实包量、澎湃系统对定时任务和 Shizuku 的限制、PCAPdroid 实装版本的接口及停抓后的快连恢复 |

比较两个时间段时，分别选择区间导出报告，再按 CSV 会话统计对照。保留日期、时区和采样覆盖，避免把整条连接或 UID 累计值当作精确的区间字节。

## 构建

需要完整 JDK 17 或 21、Android SDK Platform 35、Build Tools 34.0.0 和 Gradle Wrapper。设置 `ANDROID_HOME`，然后运行：

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

APK 输出 `app/build/outputs/apk/debug/app-debug.apk`。构建与验证记录见 `TESTING.md`。

## 参考

- [Android NetworkStatsManager](https://developer.android.com/reference/android/app/usage/NetworkStatsManager)
- [Android VpnService.Builder](https://developer.android.com/reference/android/net/VpnService.Builder)
- [Shizuku API 开发指南](https://github.com/RikkaApps/Shizuku-API)
- [PCAPdroid 调用接口](https://github.com/emanuele-f/PCAPdroid/blob/master/docs/app_api.md)
- [PCAPdroid CSV 实现](https://github.com/emanuele-f/PCAPdroid/blob/master/app/src/main/java/com/emanuelef/remote_capture/adapters/ConnectionsAdapter.java)
