package cn.onan.sentinel

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/** Bounded local reads; remote queries require a visible user action. */
class MainActivity : AppCompatActivity() {
    private val store get() = (application as SentinelApp).store
    private val prefs get() = getSharedPreferences("settings", MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()
    private val operations = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var title: TextView
    private lateinit var nav: BottomNavigationView
    private lateinit var scroll: ScrollView
    private var page = "overview"
    private var target: String? = null
    private var selectedProcess: ProcessIdentity? = null
    private var appMetadata=""
    private var category = "all"
    private var search = ""
    private var processOffset = 0
    private var snapshot: ProcessSnapshot? = null
    private var processes = emptyList<ProcessIdentity>()
    private var snapshotBusy = false
    private var client: SnapshotClient? = null
    private var visible = false
    private var loading = false
    private var revision = 0
    private var state = "STOPPED"
    private var failure = ""
    private var health = emptyList<SourceHealth>()
    private var rows = emptyList<Event>()
    private var filter = EvidenceFilter(0, Long.MAX_VALUE)
    private var before = Long.MAX_VALUE
    private var pendingReport: EvidenceFilter? = null
    private lateinit var network: NetworkExperiment
    private var traffic=emptyList<TrafficResult>()
    private var networkBusy=false
    private var trafficGroups=emptyMap<Int,List<PackageIdentity>>()
    private val refresh = Runnable { load() }
    private val changed: () -> Unit = { handler.post { if (visible) { handler.removeCallbacks(refresh); handler.postDelayed(refresh,350) } } }
    private fun dp(n:Int) = (n * resources.displayMetrics.density).toInt()
    private fun time(at:Long?) = at?.let { SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(Date(it)) } ?: "尚无成功记录"
    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = prefs.getInt("theme",AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        super.onCreate(savedInstanceState)
        network=NetworkExperiment(AndroidTrafficBackend(this))
        savedInstanceState?.let {
            page=it.getString("page","overview");target=it.getString("target")
            category=it.getString("category","all");search=it.getString("search","")
            if(page=="detail" && target==null) page="processes"
            filter=EvidenceFilter(it.getLong("start",0),it.getLong("end",Long.MAX_VALUE),it.getString("filterTarget"),it.getString("source"))
            if(it.containsKey("reportStart")) pendingReport=EvidenceFilter(it.getLong("reportStart"),it.getLong("reportEnd"),it.getString("reportTarget"),it.getString("reportSource"))
        }
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        title=TextView(this).apply { textSize=24f;setPadding(dp(20),dp(16),dp(20),dp(8));setTextColor(getColor(R.color.sentinel_on_surface)) }
        root.addView(title)
        body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),dp(20)) }
        scroll=ScrollView(this).apply { isFillViewport=true;addView(body) }
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        nav=BottomNavigationView(this).apply {
            menu.add(0,R.id.tab_overview,0,"总览").setIcon(R.drawable.nav_overview);menu.add(0,R.id.tab_apps,1,"应用").setIcon(R.drawable.nav_apps)
            menu.add(0,R.id.tab_timeline,2,"时间线").setIcon(R.drawable.nav_timeline);menu.add(0,R.id.tab_report,3,"报告").setIcon(R.drawable.nav_report)
            labelVisibilityMode=com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED
            menu.findItem(when(page) { "processes","detail","sensitive"->R.id.tab_apps;"timeline"->R.id.tab_timeline;"report"->R.id.tab_report;else->R.id.tab_overview }).isChecked=true
            setOnItemSelectedListener { go(when(it.itemId) { R.id.tab_apps->"processes";R.id.tab_timeline->"timeline";R.id.tab_report->"report";else->"overview" });true }
        }
        root.addView(nav,LinearLayout.LayoutParams(-1,-2));setContentView(root);draw()
    }
    override fun onSaveInstanceState(out:Bundle) {
        out.putString("page",page);out.putString("target",target);out.putLong("start",filter.start);out.putLong("end",filter.end);out.putString("filterTarget",filter.target);out.putString("source",filter.source)
        out.putString("category",category);out.putString("search",search)
        pendingReport?.let { out.putLong("reportStart",it.start);out.putLong("reportEnd",it.end);out.putString("reportTarget",it.target);out.putString("reportSource",it.source) }
        super.onSaveInstanceState(out)
    }
    override fun onResume() { super.onResume();visible=true;network.enabled(prefs.getBoolean("networkExperiment",false));UiSignals.subscribe(changed);load() }
    override fun onPause() { visible=false;revision++;network.enabled(false);UiSignals.unsubscribe(changed);handler.removeCallbacks(refresh);client?.stop();client=null;super.onPause() }
    override fun onDestroy() { io.shutdown();operations.shutdown();super.onDestroy() }
    @Deprecated("Back navigation to overview")
    override fun onBackPressed() { if(page!="overview") { nav.selectedItemId=R.id.tab_overview;go("overview") } else super.onBackPressed() }
    private fun go(next:String) { page=next;before=Long.MAX_VALUE;revision++;rows=emptyList();appMetadata="";draw();scroll.scrollTo(0,0);load() }
    private fun load() {
        if(!visible || loading) return
        loading=true
        val epoch=revision;val requestedPage=page;val requestedTarget=target;val requested=when(page) { "detail" -> EvidenceFilter(0,Long.MAX_VALUE,target);"sensitive" -> EvidenceFilter(0,Long.MAX_VALUE,source="SHIZUKU_APPOPS");else -> filter };val cursor=before
        io.execute { try {
            val newHealth=store.sourceHealth();val newState=if(MonitorService.live || MonitorService.runtimeStatus!="STOPPED") MonitorService.runtimeStatus else store.meta("state").ifEmpty { "STOPPED" }
            val newRows=if(requestedPage=="sensitive") Policy.targets.flatMap { store.events(requested.copy(target=it),1) } else store.events(requested,100,cursor)
            val metadata=if(requestedPage=="detail" && requestedTarget!=null) try { val info=packageManager.getPackageInfo(requestedTarget,0);"版本 ${info.versionName}\n系统分配的应用身份（UID）：${info.applicationInfo?.uid}" } catch(_:Exception) { "系统没有返回此应用的包元数据" } else ""
            runOnUiThread { loading=false;if(visible && epoch==revision) { health=newHealth;state=newState;rows=newRows;appMetadata=metadata;failure="";draw() } else if(visible) load() }
        } catch(e:Exception) { runOnUiThread { loading=false;failure=FailureReason.of(e);if(visible) draw() } } }
    }
    private fun label(parent:LinearLayout,text:String,size:Float=16f):TextView = TextView(this).apply { this.text=text;textSize=size;setTextColor(getColor(R.color.sentinel_on_surface));setPadding(0,dp(6),0,dp(6));parent.addView(this) }
    private fun card(heading:String,content:String="",action:(LinearLayout.()->Unit)?=null) {
        val card=MaterialCardView(this).apply { radius=dp(20).toFloat();strokeWidth=dp(1);strokeColor=getColor(R.color.sentinel_container);setCardBackgroundColor(getColor(R.color.sentinel_surface)) }
        val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(10),dp(16),dp(12)) }
        label(column,heading,19f);if(content.isNotEmpty()) label(column,content);action?.invoke(column);card.addView(column)
        body.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
    }
    private fun LinearLayout.button(text:String,action:()->Unit) { addView(MaterialButton(this@MainActivity).apply { this.text=text;minHeight=dp(48);isAllCaps=false;setOnClickListener { action() } },LinearLayout.LayoutParams(-1,-2)) }
    private fun message(title:String,content:String) { MaterialAlertDialogBuilder(this).setTitle(title).setMessage(content).setPositiveButton("知道了",null).show() }
    private fun choose(title:String,items:List<String>,action:(Int)->Unit) { MaterialAlertDialogBuilder(this).setTitle(title).setItems(items.toTypedArray()) { _,n->action(n) }.show() }
    private fun toast(text:String) = Toast.makeText(this,text,Toast.LENGTH_LONG).show()
    private fun draw() {
        if(!::body.isInitialized) return
        body.removeAllViews();title.text=when(page) { "overview"->"安全总览";"processes"->"运行进程";"detail"->"应用详情";"sensitive"->"敏感行为";"network"->"网络活动";"timeline"->"事件时间线";"settings"->"监控设置";else->"本地报告" }
        label(body,"欧南 · 系统网络哨兵  ${BuildConfig.VERSION_NAME}",14f)
        if(failure.isNotEmpty()) card("本地数据读取失败","未重置数据库。技术原因：$failure")
        when(page) { "overview"->overview();"processes"->processPage();"detail"->detail();"sensitive"->sensitive();"timeline"->timeline();"settings"->settings();"network"->networkPage();else->reportPage() }
    }
    private fun overview() {
        card(Chinese.status(state),"间隔 ${prefs.getInt("intervalMinutes",5)} 分钟 · 仅本地保存\n定期观察不等于连续覆盖；变化不等于恶意行为。") {
            button("启动 / 恢复监控") { startMonitoring() }
            if(MonitorService.live) { button("暂停采集") { command(MonitorService.PAUSE) };button("停止监控") { command(MonitorService.STOP) } }
            button("授权与监控设置") { go("settings") }
        }
        card("证据覆盖范围","不代表全面安全检测。后台仅关注主用户的 3 个系统组件。进程可见状态与 AppOps 模式分别采样；不读取通信正文、不录音、不抓包。") {
            button("查看可见运行进程") { go("processes") };button("敏感操作模式") { go("sensitive") };button("网络统计实验") { go("network") }
        }
        if(health.isEmpty()) card("尚无来源采样记录","启动监控后才会产生成功时间、来源状态和空白期。")
        health.forEach { h -> card("${Chinese.target(h.target)} · ${Chinese.source(h.source)}","${Chinese.status(h.status)}\n最近成功：${time(h.lastSuccess)}\n${if(h.gap) "存在未结束的空白期，期间无有效证据" else "没有未关闭空白期记录，不代表连续覆盖"}") {
            button("原因与技术详情") { message("来源详情","${h.target}\n${h.source}\n${h.status}\n${Chinese.status(h.reason)}\n${h.reason}") }
        } }
    }
    private fun authorize() {
        try { if(!Shizuku.pingBinder()) message("Shizuku 尚未运行","请先在 Shizuku 中以无线调试方式启动服务，然后返回这里授权。")
        else if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED) toast("Shizuku 已授权") else Shizuku.requestPermission(12) }
        catch(e:Exception) { message("授权服务不可用",FailureReason.of(e)) }
    }
    private fun startMonitoring() {
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),20);toast("授权通知后再次点击启动");return }
        if(!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) { toast("请在系统设置允许通知");return };command(MonitorService.START)
    }
    private fun command(action:String) { try { startForegroundService(Intent(this,MonitorService::class.java).setAction(action));handler.postDelayed(refresh,500) } catch(e:Exception) { message("前台服务无法启动",FailureReason.of(e)) } }
    private fun processPage() {
        val snap=snapshot
        card("即时可见快照",if(snap==null) "尚未读取。手动刷新通过固定只读 ps 命令查询当前可见进程。" else "${Chinese.status(snap.status)} · ${time(snap.observedAt)}\n${Chinese.status(snap.reason)}\n这是一个观察时点，不是全程运行记录。") {
            button(if(snapshotBusy) "正在读取…" else "刷新进程快照") { takeSnapshot() }
            if(snap?.status!="OK") { button("检查 Shizuku 授权") { authorize() };if(snap!=null) button("查看失败原因") { message("技术详情",snap.status+"\n"+snap.reason) } }
            button("分类："+mapOf("all" to "全部","system" to "系统进程","third" to "第三方进程","unknown" to "归属不明")[category]) { choose("按可确认的 UID 包信息分类",listOf("全部","系统进程","第三方进程","归属不明")) { category=listOf("all","system","third","unknown")[it];draw() } }
            button(if(search.isEmpty()) "搜索名称、包名或 PID" else "搜索：$search") {
                val input=EditText(this@MainActivity).apply { setText(search);hint="名称、包名、PID";isSingleLine=true }
                MaterialAlertDialogBuilder(this@MainActivity).setTitle("搜索当前快照").setView(input).setPositiveButton("应用") { _,_->search=input.text.toString().take(100);draw() }.setNeutralButton("清除") { _,_->search="";draw() }.setNegativeButton("取消",null).show()
            }
        }
        val matches=processes.filter { (category=="all" || it.category==category) && (search.isBlank() || (it.process.name+" "+it.process.pid+" "+it.candidates.joinToString { p->p.label+" "+p.packageName }).contains(search,true)) }
        if(snap?.status=="OK") label(body,"可见 ${processes.size} 项 · 匹配 ${matches.size} 项；最多显示 100 项，可搜索缩小范围。")
        if(processOffset>=matches.size) processOffset=0
        matches.drop(processOffset).take(100).forEach { p -> card(p.nameClue?.label?:p.candidates.singleOrNull()?.label?:p.process.name,"PID ${p.process.pid} · UID ${p.process.uid}\n${p.process.name}\n"+(if(p.shared) "共享 UID：${p.candidates.size} 个候选应用，无法唯一归属" else p.candidates.singleOrNull()?.packageName?:"包名归属无法确认")) {
            button("查看应用与证据") { selectedProcess=p;target=p.candidates.singleOrNull()?.packageName;go("detail") }
        } }
        if(processOffset+100<matches.size) body.button("下一页进程") { processOffset+=100;draw() }
        if(processOffset>0) body.button("返回进程首页") { processOffset=0;draw() }
        card("持续关注的系统组件","仅以下组件有后台历史；其他应用当前只提供即时快照。") { Policy.targets.forEach { pkg->button(Chinese.target(pkg)) { selectedProcess=null;target=pkg;go("detail") } } }
    }
    private fun takeSnapshot() {
        if(snapshotBusy) return
        val probe=client?:SnapshotClient(this).also { client=it;it.start() };snapshotBusy=true;draw()
        operations.execute {
            val result=try { probe.read() } catch(e:Exception) { ProcessSnapshot("UNAVAILABLE",FailureReason.of(e),System.currentTimeMillis(),emptyList()) }
            val cache=mutableMapOf<Int,List<PackageIdentity>>();val mapped=result.rows.map { ProcessAttribution.resolve(it,cache.getOrPut(it.uid) { ProcessAttribution.packages(this,it.uid) }) }
            runOnUiThread { snapshotBusy=false;if(visible && client===probe) { snapshot=result;processes=mapped;if(page=="processes") draw() } }
        }
    }
    private fun detail() {
        val p=selectedProcess
        card(target?.let { Chinese.target(it) }?:"进程归属候选",(p?.let { "PID ${it.process.pid} · UID ${it.process.uid} · 用户 ${it.process.uid/100000}\n${it.process.name}" }?:target.orEmpty())+"\n"+appMetadata) {
            if(p!=null) { if(p.candidates.isEmpty()) label(this,"系统未提供可确认的包信息。隔离 UID、其他用户或原生进程可能无法关联应用。")
                p.candidates.forEach { pkg -> label(this,"${pkg.label} · 版本 ${pkg.version}\n${pkg.packageName}");if(pkg.packageName in Policy.targets) button("查看此候选组件历史") { target=pkg.packageName;revision++;load() } }
                if(p.shared) label(this,"共享 UID 的全部候选保留；名称匹配只是线索，不能确认唯一归属。")
            }
            button("敏感操作模式与限制") { go("sensitive") };button("系统网络统计与限制") { go("network") }
        }
        if(target !in Policy.targets) card("没有后台历史采样","此应用不在当前 3 个固定关注组件中；不会把其他应用的记录当作它的历史。")
        else { card("组件历史","最近 100 条保留记录。PID 变化不能证明重启。")
            rows.filter { it.target==target && it.source in setOf("SHIZUKU_PS","SHIZUKU_APPOPS") }.forEach { eventCard(it) }
            if(rows.isEmpty()) card("尚无该组件的历史证据","请先启动监控。组件不存在或来源不可读时会记录不可用原因。")
        }
    }
    private fun sensitive() {
        card("操作模式 ≠ 实际访问","访问记录未启用。allow 表示规则允许，不代表发生了访问。当前只采集可读取的 AppOps 模式，不提供麦克风/相机实际使用次数，也不能证明数据外传。")
        Policy.targets.forEach { pkg -> val row=rows.firstOrNull { it.target==pkg };if(row==null) card(Chinese.target(pkg),"尚无保留的 AppOps 采样；不能推断从未访问。") else eventCard(row) }
    }
    private fun eventCard(e:Event) { card("${Chinese.status(e.type)} · ${Chinese.status(e.status)}","${time(e.time)}\n${Chinese.target(e.target)} · ${Chinese.source(e.source)}\n${Chinese.evidence(e)}") { button("保留的技术详情") { message("记录 #${e.id}","${e.target}\n${e.source}\n${e.type} / ${e.status}\n${e.evidence}") } } }
    private fun filters() { card("筛选保留历史", "时间：${if(filter.start==0L) "全部保留范围" else time(filter.start)+" 至 "+time(filter.end)}\n应用：${filter.target?.let { Chinese.target(it) }?:"全部"} · 来源：${filter.source?.let { Chinese.source(it) }?:"全部"}") {
        button("时间范围") { choose("结束时间固定为选择时刻",listOf("最近 1 小时","最近 24 小时","最近 7 天","全部保留历史","自定义日期时间")) { n -> if(n==4) customRange() else { val now=System.currentTimeMillis();filter=filter.copy(start=if(n==3) 0 else now-listOf(3600000L,86400000L,604800000L)[n],end=now+1);before=Long.MAX_VALUE;revision++;load() } } }
        button("应用筛选") { choose("固定关注组件",listOf("全部")+Policy.targets.map { Chinese.target(it) }) { n->filter=filter.copy(target=if(n==0)null else Policy.targets[n-1]);before=Long.MAX_VALUE;revision++;load() } }
        button("数据源筛选") { choose("选择数据源",listOf("全部","进程快照","系统操作模式","监测会话","Wi-Fi 系统统计","移动网络系统统计")) { n->filter=filter.copy(source=listOf(null,"SHIZUKU_PS","SHIZUKU_APPOPS","APP_LIFECYCLE","NETWORK_STATS_WIFI","NETWORK_STATS_MOBILE")[n]);before=Long.MAX_VALUE;revision++;load() } }
    } }
    private fun customRange() {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(24),0,dp(24),0) }
        val start=EditText(this).apply { hint="开始：2026-11-18 00:00";isSingleLine=true };val end=EditText(this).apply { hint="结束：2026-11-19 00:00";isSingleLine=true };box.addView(start);box.addView(end)
        MaterialAlertDialogBuilder(this).setTitle("本机时区，包含开始、不含结束").setView(box).setPositiveButton("应用") { _,_-> try { val format=SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA).apply { isLenient=false };filter=filter.copy(start=format.parse(start.text.toString())!!.time,end=format.parse(end.text.toString())!!.time);before=Long.MAX_VALUE;revision++;load() } catch(_:Exception) { toast("时间格式不正确，或结束不晚于开始") } }.setNegativeButton("取消",null).show()
    }
    private fun timeline() { filters();label(body,"每页最多 100 条。空白期间不补造记录；没有事件不等于没有发生行为。")
        if(rows.isEmpty()) card("此范围没有保留记录","可更改筛选。未采集、清理或容量淘汰都可能造成缺失。")
        rows.forEach { eventCard(it) };if(rows.size==100) body.button("更早的 100 条") { before=rows.last().id;load() };if(before!=Long.MAX_VALUE) body.button("返回最新记录") { before=Long.MAX_VALUE;load() }
    }
    private fun settings() {
        card("采集控制","需用户手动启动；不承诺 HyperOS 后台不被终止。Shizuku 授权丢失后相关采集停止，并记录空白期。") {
            button("授予 Shizuku 权限") { authorize() };button("启动 / 恢复监控") { startMonitoring() }
            button("采样间隔：${prefs.getInt("intervalMinutes",5)} 分钟") { choose("短间隔增加耗电",listOf("1 分钟","5 分钟","15 分钟")) { prefs.edit().putInt("intervalMinutes",listOf(1,5,15)[it]).apply();draw() } }
            button("界面主题") { choose("主题",listOf("跟随系统","浅色","深色")) { val mode=listOf(-1,1,2)[it];prefs.edit().putInt("theme",mode).apply();delegate.localNightMode=mode } }
            button("运行状态技术详情") { message("状态",state+"\n"+(application as SentinelApp).storageFailure.orEmpty()) }
        }
        card("本地历史保护","SQLite schema 2；上限 32 MiB / ${Policy.MAX_ROWS} 条事件，容量达到阈值会淘汰较早记录。升级不主动清理历史。") {
            button("清理本预览包历史…") { if(MonitorService.live) toast("请先停止监控") else MaterialAlertDialogBuilder(this@MainActivity).setTitle("确认删除本预览包历史？").setMessage("不可撤销。不访问旧版 cn.onan.sentinel 数据。建议先导出报告。").setNegativeButton("取消",null).setPositiveButton("删除历史") { _,_->io.execute { try { store.clearHistory();runOnUiThread { toast("已清理用户选择的历史");load() } } catch(e:Exception) { runOnUiThread { toast(FailureReason.of(e)) } } } }.show() }
        }
    }
    private fun reportPage() { filters();card("中文概览 + JSONL 明细","导出所选范围内的保留证据，使用目标别名、分钟级时间和白名单字段。报告区分证据、空白期与未知情况。") { button("预览统计并确认导出") { prepareReport() } } }
    private fun prepareReport() {
        val end=minOf(filter.end,System.currentTimeMillis()+1)
        if(filter.start>=end) { toast("所选时间尚未发生，无法生成监测报告");return }
        val chosen=filter.copy(end=end)
        io.execute { try { val summary=LocalReports.summary(store,chosen)
            runOnUiThread { if(visible) MaterialAlertDialogBuilder(this).setTitle("确认本地脱敏导出").setMessage(summary.markdown+"\n时间线仍可能敏感。请在文件选择器中选择本地位置；不自动上传。").setNegativeButton("取消",null).setPositiveButton("选择保存位置") { _,_->pendingReport=chosen;startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE,"onan-sentinel-report.zip"),30) }.show() }
        } catch(e:Exception) { runOnUiThread { toast("报告不可用：${FailureReason.of(e)}") } } }
    }
    @Deprecated("Uses system document picker")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data);if(requestCode!=30) return
        val chosen=pendingReport;pendingReport=null;val uri=data?.data;if(resultCode!=RESULT_OK || uri==null || chosen==null) return
        operations.execute { try { LocalReports.export(this,store,chosen) { file -> contentResolver.openOutputStream(uri,"wt")?.use { output->file.inputStream().use { it.copyTo(output) } }?:error("OUTPUT_UNAVAILABLE") };runOnUiThread { toast("已导出中文 Markdown 与 JSONL 压缩包") } } catch(e:Exception) { runOnUiThread { toast("导出未完成：${FailureReason.of(e)}；请删除目标位置的残缺文件") } } }
    }
    private fun networkPage() {
        val enabled=prefs.getBoolean("networkExperiment",false)
        val capability=network.capability()
        card("可选 · 系统统计桶","${if(enabled) "实验已开启，仅手动查询" else "默认关闭，未开始采集"}\n${if(capability=="READY") "系统授予查询权限，仍需检查实际返回值" else Chinese.status(capability)}\n不创建 VPN、不修改系统设置、不读取域名或连接内容。现有 VPN 保持不变；VPN 归属、流量去向与外传无法由此确认。") {
            button(if(enabled) "关闭实验并停止后续查询" else "开启网络统计实验") { val next=!enabled;prefs.edit().putBoolean("networkExperiment",next).apply();network.enabled(next);traffic=emptyList();draw() }
            if(enabled) {
                button("打开使用情况访问授权页") { try { startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch(_:Exception) { toast("系统未提供授权页面") } }
                button(if(networkBusy) "正在查询…" else "查询最近 24 小时统计桶") { queryNetwork() }
            }
            button("能力检测详情") { message("系统统计能力",capability+"\n权限允许不保证 HyperOS 返回其他 UID 的统计。无桶不解释为零流量。") }
        }
        card("归属与范围","查询当前用户的 3 个关注组件 UID，并用哨兵自身 UID 检查基本能力。共享 UID 不拆分到单个应用；系统桶具有延迟和时间粒度，不是实时计费。网络记录在报告中需选择“全部应用”，不会冒充某一组件的独占流量。")
        traffic.forEach { r -> card(if(r.uid<0) "查询状态" else "UID ${r.uid} · ${Chinese.source(r.source)}", (if(r.status=="OK") "系统返回 ${r.buckets} 个桶\n接收 ${r.rx} 字节 · 发送 ${r.tx} 字节\n桶覆盖：${time(r.first)} 至 ${time(r.last)}\n共享身份统计，不能确认最终应用归属。" else "${Chinese.status(r.reason)}\n未将不可用数据填为 0。")+"\n"+trafficGroups[r.uid].orEmpty().joinToString("\n") { it.label+" · "+it.packageName }) { button("技术详情") { message("统计详情",r.status+"\n"+r.reason+"\n"+r.evidence()) } } }
    }
    private fun queryNetwork() {
        if(networkBusy || !prefs.getBoolean("networkExperiment",false)) return
        networkBusy=true;draw();val end=System.currentTimeMillis();val ticket=network.ticket()
        operations.execute {
            val uids=Policy.targets.mapNotNull { try { packageManager.getApplicationInfo(it,0).uid } catch(_:Exception) { null } }+android.os.Process.myUid()
            val groups=uids.distinct().associateWith { ProcessAttribution.packages(this,it) }
            val result=try { network.query(uids,end-86400000L,end,ticket) } catch(_:Exception) { listOf(TrafficResult(-1,"NETWORK_STATS","UNAVAILABLE","NETWORK_STATS_UNSUPPORTED")) }
            // UI lifecycle cancels acceptance; never persist a late result after disabling or leaving.
            var saveError:String?=null
            try { network.accept(ticket) { result.filter { it.uid>=0 }.forEach { store.add("NETWORK_BUCKET_QUERY",it.status,it.evidence(),"uid:${it.uid}",it.source) } } } catch(e:Exception) { saveError=FailureReason.of(e) }
            runOnUiThread { networkBusy=false;if(visible) network.accept(ticket) { traffic=result;trafficGroups=groups;if(saveError!=null) toast("统计未保存：$saveError");if(page=="network") draw() } }
        }
    }
}
