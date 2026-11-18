package cn.onan.sentinel

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.view.View
import android.widget.*
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val store get() = (application as SentinelApp).store
    private lateinit var status: TextView
    private lateinit var history: TextView
    private var filter = "all"
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var visible = false
    private var loading = false
    private val refresh = object : Runnable {
        override fun run() { render(); if (visible) handler.postDelayed(this, 3000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 32, 28, 28); setBackgroundColor(Color.rgb(245, 248, 247)) }
        val scroll = ScrollView(this).apply { addView(root) }; setContentView(scroll)
        fun label(text: String, size: Float = 15f) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 8, 0, 12); root.addView(this) }
        label("欧南 · 系统网络哨兵", 24f)
        label("v0.3.0 P0 候选版 · 本地保存 · 无网络权限", 13f)
        status = label("正在读取本地状态…")
        label("采样仅覆盖主用户（user 0）。进程出现时间是观察时间；AppOps 仅比较可读模式，不能证明数据外传。深度休眠或系统限制可能造成空白期。", 13f)
        label("固定目标：\n" + Policy.targets.joinToString("\n"), 13f)
        fun button(title: String, action: () -> Unit) { root.addView(Button(this).apply { text = title; setOnClickListener { action() } }) }
        button("1 · 授予 Shizuku 权限") {
            try {
                if (!Shizuku.pingBinder()) toast("请先在 Shizuku 中启动无线调试服务")
                else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) toast("已授权")
                else Shizuku.requestPermission(12)
            } catch (e: Exception) { toast("Shizuku 不可用：${e.javaClass.simpleName}") }
        }
        button("2 · 启动 / 恢复监控") {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 20)
                toast("授权通知后，请再次点击启动")
            } else if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) toast("请在系统设置中允许通知")
            else command(MonitorService.START)
        }
        button("暂停采集（保留运行通知）") { if (MonitorService.live) command(MonitorService.PAUSE) else toast("监控未运行") }
        button("停止监控") { if (MonitorService.live) command(MonitorService.STOP) else toast("监控未运行") }
        button("采样间隔（默认 5 分钟）") {
            AlertDialog.Builder(this).setTitle("选择间隔；短间隔增加耗电")
                .setItems(arrayOf("1 分钟", "5 分钟", "15 分钟")) { _, which ->
                    getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("intervalMinutes", listOf(1, 5, 15)[which]).apply()
                    toast("间隔已保存；运行中最迟下一轮生效")
                }.show()
        }
        button("导出脱敏 JSONL 报告") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/x-ndjson").putExtra(Intent.EXTRA_TITLE, "onan-sentinel-report.jsonl"), 30)
        }
        button("旧版数据库只读结构检查") {
            io.execute {
                val message = try {
                    val legacy = LegacyEvidenceReader.inspect(this)
                    if (legacy.exists) "当前沙箱 evidence.db：schema 1，${legacy.rows} 条。未迁移、未修改。"
                    else "当前隔离包没有旧库。Android 沙箱禁止直接访问原应用数据；这不代表原应用无历史。"
                } catch (e: Exception) { "旧库不可读或结构不兼容：${e.javaClass.simpleName}。未重置。" }
                runOnUiThread { toast(message) }
            }
        }
        button("清理本测试包的本地历史") {
            if (MonitorService.live) toast("请先停止监控")
            else AlertDialog.Builder(this).setTitle("删除本测试包历史？")
                .setMessage("此操作不可撤销。不会访问旧版 cn.onan.sentinel 的数据。")
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                    io.execute { try { store.clearHistory(); runOnUiThread { toast("已清理"); render() } } catch (e: Exception) { runOnUiThread { toast("清理失败：${e.javaClass.simpleName}") } } }
                }.show()
        }
        button("删除本测试包的 schema 1 迁移备份") {
            if (MonitorService.live) toast("请先停止监控")
            else AlertDialog.Builder(this).setTitle("删除迁移前备份？")
                .setMessage("仅在已核对升级后历史完整时操作。此操作删除私有迁移备份，不清理当前数据库或旧版应用。")
                .setNegativeButton("取消", null).setPositiveButton("删除备份") { _, _ ->
                    io.execute { try { store.deleteMigrationBackup(); runOnUiThread { toast("迁移备份已删除") } }
                    catch(e: Exception) { runOnUiThread { toast("备份删除失败：${e.javaClass.simpleName}") } } }
                }.show()
        }
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("全部记录", "启动 / 状态时间线", "异常与变化", "可用证据"))
        root.addView(spinner)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                filter = listOf("all", "startup", "anomalies", "evidence")[position]; render()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        history = label("", 12f)
    }
    private fun command(action: String) {
        try { startForegroundService(Intent(this, MonitorService::class.java).setAction(action)) }
        catch (e: Exception) { toast("无法启动前台服务：${e.javaClass.simpleName}") }
    }
    private fun render() {
        if (!::history.isInitialized || loading || isFinishing) return
        loading = true
        val selectedFilter = filter
        io.execute {
            try {
                val app = application as SentinelApp
                val binder = try { if (Shizuku.pingBinder()) "已连接" else "未连接" } catch (_: Exception) { "不可用" }
                val state = if (MonitorService.live || MonitorService.runtimeStatus != "STOPPED") MonitorService.runtimeStatus else store.meta("state").ifEmpty { "STOPPED" }
                val bytes = getDatabasePath("sentinel_v020.db").length()
                val summary = "状态：$state\nShizuku Binder：$binder\n授权：${store.meta("link.authorization")}\nUserService：${store.meta("link.userService")}\n通知：${store.meta("link.notification")}\n${store.meta("lastSummary")}\n未关闭的来源空白期：${store.openSourceGaps()}\n${store.sourceSummary()}\n全局/旧版空白期：${store.meta("gapAt").ifEmpty { "无未关闭记录（不代表连续覆盖）" }}\n原因：${store.meta("reason")}\n数据库：${bytes / 1024} KiB / 32 MiB；最多 ${Policy.MAX_ROWS} 条\n${app.storageFailure.orEmpty()}"
                val date = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                val events = store.latest(selectedFilter)
                val body = "最近 ${events.size} 条；变化不等于恶意行为\n\n" + events.joinToString("\n\n") {
                    "${date.format(Date(it.time))}  ${it.type} [${it.status}]\n${it.target} · ${it.source}\n${it.evidence}"
                }
                runOnUiThread { if (!isDestroyed) { status.text = summary; history.text = body }; loading = false }
            } catch (e: Exception) { runOnUiThread { status.text = "本地存储不可用：${FailureReason.of(e)}。未重置数据库。"; loading = false } }
        }
    }
    @Deprecated("Uses platform document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode == 30 && resultCode == RESULT_OK) io.execute {
            try {
                contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { store.export(it) } ?: error("No output stream")
                runOnUiThread { toast("报告已导出；仅目标别名和分钟级时间，时间线仍可能敏感") }
            } catch (e: Exception) { runOnUiThread { toast("导出失败：${FailureReason.of(e)}") } }
        }
    }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    override fun onResume() { super.onResume(); visible = true; handler.post(refresh) }
    override fun onPause() { visible = false; handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { io.shutdown(); super.onDestroy() }
}
