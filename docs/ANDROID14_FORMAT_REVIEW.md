# Android 14 采样格式复查

日期：2026-11-18（Asia/Shanghai）。范围仅为 `Policy.kt` 解析器及格式回归测试，不新增命令、不调整数据库或前台服务。

## 依据与样本性质

- [AOSP AppOpsService.java，android-14.0.0_r1](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/appop/AppOpsService.java)，`case "get"` 第 4648–4744 行。
- [AOSP toybox ps.c，同一标签](https://android.googlesource.com/platform/external/toybox/+/android-14.0.0_r1/toys/posix/ps.c)，`UID` / `USER` / `NAME` 字段定义及 `ps_main`。
- 下载源码 SHA-256：AppOpsService `e05663598a91816495e548270d349fc4e504525fc37449b6d0311776da36ba69`；ps.c `90e441ddcaba0366ed4ca04d21f91ea6a602c5175271b61d36f39430587ca983`。

`app/src/test/resources/android14/` 是按上述真实源码输出路径构造的合成样本，**不是 Redmi K40S 或 HyperOS 的实机采集记录**。未取得厂商对应版本的命令输出，不宣称厂商兼容性已通过。

## AppOps

当没有操作记录时，AOSP 输出 `No operations.`；若命令还指定了单个操作，则追加 `Default mode: <mode>`。因此原解析器只比较整段文本是否等于 `No operations.` 会误报这条合法双行响应为不支持。

当前白名单使用 `cmd appops get --user 0 <固定包名>`，没有单个操作参数。在 AOSP 的此调用路径通常只有空记录一行；不能将双行样本描述为当前命令的实机复现。本次防御性支持双行格式，保存 `NO_RECORDED_OPERATIONS` 和 `REPORTED_DEFAULT_MODE=<mode>`，状态为 PARTIAL。文本没有操作名，不能将默认模式套用于全部 AppOps，也不能解释成发生过访问。未知模式、矛盾操作行、多余默认行或警告不会被空记录标记掩盖；拒绝/包不存在错误仍优先。

AOSP 只在 UID 记录组的首行打印 `Uid mode:`，其余 UID 行与随后包级行没有明确分隔。原解析器把所有无前缀行标为 PACKAGE 不可靠。本次仅将明确前缀行标为 UID，其余模式标为 UNSCOPED；不猜测有效权限或 UID 与包级覆盖关系。时间、拒绝时间、持续时间、running 标记及归因文本不进入证据；只比较可识别模式。旧历史记录保持原样，旧 PACKAGE 标记应视为当时解析器的标签，不能作为确认包级作用域的依据。

## 进程列表

白名单显式指定 `UID,PID,NAME` 和 `-w`。AOSP 的 UID 是数字，USER 是用户名，NAME 来自 argv[0]，不同于可能截断的线程名 CMD。不能把厂商默认的 USER/PID/.../NAME 布局当作本命令的布局。

名称字段可包含空格。按所有空白拆列会使一个无关进程名称导致整批格式失败；现在只拆前两个数字字段，保留第三字段完整名称，继续按包名精确匹配或 `包名:` 子进程匹配。主用户 UID 过滤不变。对齐空格、CRLF、第二空间 UID、名称前缀冲突均有测试。

USER/CMD 等不同表头、数字列变成用户名、缺列、命令错误仍报告不支持；不猜测 PID/UID。名称截断不能可靠还原，未精确匹配只报告 `NOT_OBSERVED_IN_PS`，不声称目标进程不存在。若 HyperOS 不支持显式字段或受 SELinux 可见性限制，应保留不可用状态，不能自动改用更高权限或其他命令。

## 待实机验收

在目标机核对既有两条白名单命令的输出表头、数字 UID、完整 NAME、空 AppOps、UID 前缀、多操作模式、拒绝和目标不存在场景。只读观察，不为制造测试数据执行 `appops set/reset` 或停止系统安全组件。参照 `DEVICE_ACCEPTANCE.md`，分享前脱敏；未经授权不上传原始进程列表。当前仍没有实机格式、24h、耗电或断线验收结果。
