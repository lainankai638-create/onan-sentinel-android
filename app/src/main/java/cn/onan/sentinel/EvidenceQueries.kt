package cn.onan.sentinel

object UiSignals {
    private val listeners=java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    fun subscribe(listener:()->Unit) { listeners.add(listener) }
    fun unsubscribe(listener:()->Unit) { listeners.remove(listener) }
    fun changed() { listeners.forEach { it() } }
}

data class EvidenceFilter(val start:Long,val end:Long,val target:String?=null,val source:String?=null) {
    init { require(start>=0 && end>start) }
    fun where(prefix:String="",timeColumn:String="timestamp"):Pair<String,Array<String>> {
        val conditions=mutableListOf("$prefix$timeColumn>=?","$prefix$timeColumn<?")
        val args=mutableListOf(start.toString(),end.toString())
        target?.let { conditions+="$prefix"+"target=?";args+=it }
        source?.let {
            if(it=="NETWORK_STATS") conditions+="${prefix}source IN ('NETWORK_STATS_WIFI','NETWORK_STATS_MOBILE')"
            else { conditions+="${prefix}source=?";args+=it }
        }
        return conditions.joinToString(" AND ") to args.toTypedArray()
    }
}
data class SourceHealth(val source:String,val target:String,val status:String,val reason:String,val lastSuccess:Long?,val gap:Boolean)
fun EventStore.sourceHealth():List<SourceHealth> = synchronized(this) {
    readableDatabase.rawQuery("SELECT source,target,status,reason,last_success,open_gap_id FROM source_state ORDER BY target,source",null).use { c ->
        buildList { while(c.moveToNext()) add(SourceHealth(c.getString(0),c.getString(1),c.getString(2),c.getString(3),if(c.isNull(4)) null else c.getLong(4),!c.isNull(5))) }
    }
}
fun EventStore.events(filter:EvidenceFilter,limit:Int=100,before:Long=Long.MAX_VALUE):List<Event> = synchronized(this) {
    val (where,args)=filter.where()
    readableDatabase.rawQuery("SELECT id,timestamp,target,type,source,status,evidence FROM events WHERE $where AND id<? ORDER BY id DESC LIMIT ?",args+arrayOf(before.toString(),limit.coerceIn(1,500).toString())).use { c ->
        buildList { while(c.moveToNext()) add(Event(c.getLong(0),c.getLong(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getString(6))) }
    }
}
object Chinese {
    private val states=mapOf(
        "OK" to "采样成功", "PARTIAL" to "部分证据可用", "UNAVAILABLE" to "暂不可用", "UNSUPPORTED" to "不支持此能力",
        "PARSE_ERROR" to "无法识别系统格式", "DENIED" to "权限不足", "PERMISSION_DENIED" to "权限不足", "UNKNOWN" to "无法确认",
        "STOPPED" to "监测已停止", "STOPPED_UNCLEAN" to "上次监测异常中断", "PAUSED" to "已暂停采集", "CONNECTING" to "正在连接授权服务",
        "MONITORING_SAMPLED" to "正在定期采样", "MONITORING_PARTIAL" to "正在采样 · 部分来源不可用", "ALL_SOURCES_UNAVAILABLE" to "所有来源暂不可用",
        "SHIZUKU_NOT_RUNNING" to "Shizuku 尚未运行", "SHIZUKU_PERMISSION_REQUIRED" to "需要 Shizuku 授权",
        "SHIZUKU_PERMISSION_DENIED" to "Shizuku 授权已失效", "SHIZUKU_DISCONNECTED" to "Shizuku 连接已中断",
        "SHIZUKU_BINDER_UNAVAILABLE" to "无法连接 Shizuku", "SHIZUKU_ADB_SHELL_REQUIRED" to "请使用无线调试模式的 Shizuku",
        "USER_SERVICE_CONNECTING" to "正在连接只读采集服务", "USER_SERVICE_RETRY_BACKOFF" to "连接失败，等待重试",
        "USER_SERVICE_BIND_TIMEOUT" to "采集服务连接超时", "USER_SERVICE_BIND_FAILED" to "采集服务连接失败",
        "USER_SERVICE_DISCONNECTED" to "只读采集服务已断开", "USER_SERVICE_CALL_FAILED_OR_TIMEOUT" to "采集调用失败或超时",
        "SOURCE_CALL_STILL_RUNNING" to "上一次采集尚未结束", "PS_FORMAT_UNKNOWN" to "无法识别进程列表格式",
        "APPOPS_FORMAT_UNKNOWN" to "无法识别系统操作格式", "OUTPUT_LIMIT_OR_READ_FAILURE" to "输出过大或读取失败",
        "COMMAND_TIMEOUT" to "系统命令读取超时", "COMMAND_EXECUTION_FAILED" to "系统命令不可读取",
        "AUTHORIZATION_CHANGED" to "授权已变化，请重新采样", "NOTIFICATION_PERMISSION_REQUIRED" to "需要允许运行通知",
        "NOTIFICATIONS_DISABLED" to "运行通知被关闭", "USER_PAUSED" to "用户暂停了采集", "USER_STOPPED" to "用户停止了监测",
        "VISIBLE_SNAPSHOT_ONLY" to "本次可见快照", "NO_RECORDED_OPERATIONS" to "未返回操作记录", "TIMEOUT" to "读取超时",
        "BASELINE" to "首次有效观察", "SAMPLE" to "定期采样", "STATE_CHANGED" to "观察到状态变化",
        "SAMPLE_UNAVAILABLE" to "本次采样不可用", "SESSION_START" to "开始监测", "SESSION_STOP" to "停止监测",
        "SOURCE_GAP_BEGIN" to "来源中断", "SOURCE_GAP_END" to "来源恢复", "GAP_BEGIN" to "监测出现空白期", "GAP_END" to "监测恢复",
        "UNCLEAN_STOP_DETECTED" to "发现上次异常停止", "CLOCK_CHANGED" to "系统时间发生变化", "RESUMED" to "恢复采集",
        "LINK_STATE" to "连接状态变化", "DEVICE_CONTEXT" to "设备与采样设置", "LOCAL_CLEANUP" to "用户清理历史",
        "SHIZUKU_CONNECTED" to "采集服务已连接", "SHIZUKU_BINDER_RECEIVED" to "已收到 Shizuku 连接",
        "COLLECTION_UNAVAILABLE" to "采集不可用", "NETWORK_BUCKET_QUERY" to "系统网络统计查询",
        "NO_BUCKETS" to "系统未返回统计桶", "EXPERIMENT_DISABLED" to "网络统计实验已关闭", "USAGE_ACCESS_REQUIRED" to "需要使用情况访问权限",
        "STORAGE_FAILURE" to "本地存储不可用", "LOCAL_STORAGE_FULL" to "本地存储空间不足", "MIGRATION_SPACE_REQUIRED" to "升级备份所需空间不足",
        "NETWORK_STATS_UNSUPPORTED" to "系统统计不可读取或不支持", "NETWORK_BUCKET_INVALID" to "统计桶格式不受支持", "USAGE_ACCESS_DENIED" to "系统拒绝统计访问", "NETWORK_QUERY_TIMEOUT" to "系统统计查询超时",
        "READY" to "采集服务已就绪", "APPOPS_PERMISSION_DENIED" to "系统拒绝读取操作模式", "TARGET_NOT_AVAILABLE_FOR_USER_0" to "当前主用户没有此组件", "SYSTEM_BUCKETS_NOT_PACKETS" to "系统统计桶，不是网络抓包")
    fun status(code:String)=states[code]?:if(code.startsWith("COMMAND_EXIT_")) "系统命令返回错误" else "状态待核查"
    fun source(code:String)=when(code) { "SHIZUKU_PS"->"进程快照";"SHIZUKU_APPOPS"->"系统操作模式";"SHIZUKU_BINDER"->"授权连接";"APP_LIFECYCLE"->"监测会话";"NETWORK_STATS_WIFI"->"Wi-Fi 系统统计";"NETWORK_STATS_MOBILE"->"移动网络系统统计";else->"其他来源" }
    fun target(pkg:String)=when(pkg) { "com.miui.securitycenter"->"手机管家";"com.miui.guardprovider"->"系统安全组件";"com.miui.powerkeeper"->"电量与性能";"monitor"->"哨兵自身";else->if(pkg.startsWith("uid:")) "共享应用身份统计" else pkg }
    fun evidence(row:Event):String=when {
        row.evidence.startsWith("OBSERVED_PIDS=")->"本次观察到进程："+row.evidence.substringAfter('=')+"。PID 变化不等于确认重启。"
        row.evidence=="NOT_OBSERVED_IN_PS"->"本次可见列表未出现匹配进程，不代表期间从未运行。"
        row.source=="SHIZUKU_APPOPS" && row.status in setOf("OK","PARTIAL")->appOps(row.evidence)
        else->status(row.type)+" · "+status(row.status)
    }
    fun appOps(raw:String):String {
        if(raw.startsWith("NO_RECORDED_OPERATIONS")) return "系统未返回操作记录；不代表从未发生访问。"+(if(raw.contains("REPORTED_DEFAULT_MODE=")) "系统还返回默认模式，这不是访问事件。" else "")
        val names=mapOf("CAMERA" to "相机","RECORD_AUDIO" to "麦克风","COARSE_LOCATION" to "大致位置","FINE_LOCATION" to "精确位置","READ_CLIPBOARD" to "读取剪贴板","READ_SMS" to "短信相关操作","READ_CALL_LOG" to "通话记录相关操作")
        val modes=mapOf("allow" to "允许（不代表实际访问）","ignore" to "忽略请求","deny" to "拒绝","errored" to "拒绝并报错","foreground" to "仅前台允许","default" to "使用系统默认规则","ask" to "需要询问")
        return raw.lineSequence().mapNotNull { line ->
            val match=Regex("(UID|UNSCOPED|PACKAGE):([A-Z0-9_]+)=(allow|ignore|deny|default|foreground|errored|ask)").matchEntire(line)?:return@mapNotNull null
            val op=match.groupValues[2];"${names[op]?:"其他系统操作"}：${modes[match.groupValues[3]]}；${if(match.groupValues[1]=="UID") "系统身份级记录" else "作用域未确认"}"
        }.joinToString("\n").ifEmpty { "仅有部分可读模式，详细字段可在技术详情查看。" }
    }
}
