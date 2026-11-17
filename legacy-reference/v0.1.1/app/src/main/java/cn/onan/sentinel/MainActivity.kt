package cn.onan.sentinel

import android.app.Activity
import android.app.AlertDialog
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import rikka.shizuku.Shizuku
import org.json.JSONObject
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {
    private lateinit var body: LinearLayout
    private var page = 0
    private var target = SECURITY
    private var start = midnight()
    private var end: Long? = null
    private var shell: IReadOnlyShell? = null
    private var bound = false
    private var shizukuNote: String? = null
    private val connectionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val connectionTimeout: Runnable = Runnable {
        if (bound && shell == null) {
            runCatching { Shizuku.unbindUserService(shellArgs, shellConnection, false) }
            bound = false
            shizukuNote = "已授权，但只读服务连接超时。可以重新连接。"
            if (!isDestroyed) render()
        }
    }
    private var importTarget: String? = null
    private var exportText: String? = null
    private var attachment: Uri? = null
    private var status = ""
    private val shellArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, ReadOnlyShell::class.java))
            .processNameSuffix("readonly").daemon(false).version(1)
    }
    private val shellConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (isDestroyed || !bound) return
            connectionHandler.removeCallbacks(connectionTimeout)
            shizukuNote = null
            shell = IReadOnlyShell.Stub.asInterface(binder)
            render()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            if (isDestroyed) return
            connectionHandler.removeCallbacks(connectionTimeout)
            shizukuNote = "只读服务已断开，可以重新连接。"
            shell = null
            bound = false
            render()
        }
    }
    private val binderReady = Shizuku.OnBinderReceivedListener {
        if (!isDestroyed) {
            shizukuNote = null
            if (authorized()) connectShell()
            render()
        }
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        connectionHandler.removeCallbacks(connectionTimeout)
        shizukuNote = "与 Shizuku 的连接已断开。"
        shell = null
        bound = false
        if (!isDestroyed) render()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { request, result ->
        if (request == 30 && !isDestroyed) {
            if (result == PackageManager.PERMISSION_GRANTED) connectShell()
            else shizukuNote = "Shizuku 授权未通过，请检查它的授权应用列表。"
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = savedInstanceState?.getString("target") ?: SECURITY
        page = savedInstanceState?.getInt("page") ?: 0
        start = savedInstanceState?.getLong("start") ?: midnight()
        end = savedInstanceState?.getLong("end")?.takeIf { it != -1L }
        importTarget = savedInstanceState?.getString("importTarget")
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        Shizuku.addBinderReceivedListenerSticky(binderReady)
        render()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("target", target)
        out.putInt("page", page)
        out.putLong("start", start)
        out.putLong("end", end ?: -1)
        out.putString("importTarget", importTarget)
    }
    override fun onResume() {
        super.onResume()
        if (Shizuku.pingBinder() && authorized()) connectShell()
        render()
    }

    override fun onDestroy() {
        connectionHandler.removeCallbacks(connectionTimeout)
        Shizuku.removeBinderReceivedListener(binderReady)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        if (bound) runCatching { Shizuku.unbindUserService(shellArgs, shellConnection, true) }
        super.onDestroy()
    }

    private fun authorized() = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun connectShell() {
        if (bound || !Shizuku.pingBinder() || !authorized()) return
        bound = true
        shizukuNote = null
        connectionHandler.postDelayed(connectionTimeout, 10_000)
        runCatching { Shizuku.bindUserService(shellArgs, shellConnection) }.onFailure {
            connectionHandler.removeCallbacks(connectionTimeout)
            bound = false
            shizukuNote = "只读服务连接失败（${it.javaClass.simpleName}）。"
        }
    }

    private fun work(task: () -> String) {
        worker.execute {
            val result = try { task() } catch (e: Exception) {
                "操作未完成：${e.message?.take(180) ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                if (!isDestroyed) {
                    status = result
                    render()
                }
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 16f, dark: Boolean = true): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.parseColor(if (dark) "#153D32" else "#FFFFFF"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setTextIsSelectable(dark)
        }

    private fun action(title: String, block: () -> Unit) {
        body.addView(Button(this).apply {
            text = title
            isAllCaps = false
            setOnClickListener { block() }
        })
    }

    private fun paragraph(value: String, size: Float = 16f) { body.addView(text(value, size)) }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F1F5F2"))
        }
        root.addView(text("欧南·系统网络哨兵", 24f, false).apply {
            setBackgroundColor(Color.parseColor("#153D32"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        })
        val tabs = LinearLayout(this)
        listOf("总览", "组件", "详情", "抓包", "报告").forEachIndexed { index, name ->
            tabs.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = name
                textSize = 14f
                isSelected = index == page
                setTextColor(Color.parseColor("#153D32"))
                if (index == page) setBackgroundColor(Color.parseColor("#C8E4D8"))
                setPadding(0, 0, 0, 0)
                setOnClickListener { page = index; status = ""; render() }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        root.addView(tabs)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(16))
        }
        root.addView(ScrollView(this).apply { addView(body) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        if (status.isNotBlank()) paragraph(status)
        when (page) {
            0 -> overview()
            1 -> components()
            2 -> details()
            3 -> capture()
            4 -> reports()
        }
    }

    private fun overview() {
        val scheduler = getSystemService(JobScheduler::class.java)
        val running = scheduler.getPendingJob(100) != null
        val state = when {
            shell != null -> "已连接"
            bound -> "正在连接只读服务"
            !Shizuku.pingBinder() -> "哨兵尚未收到服务连接"
            authorized() -> "已授权，等待连接只读服务"
            else -> "已收到服务连接，等待授权"
        }
        paragraph("日常监测 · ${if (running) "已开启" else "已停止"}", 22f)
        paragraph("Shizuku $state\n使用情况访问 ${if (usageAllowed()) "已授权" else "权限受限"}\nVPN ${if (vpnVisible()) "已观察到活动 VPN" else "当前未观察到 VPN"}")
        if (!usageAllowed()) action("打开使用情况访问设置") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        shizukuNote?.let { paragraph(it, 14f) }
        if (shell == null && !bound) action("连接 Shizuku") {
            when {
                !Shizuku.pingBinder() -> {
                    val manager = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (manager == null) shizukuNote = "当前用户下找不到 Shizuku 管理器。"
                    else {
                        shizukuNote = "尚未收到服务连接。请检查 Shizuku 状态，再返回哨兵。"
                        startActivity(manager)
                    }
                }
                authorized() -> connectShell()
                else -> runCatching { Shizuku.requestPermission(30) }.onFailure {
                    shizukuNote = "无法请求 Shizuku 授权（${it.javaClass.simpleName}）。"
                }
            }
            render()
        }
        action(if (running) "停止日常监测" else "开启日常监测") {
            if (running) {
                scheduler.cancel(100)
                status = "日常监测已停止"
            } else {
                val job = JobInfo.Builder(100, ComponentName(this, SampleJob::class.java))
                    .setPeriodic(15 * 60_000L).build()
                status = if (scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS)
                    "已预约定时采样，系统可能延迟执行" else "系统拒绝安排采样"
            }
            render()
        }
        paragraph("今日流量 · 最近一次 UID 快照", 19f)
        val today = midnight()
        val now = System.currentTimeMillis()
        Store(this).use { db ->
            for (pkg in listOf(SECURITY, GUARD)) {
                val sample = db.rows(pkg, today, now, 1, "流量快照").firstOrNull()
                val summaries = listOf("Wi-Fi", "移动网络").map { medium ->
                    val counters = sample?.data?.optJSONObject(medium)
                    if (counters?.has("上传字节") == true)
                        "$medium ↑${counters.optLong("上传字节")} B ↓${counters.optLong("下载字节")} B"
                    else "$medium 暂无可用统计"
                }
                paragraph("${label(pkg)}\n${summaries.joinToString("\n")}", 14f)
            }
            signals(db.rows(target, today, now, 2000, "连接")).firstOrNull()?.let { paragraph(it, 14f) }
        }
    }

    private fun components() {
        paragraph("系统组件档案", 22f)
        targets().forEach { pkg ->
            action("${label(pkg)}\n$pkg") { target = pkg; page = 2; render() }
        }
        action("添加目标包名") {
            val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
            AlertDialog.Builder(this).setTitle("输入安卓包名").setView(input).setNegativeButton("取消", null)
                .setPositiveButton("添加") { _, _ ->
                    val value = input.text.toString().trim()
                    if (value.length > 200 || !PACKAGE_NAME.matches(value)) status = "请输入有效包名"
                    else if (value !in targets()) {
                        val extras = options().getStringSet("targets", emptySet()).orEmpty() + value
                        if (extras.size <= 20) options().edit().putStringSet("targets", extras).apply()
                        else status = "最多添加 20 个自定义包名"
                    }
                    render()
                }.show()
        }
        paragraph("自定义包名受 Android 包可见性限制。两个默认组件已登记可见。", 14f)
    }

    private fun pickTarget() {
        val options = targets()
        AlertDialog.Builder(this).setTitle("选择监测对象")
            .setItems(options.map(::label).toTypedArray()) { _, choice -> target = options[choice]; render() }.show()
    }

    private fun details() {
        val pkg = target
        val info = pkg(pkg)
        paragraph(label(pkg), 24f)
        paragraph("$pkg\n版本 ${info?.versionName ?: "无法获取"}\nUID ${info?.applicationInfo?.uid ?: "无法获取"}")
        info?.applicationInfo?.uid?.let {
            paragraph("同 UID 可见包 ${packageManager.getPackagesForUid(it).orEmpty().joinToString("、").ifBlank { "无法获取" }}", 14f)
        }
        info?.let {
            paragraph("最近安装 ${displayTime(it.firstInstallTime)}\n最近更新 ${displayTime(it.lastUpdateTime)}", 14f)
            val names = it.requestedPermissions.orEmpty()
            val flags = it.requestedPermissionsFlags ?: intArrayOf()
            action("查看声明权限及授权标志") {
                val list = names.mapIndexed { index, name ->
                    val grant = flags.getOrNull(index)?.and(android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED)
                    "$name · ${when (grant) { null -> "无法获取"; 0 -> "未授权"; else -> "已授权" }}"
                }
                AlertDialog.Builder(this).setTitle("组件权限").setMessage(list.joinToString("\n").ifBlank { "无可见权限信息" })
                    .setPositiveButton("关闭", null).show()
            }
        }
        action("立即记录流量快照") {
            work {
                Store(this).use { db -> sample(db, pkg, System.currentTimeMillis()) }
                "已保存流量快照及可用性"
            }
        }
        action("记录后台进程快照") {
            val service = shell
            work {
                val now = System.currentTimeMillis()
                val result = runCatching { requireNotNull(service) { "Shizuku 未连接" }.processes(pkg) }
                val data = JSONObject().put("结果", result.getOrNull()?.ifBlank { "未见包名前缀匹配进程" } ?: "权限受限或只读服务不可用")
                Store(this).use { it.add(Evidence(now, pkg, "进程快照", if (result.isSuccess) "已确认" else "无法获取", "Shizuku / ps", data)) }
                "已保存进程快照"
            }
        }
        action("标记刚发生的事件") {
            val events = arrayOf("安装应用", "更新应用", "启动安全扫描", "结束安全扫描", "打开组件界面")
            AlertDialog.Builder(this).setTitle("记录用户观察").setItems(events) { _, choice ->
                val time = System.currentTimeMillis()
                work {
                    Store(this).use { it.add(Evidence(time, pkg, "事件标记", "推测", "用户手动标记",
                        JSONObject().put("用户观察", events[choice]))) }
                    "已记录时间点"
                }
            }.show()
        }
        action("针对该组件分析") { page = 3; render() }
        rangeControl()
        recent(null)
    }

    private fun captureIntent(action: String) = Intent(Intent.ACTION_VIEW)
        .setClassName(PCAP, "$PCAP.activities.CaptureCtrl").putExtra("action", action)

    @Suppress("DEPRECATION")
    private fun requestCapture(intent: Intent, request: Int) {
        runCatching { startActivityForResult(intent, request) }.onFailure {
            status = "当前 PCAPdroid 版本未提供可调用的公开入口"
            render()
        }
    }

    private fun capture() {
        paragraph("深度抓包 · ${label(target)}", 22f)
        action("切换监测对象", ::pickTarget)
        paragraph("当前 ${if (vpnVisible()) "有可见 VPN" else "未观察到 VPN"}。共享 UID 或系统流量限制可能影响归属。")
        action("请求 PCAPdroid 聚焦抓包") {
            val selected = target
            if (pkg(selected) == null) {
                status = "当前目标包不可见，无法验证抓包筛选目标"
                render()
            } else AlertDialog.Builder(this).setTitle("使用 PCAPdroid 的 VPN 通道")
                .setMessage("Android 同一时间仅有一个活动 VPN。请先自行停止快连，再开始抓包。系统可能还会要求确认。")
                .setNegativeButton("取消", null).setPositiveButton("继续") { _, _ ->
                    if (vpnVisible()) {
                        status = "仍可见活动 VPN，抓包请求未发出"
                        render()
                    } else {
                        requestCapture(captureIntent("start").putExtra("app_filter", selected)
                            .putExtra("root_capture", false).putExtra("tls_decryption", false)
                            .putExtra("auto_block_private_dns", false).putExtra("socks5_enabled", false)
                            .putExtra("pcap_dump_mode", "none").putExtra("ip_mode", "both"), 41)
                    }
                }.show()
        }
        action("查询 PCAPdroid 抓包状态") { requestCapture(captureIntent("get_status"), 42) }
        action("打开 PCAPdroid 停止或导出抓包") {
            val launcher = packageManager.getLaunchIntentForPackage(PCAP)
            if (launcher != null) startActivity(launcher) else { status = "找不到 PCAPdroid"; render() }
        }
        paragraph("在 PCAPdroid 的连接页面导出 CSV，再导入查看远程地址、协议和上下行。经典 PCAP 只能提取包流。", 14f)
        action("导入 PCAPdroid 连接 CSV") { selectFile(43) }
        action("导入离线经典 PCAP") { selectFile(44) }
        rangeControl()
        recent("capture")
    }

    @Suppress("DEPRECATION")
    private fun selectFile(code: Int) {
        importTarget = target
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*"), code)
    }

    private fun reports() {
        paragraph("证据报告 · ${label(target)}", 22f)
        action("切换监测对象", ::pickTarget)
        rangeControl()
        val hide = CheckBox(this).apply { setText(R.string.redact_endpoints); isChecked = true }
        body.addView(hide)
        paragraph("时间、字节数和目标包名仍会导出。附带原始 PCAP 时，脱敏仅作用于报告。", 14f)
        action("预览并生成 GPT 分析报告") { reportPreview(hide.isChecked) }
        val days = options().getInt("retention", 14)
        action("本地数据保留 $days 天") {
            val choices = intArrayOf(1, 7, 14, 30, 90)
            AlertDialog.Builder(this).setTitle("设置保留期限")
                .setItems(choices.map { "$it 天" }.toTypedArray()) { _, index ->
                    options().edit().putInt("retention", choices[index]).apply()
                    work { Store(this).use { it.prune(choices[index]) }; "保留期限已更新" }
                }.show()
        }
        action("清除全部本地证据") {
            AlertDialog.Builder(this).setTitle("清除本应用记录？")
                .setMessage("已另存的文件及 PCAPdroid 数据需在原位置管理。")
                .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ ->
                    work { Store(this).use(Store::clear); "本地证据已清除" }
                }.show()
        }
        recent(null)
    }

    private fun rangeControl() {
        val from = displayTime(start)
        val to = end?.let(::displayTime) ?: "现在"
        paragraph("$from 至 $to", 13f)
        action("设置查看时间段") {
            val labels = arrayOf("今天", "过去 24 小时", "过去 7 天", "全部保留记录", "自定义")
            AlertDialog.Builder(this).setTitle("选择时间段").setItems(labels) { _, selection ->
                val now = System.currentTimeMillis()
                if (selection == 4) { customRange(); return@setItems }
                start = when (selection) { 0 -> midnight(); 1 -> now - DAY; 2 -> now - 7 * DAY; else -> 0 }
                end = null
                render()
            }.show()
        }
    }

    private fun customRange() {
        val formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT)
        val zone = ZoneId.systemDefault()
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val from = EditText(this).apply { hint = "开始 yyyy-MM-dd HH:mm" }
        val to = EditText(this).apply { hint = "结束 yyyy-MM-dd HH:mm" }
        panel.addView(from)
        panel.addView(to)
        val dialog = AlertDialog.Builder(this).setTitle("按手机本地时区填写").setView(panel)
            .setNegativeButton("取消", null).setPositiveButton("应用", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val times = runCatching {
                    listOf(from, to).map {
                        val time = LocalDateTime.parse(it.text.toString().trim(), formatter)
                        val offset = zone.rules.getValidOffsets(time).single()
                        time.toInstant(offset).toEpochMilli()
                    }
                }.getOrNull()
                if (times == null || times[0] > times[1]) {
                    to.error = "请检查时间格式和先后顺序"
                } else { start = times[0]; end = times[1]; dialog.dismiss(); render() }
            }
        }
        dialog.show()
    }

    private fun recent(filter: String?) {
        val rows = Store(this).use { db ->
            if (filter == "capture") listOf("连接", "数据包流", "导入会话").flatMap {
                db.rows(target, start, end ?: System.currentTimeMillis(), 20, it)
            }.sortedByDescending { it.time }.take(20)
            else db.rows(target, start, end ?: System.currentTimeMillis(), 20)
        }
        paragraph("最近证据", 19f)
        if (rows.isEmpty()) paragraph("当前时间范围暂无记录。", 14f)
        rows.forEach { row ->
            val data = row.data
            val fields = data.keys().asSequence().joinToString("\n") { "$it  ${data.opt(it)}" }
            paragraph("${displayTime(row.time)}\n${row.grade} · ${row.kind} · ${row.source}\n$fields", 14f)
        }
    }

    private fun reportPreview(hide: Boolean) {
        val selected = target
        val from = start
        val to = end ?: System.currentTimeMillis()
        worker.execute {
            try {
                val records = Store(this).use { it.rows(selected, from, to) }
                val identity = JSONObject()
                pkg(selected)?.let {
                    identity.put("UID", it.applicationInfo?.uid).put("版本", it.versionName)
                }
                val markdown = makeReport(selected, from, to, records, hide, false, identity)
                val json = makeReport(selected, from, to, records, hide, true, identity)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    val preview = ScrollView(this).apply { addView(text(markdown, 14f)) }
                    AlertDialog.Builder(this).setTitle("检查导出内容").setView(preview)
                        .setNegativeButton("其他导出") { _, _ -> attachmentDialog(markdown) }
                        .setNeutralButton("保存 JSON") { _, _ -> save(json, true) }
                        .setPositiveButton("保存 Markdown") { _, _ -> save(markdown, false) }.show()
                }
            } catch (e: Exception) {
                runOnUiThread { if (!isDestroyed) { status = "无法生成报告：${e.javaClass.simpleName}"; render() } }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun attachmentDialog(markdown: String) {
        AlertDialog.Builder(this).setTitle("附加原始抓包")
            .setMessage("原始抓包可能包含设备地址、域名及通信内容。ZIP 中的原文件将按原样保留。")
            .setNegativeButton("取消", null).setPositiveButton("选择原始抓包文件") { _, _ ->
                exportText = markdown
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 46)
            }.show()
    }

    @Suppress("DEPRECATION")
    private fun save(content: String, json: Boolean, raw: Uri? = null) {
        exportText = content
        attachment = raw
        val (type, extension) = when {
            raw != null -> "application/zip" to "zip"
            json -> "application/json" to "json"
            else -> "text/markdown" to "md"
        }
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(type).putExtra(Intent.EXTRA_TITLE, "onan-evidence.$extension"), 45)
    }

    @Deprecated("Uses the Android platform activity result callback")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        when (request) {
            41 -> status = if (result == RESULT_OK) "PCAPdroid 已接受抓包请求；可查询运行状态"
                else "PCAPdroid 未确认启动，请在 PCAPdroid 检查权限和状态"
            42 -> status = if (result == RESULT_OK && data?.hasExtra("running") == true)
                "PCAPdroid ${data.getStringExtra("version_name").orEmpty()} · ${if (data.getBooleanExtra("running", false)) "抓包中（对象以 PCAPdroid 显示为准）" else "未抓包"}"
                else "未取得 PCAPdroid 抓包状态"
            43, 44 -> {
                val selected = importTarget
                importTarget = null
                val uri = data?.data
                if (result == RESULT_OK && uri != null && selected != null) work {
                    val session = UUID.randomUUID().toString().take(12)
                    val uid = pkg(selected)?.applicationInfo?.uid
                    Store(this).use { db ->
                        val sqlite = db.writableDatabase
                        sqlite.beginTransaction()
                        try {
                            val input = contentResolver.openInputStream(uri) ?: error("无法打开文件")
                            val count = input.use {
                                if (request == 43) readConnections(CappedInput(it, 16L * 1024 * 1024), selected, uid, session, db::add)
                                else readPcap(it, selected, session, db::add)
                            }
                            val now = System.currentTimeMillis()
                            db.add(Evidence(now, selected, "导入会话", "已确认", "本地文件导入",
                                JSONObject().put("会话", session).put("格式", if (request == 43) "CSV" else "PCAP").put("导入条数", count)))
                            sqlite.setTransactionSuccessful()
                            "会话 $session 已导入 $count 条记录。记录按原始观测时间显示。"
                        } finally { sqlite.endTransaction() }
                    }
                }
            }
            45 -> {
                val content = exportText
                val raw = attachment
                exportText = null
                attachment = null
                val uri = data?.data
                if (result == RESULT_OK && uri != null && content != null) work {
                    if (raw != null) require(raw != uri) { "输出文件不能与原抓包相同" }
                    if (raw == null) {
                        val output = contentResolver.openOutputStream(uri, "wt") ?: error("无法打开导出文件")
                        output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    } else {
                        val input = contentResolver.openInputStream(raw) ?: error("无法读取原始抓包")
                        java.io.BufferedInputStream(input).use { capture ->
                            val magic = java.io.DataInputStream(capture).readInt()
                            val extension = when (magic) {
                                0xa1b2c3d4.toInt(), 0xa1b23c4d.toInt(), 0xd4c3b2a1.toInt(), 0x4d3cb2a1 -> "pcap"
                                0x0a0d0d0a -> "pcapng"
                                else -> error("所选文件不是可识别的 PCAP 或 PCAPNG")
                            }
                            val output = contentResolver.openOutputStream(uri, "wt") ?: error("无法打开导出文件")
                            ZipOutputStream(output).use { zip ->
                                zip.putNextEntry(ZipEntry("onan-evidence.md"))
                                zip.write(content.toByteArray(Charsets.UTF_8))
                                zip.closeEntry()
                                zip.putNextEntry(ZipEntry("capture.$extension"))
                                zip.write(java.nio.ByteBuffer.allocate(4).putInt(magic).array())
                                capture.copyTo(zip)
                                zip.closeEntry()
                            }
                        }
                    }
                    "报告已保存到所选位置"
                } else if (result == RESULT_OK) status = "导出预览已失效，请重新生成报告"
            }
            46 -> {
                val raw = data?.data
                val content = exportText
                exportText = null
                if (result == RESULT_OK && raw != null && content != null) save(content, false, raw)
            }
        }
        render()
    }
}

private class CappedInput(private val input: InputStream, private var left: Long) : InputStream() {
    override fun read(): Int {
        val byte = input.read()
        if (byte >= 0 && --left < 0) error("CSV 超过 16 MiB 导入上限")
        return byte
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val count = input.read(b, off, minOf(len.toLong(), left + 1).toInt())
        if (count > 0) {
            left -= count
            require(left >= 0) { "CSV 超过 16 MiB 导入上限" }
        }
        return count
    }
}
