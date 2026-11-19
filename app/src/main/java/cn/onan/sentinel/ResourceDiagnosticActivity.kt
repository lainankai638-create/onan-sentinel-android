package cn.onan.sentinel

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/** A manual diagnostic, isolated from MonitorService and EventStore. Never starts itself. */
class ResourceDiagnosticActivity:AppCompatActivity() {
    companion object { private val lane=BoundedCallLane() }
    private val io=Executors.newSingleThreadExecutor()
    private val control=CollectionControl()
    private lateinit var bridge:ShizukuBridge
    private lateinit var output:TextView
    private lateinit var start:MaterialButton
    private var report:JSONObject?=null
    private var pendingExport:String?=null
    override fun onCreate(savedInstanceState:Bundle?) {
        delegate.localNightMode=getSharedPreferences("settings",MODE_PRIVATE).getInt("theme",-1)
        super.onCreate(savedInstanceState)
        bridge=ShizukuBridge(this,serviceClass=ResourceUserService::class.java) { }
        pendingExport=savedInstanceState?.getString("pendingExport")
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;val p=(16*resources.displayMetrics.density).toInt();setPadding(p,p,p,p) }
        fun label(text:String,size:Float=16f)=TextView(this).apply { this.text=text;textSize=size;setPadding(0,12,0,12);root.addView(this) }
        fun button(text:String,action:()->Unit)=MaterialButton(this).apply { this.text=text;minHeight=(48*resources.displayMetrics.density).toInt();setOnClickListener { action() };root.addView(this,LinearLayout.LayoutParams(-1,-2)) }
        label("资源能力诊断",24f)
        label("最多 4 个可见代表进程，每次手动读取两轮，间隔约 3 秒。包含采集服务、名称命中关注组件的候选、system_server、普通应用代表；不存在就跳过。\nCPU 的 100% 表示一个逻辑核，可超过 100%；首样不计算。RSS 是驻留内存，不是 PSS 或独占内存。结果仅保留于本页内存，不写历史库。")
        button("检查 / 授予 Shizuku 权限") {
            try { if(!Shizuku.pingBinder()) toast("请先在 Shizuku 中启动 ADB / 无线调试服务")
                else if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED) toast("已授权；仍需手动诊断验证各项能力") else Shizuku.requestPermission(42)
            } catch(_:Exception) { toast("无法连接 Shizuku") }
        }
        start=button("手动开始两次采样") { diagnose() }
        button("导出脱敏诊断 JSON…") { confirmExport() }
        button("返回") { finish() }
        output=label("尚未诊断。K40S / HyperOS 能力待实机验证。离开页面会取消在途采集；重新进入不会自动采样。")
        output.setTextIsSelectable(true)
        setContentView(ScrollView(this).apply { addView(root) })
    }
    override fun onResume() { super.onResume();control.resume();bridge.start();start.isEnabled=true }
    override fun onPause() { control.halt();bridge.close();if(!start.isEnabled) output.text="采集已取消，没有接收在途结果。请返回后重新手动采样。";super.onPause() }
    override fun onDestroy() { io.shutdown();super.onDestroy() }
    override fun onSaveInstanceState(out:Bundle) { out.putString("pendingExport",pendingExport);super.onSaveInstanceState(out) }
    private fun diagnose() {
        if(!start.isEnabled) return
        report=null;start.isEnabled=false
        output.text="正在连接只读服务并采样。首样 CPU 使用率不可计算，不显示零值。两轮完成后显示全部数值、来源和不可用原因。"
        val token=control.token()
        io.execute {
            var lease:Pair<Long,IReadOnlyProbe>?=null
            val result=try {
                val deadline=SystemClock.elapsedRealtime()+12000
                var state="USER_SERVICE_CONNECTING"
                while(control.active(token)) {
                    control.accept(token) { state=bridge.connect();if(state=="READY") lease=bridge.lease() }
                    if(lease!=null || state !in setOf("USER_SERVICE_CONNECTING","USER_SERVICE_RETRY_BACKOFF") || SystemClock.elapsedRealtime()>=deadline) break
                    Thread.sleep(100)
                }
                val current=lease
                if(current==null) ResourceDiagnostics.failure(if(state in setOf("USER_SERVICE_CONNECTING","USER_SERVICE_RETRY_BACKOFF")) "USER_SERVICE_BIND_TIMEOUT" else state)
                else {
                    val raw=lane.call(15000) { current.second.resources() }
                    require(raw.length<=131072);JSONObject(raw)
                }
            } catch(e:Exception) { ResourceDiagnostics.failure(when(e) {
                is java.util.concurrent.TimeoutException->"DIAGNOSTIC_TIMEOUT"
                is IllegalStateException,is java.util.concurrent.RejectedExecutionException->"SOURCE_CALL_STILL_RUNNING"
                else->"DIAGNOSTIC_READ_FAILED"
            }) }
            runOnUiThread { control.accept(token) {
                val current=lease
                var accepted=current==null
                if(current!=null) accepted=bridge.accept(current.first) { }
                report=if(accepted) result else ResourceDiagnostics.failure("AUTHORIZATION_CHANGED")
                output.text=ResourcePresentation.text(report!!);start.isEnabled=true
            } }
        }
    }
    private fun confirmExport() {
        val current=report?:run { toast("请先完成一次诊断");return }
        val text=ResourceDiagnostics.redacted(current).toString(2)
        MaterialAlertDialogBuilder(this).setTitle("确认本地脱敏导出")
            .setMessage("移除进程名称、PID、UID、PPID、启动计数；保留资源数值、采样间隔、来源与失败原因。时间取整到分钟，诊断数据仍可能敏感。请在文件选择器中选择本地位置；不自动上传。")
            .setNegativeButton("取消",null).setPositiveButton("选择保存位置") { _,_->
                pendingExport=text
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"onan-resource-diagnostic.json"),41)
            }.show()
    }
    @Deprecated("Uses system document picker")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data);if(requestCode!=41) return
        val text=pendingExport;pendingExport=null;val uri=data?.data
        if(resultCode!=RESULT_OK || text==null || uri==null) return
        io.execute { try { contentResolver.openOutputStream(uri,"wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }?:error("OUTPUT_UNAVAILABLE")
            runOnUiThread { toast("已导出脱敏诊断；没有修改历史数据库") }
        } catch(_:Exception) { runOnUiThread { toast("导出失败；历史数据库未改动，请删除保存位置的残缺文件") } } }
    }
    private fun toast(text:String)=Toast.makeText(this,text,Toast.LENGTH_LONG).show()
}

