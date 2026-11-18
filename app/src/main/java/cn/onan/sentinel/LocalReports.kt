package cn.onan.sentinel

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ReportSummary(val events:Int,val valid:Int,val changes:Int,val maxInterval:Long?,val closedSessionMs:Long,val gaps:Int,val longestGap:Long,val uncertainClock:Boolean,val markdown:String)
object LocalReports {
    private val sampleTypes=setOf("SAMPLE","BASELINE","STATE_CHANGED")
    private fun alias(target:String)=Policy.targets.indexOf(target).let { if(it>=0) "目标${it+1}" else if(target.startsWith("uid:")) "身份组（已脱敏）" else "监测自身或其他" }
    private fun code(raw:String)=if(Regex("[A-Z][A-Z0-9_]{0,79}").matches(raw)) raw else "REDACTED"
    fun evidence(row:Event):String = when {
        row.source=="SHIZUKU_PS" && row.evidence.matches(Regex("OBSERVED_PIDS=[0-9,]+")) -> "OBSERVED_PIDS=[REDACTED]"
        row.source=="SHIZUKU_PS" && row.evidence=="NOT_OBSERVED_IN_PS" -> row.evidence
        row.source=="SHIZUKU_APPOPS" -> row.evidence.lineSequence().filter { it=="NO_RECORDED_OPERATIONS" || it.matches(Regex("REPORTED_DEFAULT_MODE=(allow|ignore|deny|default|foreground|errored|ask)")) || it.matches(Regex("(UID|UNSCOPED|PACKAGE):[A-Z0-9_]{1,80}=(allow|ignore|deny|default|foreground|errored|ask)")) }.joinToString("\n").ifEmpty { "[非白名单字段已移除]" }
        row.source.startsWith("NETWORK_STATS_") -> row.evidence.takeIf { it.matches(Regex("BUCKETS=[0-9]+;RX=[0-9]+;TX=[0-9]+;START_MIN=[0-9]+;END_MIN=[0-9]+")) }?:"[非白名单字段已移除]"
        else -> "[仅保留事件类型与状态；原始文本已移除]"
    }
    private inline fun each(store:EventStore,filter:EvidenceFilter,read:(Event)->Unit) {
        val (where,args)=filter.where()
        store.readableDatabase.rawQuery("SELECT id,timestamp,target,type,source,status,evidence FROM events WHERE $where ORDER BY id",args).use { c ->
            while(c.moveToNext()) { check(!Thread.currentThread().isInterrupted);read(Event(c.getLong(0),c.getLong(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getString(6))) }
        }
    }
    fun summary(store:EventStore,filter:EvidenceFilter):ReportSummary = synchronized(store) {
        var total=0;var valid=0;var changes=0;var maximum:Long?=null;var uncertain=false
        val previous=mutableMapOf<Pair<String,String>,Long>()
        val perSourceMaximum=mutableMapOf<Pair<String,String>,Long>()
        each(store,filter) { row -> total++
            if(row.type in sampleTypes && row.source in setOf("SHIZUKU_PS","SHIZUKU_APPOPS") && row.status in setOf("OK","PARTIAL")) {
                valid++;val key=row.source to row.target;previous.put(key,row.time)?.let { last-> if(row.time<last) uncertain=true else { maximum=maxOf(maximum?:0,row.time-last);perSourceMaximum[key]=maxOf(perSourceMaximum[key]?:0,row.time-last) } }
            }
            if(row.type=="STATE_CHANGED" && row.source=="SHIZUKU_PS") changes++
        }
        // Session duration is global metadata, never inferred from app evidence or an unclosed session.
        var start:Long?=null;var duration=0L
        store.readableDatabase.rawQuery("SELECT timestamp,type FROM events WHERE type IN ('SESSION_START','SESSION_STOP','UNCLEAN_STOP_DETECTED') ORDER BY id",null).use { c -> while(c.moveToNext()) {
            val at=c.getLong(0)
            when(c.getString(1)) { "SESSION_START"->start=at;"UNCLEAN_STOP_DETECTED"->start=null;else->{ start?.let { s->if(at<s) uncertain=true else duration+=maxOf(0,minOf(at,filter.end)-maxOf(s,filter.start)) };start=null } }
        } }
        var gaps=0;var longest=0L
        val (gapWhere,gapArgs)=gapFilter(filter)
        store.readableDatabase.rawQuery("SELECT start_at,end_at FROM source_gaps WHERE $gapWhere",gapArgs).use { c -> while(c.moveToNext()) {
            gaps++;val end=if(c.isNull(1)) minOf(filter.end,System.currentTimeMillis()) else minOf(c.getLong(1),filter.end)
            val begin=maxOf(c.getLong(0),filter.start);if(end<begin) uncertain=true else longest=maxOf(longest,end-begin)
        } }
        val date=java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.CHINA)
        val range=if(filter.start==0L) "最早保留记录至 ${date.format(java.util.Date(filter.end))}" else "${date.format(java.util.Date(filter.start))} 至 ${date.format(java.util.Date(filter.end))}"
        val markdown="""
            # 欧南 · 本地证据概览
            时间范围：$range（本机时区；含开始、不含结束）
            应用：${filter.target?.let { alias(it) }?:"全部保留目标"}；数据源：${filter.source?.let { Chinese.source(it) }?:"全部"}
            - 保留事件：$total 条；有效采样：$valid 次（按组件和来源分别计数）
            - 已闭合监测会话：${duration/1000} 秒（全局，包含暂停；未闭合或异常停止不估算）
            - 最长相邻有效采样间隔：${maximum?.let { "${it/1000} 秒" }?:"无法计算：不足两次同来源同组件采样"}
            - 进程观察变化：$changes 次（不是确认重启次数）
            - 来源空白期：$gaps 段；最长范围交集：${longest/1000} 秒（可含未知中断时刻的保守边界）
            - 时间倒退：${if(uncertain) "发现，时间统计不可靠" else "在所检查的记录中未发现"}

            各来源最长有效采样间隔：
            ${previous.keys.joinToString("\n") { key->"- ${alias(key.second)} / ${Chinese.source(key.first)}："+(perSourceMaximum[key]?.let { "${it/1000} 秒" }?:"不足两次，无法计算") }.ifEmpty { "无有效采样记录" }}

            仅统计当前保留历史。容量淘汰、清理和未采集无法还原；没有记录不能解释为没有行为。
            AppOps allow 是允许模式，不是实际访问。进程快照不是连续监控；网络统计桶不是抓包，VPN 下无法据此确认最终归属或外传。重复网络查询可能重叠或被系统修订，不相加为总流量。
            导出已去掉包名、PID、UID及任意原始文本；时间取整到分钟。时间线仍可能敏感。精确秒数仅为汇总统计。
        """.trimIndent()
        ReportSummary(total,valid,changes,maximum,duration,gaps,longest,uncertain,markdown)
    }
    private fun gapFilter(filter:EvidenceFilter):Pair<String,Array<String>> {
        val conditions=mutableListOf("start_at<?","(end_at IS NULL OR end_at>?)")
        val args=mutableListOf(filter.end.toString(),filter.start.toString())
        filter.target?.let { conditions+="target=?";args+=it };filter.source?.let { if(it=="NETWORK_STATS") conditions+="source IN ('NETWORK_STATS_WIFI','NETWORK_STATS_MOBILE')" else { conditions+="source=?";args+=it } }
        return conditions.joinToString(" AND ") to args.toTypedArray()
    }
    /** Private ZIP is completed under the store lock; external/slow providers never hold that lock. */
    fun export(context:Context,store:EventStore,filter:EvidenceFilter,save:(File)->Unit) {
        check(context.cacheDir.usableSpace>=Policy.DB_BYTES*2) { "EXPORT_SPACE_REQUIRED" }
        val file=File.createTempFile("sentinel-redacted-",".zip",context.cacheDir)
        try {
            synchronized(store) { ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("概览.md"));zip.write(summary(store,filter).markdown.toByteArray(Charsets.UTF_8));zip.closeEntry()
                zip.putNextEntry(ZipEntry("明细.jsonl"))
                fun line(j:JSONObject) { zip.write((j.toString()+"\n").toByteArray(Charsets.UTF_8)) }
                line(JSONObject().put("schema",2).put("app",BuildConfig.VERSION_NAME).put("parserVersion","v030-visible-1").put("scope","retained filtered evidence; sampled visibility and modes, not access events").put("timeRoundingMs",60000).put("startMinute",filter.start/60000*60000).put("endMinute",filter.end/60000*60000))
                val groups=mutableMapOf<String,String>()
                each(store,filter) { row -> line(JSONObject().put("eventId",row.id).put("timeMinute",row.time/60000*60000).put("target",if(row.target.startsWith("uid:")) groups.getOrPut(row.target) { "身份组${groups.size+1}" } else alias(row.target)).put("source",code(row.source)).put("type",code(row.type)).put("status",code(row.status)).put("scope",if(row.source.startsWith("NETWORK_STATS_")) "UID bucket aggregate; may overlap earlier query" else "sampled visibility or mode; not actual access").put("evidence",evidence(row))) }
                val (where,args)=gapFilter(filter)
                store.readableDatabase.rawQuery("SELECT source,target,start_at,end_at,start_kind FROM source_gaps WHERE $where ORDER BY id",args).use { c -> while(c.moveToNext()) line(JSONObject().put("type","SOURCE_GAP").put("source",code(c.getString(0))).put("target",alias(c.getString(1))).put("startMinute",maxOf(c.getLong(2),filter.start)/60000*60000).put("endMinute",if(c.isNull(3)) JSONObject.NULL else minOf(c.getLong(3),filter.end)/60000*60000).put("startKind",code(c.getString(4)))) }
                zip.closeEntry()
            } }
            save(file)
        } finally { file.delete() }
    }
}
