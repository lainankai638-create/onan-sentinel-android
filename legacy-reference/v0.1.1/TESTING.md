# 构建与验证记录

## 验证环境

- Linux x86_64，Amazon Corretto JDK 17，Gradle 8.9，Android Gradle Plugin 8.7.3。
- Android SDK Platform 35。APK 最低 API 29，目标 API 34。
- 构建任务为 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`。安装包使用 Android 调试签名。

## 已验证项目

| 检查 | 结果 |
|---|---|
| Kotlin、AIDL、资源编译及 APK 打包 | 成功 |
| JUnit | 5 项通过，0 失败、0 跳过 |
| PCAPdroid CSV | 测试样本匹配实际导出表头。筛选包名与 UID，核对上下行字节和主机名，拒绝不兼容表头 |
| 经典 PCAP | 测试样本解析源端点、目的端点、端口与帧字节，归属保持推测，拒绝截断输入 |
| GPT 报告脱敏 | 测试样本核对默认脱敏隐藏 IP、主机名与其他包名 |
| Android Lint | 0 errors, 3 warnings |
| APK 签名与对齐 | 通过 `apksigner verify` 和 `zipalign -c` |
| Shizuku 接收入口 | 以最终 APK 检查 provider 类、authority、启用和导出状态及调用权限 |
| APK manifest | 包名 `cn.onan.sentinel`，版本 `0.1.1`，包含启动 Activity；没有 `INTERNET` 权限 |

## Redmi K40S 待实机验证

构建环境的 `adb devices -l` 未发现连接设备，以上结果来自构建、静态检查与 JVM 测试。

| 操作 | 预期观察 |
|---|---|
| 下载 APK，用手机安装并打开 | 五个主页面可打开，两个内置档案分别显示安装版本与 UID 或无法获取 |
| 保持快连运行，授权使用情况访问并采样 | 快连保持运行。流量样本显示上下行或缺桶、权限受限 |
| 通过 Shizuku 授权，然后打开目标应用，记录进程快照 | 显示匹配的进程或权限受限。用时间点核对快照结果 |
| 快连运行时请求抓包 | 可见活动 VPN 时请求被拦住 |
| 自行停止快连后对每个系统组件分别请求抓包 | 核对 PCAPdroid 所示筛选对象、抓包状态、UID 归属和系统组件是否有可见连接。按设备实际表现记录 |
| 在两轮同类扫描实验前后标记时间，导入两份 CSV | 可选时间范围，在报告中分会话对照记录。关联只作为线索 |
| 保存 Markdown、JSON 和附抓包的 ZIP | 系统文件选择器写入所选位置。脱敏报告只替换文本中的地址和其他包名，ZIP 内原文件保持原字节 |
| 停止 PCAPdroid，手动恢复快连 | 快连可以重新使用；具体表现需在该系统版本验证 |

APK SHA-256

`868fa738ce60240ce87f0693e787d1296022402e0c38875ac89a7dfcb2c592f2`