object ResourcePresentation {
    private val labels=linkedMapOf("name" to "进程名称","pid" to "PID","discoveryUid" to "发现快照 UID（未复核）","uid" to "UID（真实用户身份）","ppid" to "PPID（父进程）",
        "userTicks" to "CPU 用户态原始计数（ticks）","systemTicks" to "CPU 内核态原始计数（ticks）","startTicks" to "启动计数（仅用于身份校验）","rssKiB" to "RSS 驻留内存（KiB，非 PSS）","threads" to "线程数")
    fun reason(code:String):String=when(code) {
        "OK"->"已读取";"FIRST_SAMPLE"->"首样不可计算，等待第二个有效样本";"FIELD_NOT_RETURNED"->"系统未返回此字段"
        "ACCESS_DENIED"->"系统拒绝读取；不绕过权限";"PROCESS_EXITED_OR_NOT_VISIBLE"->"进程已退出或该路径不可见"
        "FORMAT_UNSUPPORTED"->"系统输出格式不支持";"OUTPUT_LIMIT"->"元数据超过读取上限";"READ_FAILED","DIAGNOSTIC_READ_FAILED"->"读取失败"
        "IDENTITY_CHANGED_DURING_READ","PROCESS_IDENTITY_CHANGED"->"进程身份发生变化，不比较 CPU"
        "IDENTITY_UNVERIFIED"->"已读取数值，但无法确认跨文件进程身份";"DISCOVERY_ONLY"->"仅为发现快照值，尚未复核"
        "TWO_VALID_SAMPLES_REQUIRED"->"缺少两次同一进程的有效计数";"CLOCK_TICKS_UNAVAILABLE"->"无法取得一致的计数频率"
        "INVALID_INTERVAL"->"采样间隔无效";"COUNTER_REVERSED"->"CPU 计数倒退，不计算使用率"
        "NO_REPRESENTATIVE_VISIBLE"->"没有可见的代表进程";"DIAGNOSTIC_TIMEOUT"->"诊断调用超时"
        else->Chinese.status(code)
    }
    fun text(report:JSONObject):String=buildString {
        if(report.optString("status")!="COMPLETED") { val code=report.optString("reason");append("诊断不可用：${reason(code)}\n技术原因：$code\nK40S 能力仍待实机验证。");return@buildString }
        append("两轮诊断已完成（不代表所有指标可用）。数据不是原子快照；CPU 仅在两次身份和计数有效时计算。名称可能截断，不据此确认应用归属。\n")
        val samples=report.getJSONArray("samples")
        for(i in 0 until samples.length()) {
            append("\n━━ 代表进程 ${i+1} ━━\n")
            val row=samples.getJSONObject(i)
            for(phase in listOf("first","second")) {
                val point=row.getJSONObject(phase);append(if(phase=="first") "\n第一次采样\n" else "\n第二次采样\n")
                append("时钟频率：${if(point.isNull("clockTicksPerSecond")) "不可用：无法读取计数频率" else point.getLong("clockTicksPerSecond").toString()+" ticks/秒"}；来源 sysconf(_SC_CLK_TCK)\n")
                for((key,label) in labels+mapOf("cpuPercent" to "CPU 使用率（单逻辑核 100%）")) {
                    val m=if(key=="cpuPercent") point.getJSONObject(key) else point.getJSONObject("fields").getJSONObject(key)
                    val code=m.getString("reason");append("$label：${if(m.isNull("value")) "不可用" else m.getString("value")+(if(key=="cpuPercent") "%" else "")}\n来源：${m.getString("source")}\n状态：${reason(code)} [$code]\n")
                }
            }
            val elapsed=row.getJSONObject("second").getLong("atNs")-row.getJSONObject("first").getLong("atNs")
            append("实际计数观察间隔：${elapsed/1000000} ms（包括可能的休眠，不保证恰好3秒）\n")
        }
    }
}
