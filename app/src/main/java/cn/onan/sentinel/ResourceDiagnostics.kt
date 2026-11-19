package cn.onan.sentinel

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class ProcField(val fileName:String) { STAT("stat"), STATUS("status") }
data class ProcText(val text:String?=null,val reason:String="OK")
data class ResourceMetric(val value:String?,val source:String,val reason:String="OK") {
    fun json()=JSONObject().put("value",value?:JSONObject.NULL).put("source",source).put("reason",reason)
}
data class ProcStat(val pid:Int,val name:String,val ppid:Long,val userTicks:Long,val systemTicks:Long,val threads:Long,val startTicks:Long)
data class ResourcePoint(val atNs:Long,val hz:Long,val fields:Map<String,ResourceMetric>) {
    fun json()=JSONObject().put("atNs",atNs).put("clockTicksPerSecond",if(hz>0) hz else JSONObject.NULL).put("clockTicksReason",if(hz>0) "OK" else "CLOCK_TICKS_UNAVAILABLE")
        .put("fields",JSONObject().apply { fields.forEach { (key,value)->put(key,value.json()) } })
}

object ResourceDiagnostics {
    const val PS="ps -A -w -o UID,PID,NAME"
    const val STAT="/proc/<PID>/stat"
    const val STATUS="/proc/<PID>/status"
    private val cpuSource="$STAT utime+stime; CLOCK_BOOTTIME elapsedRealtimeNanos; sysconf(_SC_CLK_TCK)"
    fun failure(reason:String)=JSONObject().put("status","UNAVAILABLE").put("reason",reason).put("samples",JSONArray())
    fun select(rows:List<VisibleProcess>,selfPid:Int):List<VisibleProcess> = listOfNotNull(
        rows.firstOrNull { it.pid==selfPid },
        Policy.targets.firstNotNullOfOrNull { pkg->rows.firstOrNull { it.uid in 0..99999 && (it.name==pkg || it.name.startsWith("$pkg:")) } },
        rows.firstOrNull { it.name=="system_server" },
        rows.firstOrNull { it.uid in 10000..89999 }
    ).distinctBy { it.pid }.take(4)

    fun parseStat(raw:String,pid:Int):ProcStat? = try {
        val open=raw.indexOf('(');val close=raw.lastIndexOf(')')
        require(open>0 && close>open && raw.substring(0,open).trim().toInt()==pid)
        val f=raw.substring(close+1).trim().split(Regex("\\s+"));require(f.size>=22 && f[0].length==1)
        fun number(i:Int)=f[i].toLong().also { require(it>=0) }
        val name=raw.substring(open+1,close).let { if(it.length in 1..64 && it.matches(Regex("[A-Za-z0-9_.:() -]+"))) it else "[名称已脱敏]" }
        ProcStat(pid,name,number(1),number(11),number(12),number(17),number(19))
    } catch(_:Exception) { null }

    fun statusMetric(raw:ProcText,key:String):ResourceMetric {
        val source="$STATUS:$key"
        if(raw.text==null) return ResourceMetric(null,source,raw.reason)
        val lines=raw.text.lineSequence().filter { it.startsWith("$key:") }.toList()
        if(lines.isEmpty()) return ResourceMetric(null,source,"FIELD_NOT_RETURNED")
        if(lines.size!=1) return ResourceMetric(null,source,"FORMAT_UNSUPPORTED")
        val match=when(key) {
            "Uid"->Regex("Uid:\\s*([0-9]+)\\s+[0-9]+\\s+[0-9]+\\s+[0-9]+\\s*")
            "VmRSS"->Regex("VmRSS:\\s*([0-9]+)\\s+kB\\s*")
            else->return ResourceMetric(null,source,"FIELD_NOT_ALLOWED")
        }.matchEntire(lines.single())?:return ResourceMetric(null,source,"FORMAT_UNSUPPORTED")
        val value=match.groupValues[1].toLongOrNull()?:return ResourceMetric(null,source,"FORMAT_UNSUPPORTED")
        return ResourceMetric(value.toString(),source)
    }

    fun point(row:VisibleProcess,hz:Long,read:(Int,ProcField)->ProcText,now:()->Long):ResourcePoint {
        val a=read(row.pid,ProcField.STAT);val firstAt=now()
        val status=read(row.pid,ProcField.STATUS)
        val b=read(row.pid,ProcField.STAT);val secondAt=now()
        val first=a.text?.let { parseStat(it,row.pid) };val second=b.text?.let { parseStat(it,row.pid) }
        val stat=second?:first
        val stable=first!=null && second!=null && first.startTicks==second.startTicks
        val reason=when {
            first!=null && second!=null && !stable->"IDENTITY_CHANGED_DURING_READ"
            a.text==null->a.reason
            b.text==null->b.reason
            !stable->"FORMAT_UNSUPPORTED"
            else->"OK"
        }
        val values=linkedMapOf<String,ResourceMetric>()
        values["pid"]=ResourceMetric(row.pid.toString(),PS+":PID（发现时）")
        values["discoveryUid"]=ResourceMetric(row.uid.toString(),PS+":UID（发现时）","DISCOVERY_ONLY")
        values["name"]=if(stat==null) ResourceMetric(row.name,PS+":NAME（发现时）","DISCOVERY_ONLY") else ResourceMetric(stat.name,STAT+":comm（可能截断）",reason)
        fun statMetric(value:Long?,field:String)=ResourceMetric(if(reason=="IDENTITY_CHANGED_DURING_READ") null else value?.toString(),"$STAT:$field",reason)
        values["ppid"]=statMetric(stat?.ppid,"ppid")
        values["userTicks"]=statMetric(stat?.userTicks,"utime")
        values["systemTicks"]=statMetric(stat?.systemTicks,"stime")
        values["startTicks"]=statMetric(stat?.startTicks,"starttime")
        values["threads"]=statMetric(stat?.threads,"num_threads")
        for((field,key) in listOf("uid" to "Uid","rssKiB" to "VmRSS")) {
            val metric=statusMetric(status,key)
            values[field]=if(metric.value!=null && !stable) metric.copy(reason="IDENTITY_UNVERIFIED") else metric
        }
        return ResourcePoint(if(second!=null) secondAt else firstAt,hz,values)
    }

    fun cpu(first:ResourcePoint?,second:ResourcePoint):ResourceMetric {
        fun fail(reason:String)=ResourceMetric(null,cpuSource,reason)
        if(first==null) return fail("FIRST_SAMPLE")
        fun value(p:ResourcePoint,key:String):Long?=p.fields[key]?.takeIf { it.reason=="OK" }?.value?.toLongOrNull()
        val required=listOf("pid","uid","startTicks","userTicks","systemTicks")
        if(required.any { value(first,it)==null || value(second,it)==null }) return fail("TWO_VALID_SAMPLES_REQUIRED")
        if(listOf("pid","uid","startTicks").any { value(first,it)!=value(second,it) }) return fail("PROCESS_IDENTITY_CHANGED")
        if(first.hz<=0 || first.hz!=second.hz) return fail("CLOCK_TICKS_UNAVAILABLE")
        if(second.atNs<=first.atNs) return fail("INVALID_INTERVAL")
        val du=value(second,"userTicks")!!-value(first,"userTicks")!!
        val ds=value(second,"systemTicks")!!-value(first,"systemTicks")!!
        if(du<0 || ds<0) return fail("COUNTER_REVERSED")
        // 100% = one logical CPU fully occupied; multi-threaded processes can exceed 100%.
        val percent=(du.toDouble()+ds.toDouble())/second.hz/((second.atNs-first.atNs)/1e9)*100.0
        return ResourceMetric(String.format(Locale.ROOT,"%.2f",percent),cpuSource)
    }

    fun collect(rows:List<VisibleProcess>,selfPid:Int,hz:Long,read:(Int,ProcField)->ProcText,now:()->Long,sleep:(Long)->Unit):JSONObject {
        val chosen=select(rows,selfPid)
        if(chosen.isEmpty()) return failure("NO_REPRESENTATIVE_VISIBLE")
        val first=chosen.map { point(it,hz,read,now) }
        sleep(3000)
        val second=chosen.map { point(it,hz,read,now) }
        return JSONObject().put("status","COMPLETED").put("version",BuildConfig.VERSION_NAME)
            .put("createdAt",System.currentTimeMillis()).put("clockSource","elapsedRealtimeNanos (includes suspend)")
            .put("scope","manual two-point diagnostic; max 4 visible representatives; RSS, not PSS; no database writes")
            .put("samples",JSONArray().apply { chosen.indices.forEach { i -> put(JSONObject().put("sample",i+1)
                .put("first",first[i].json().put("cpuPercent",cpu(null,first[i]).json()))
                .put("second",second[i].json().put("cpuPercent",cpu(first[i],second[i]).json()))) } })
    }

    /** Whitelist reconstruction: identities, starttime and arbitrary raw text cannot enter exports. */
    fun redacted(report:JSONObject):JSONObject {
        val out=JSONObject().put("version",BuildConfig.VERSION_NAME).put("status",report.optString("status"))
            .put("privacy","Names/PID/UID/PPID/starttime removed; time rounded to minute; resource values retained")
            .put("cpuConvention","100% = one logical CPU; two matching valid samples required; elapsed time includes suspend")
            .put("createdAtMinute",report.optLong("createdAt")/60000*60000)
        if(report.has("reason")) out.put("reason",report.getString("reason"))
        val samples=report.optJSONArray("samples")?:JSONArray()
        out.put("samples",JSONArray().apply { for(i in 0 until samples.length()) {
            val row=samples.getJSONObject(i);val exported=JSONObject().put("alias","PROCESS_${i+1}")
            val base=row.getJSONObject("first").getLong("atNs")
            for(phase in listOf("first","second")) {
                val point=row.getJSONObject(phase);val fields=point.getJSONObject("fields")
                val clean=JSONObject();for(key in listOf("userTicks","systemTicks","rssKiB","threads")) clean.put(key,fields.getJSONObject(key))
                // Keep per-metric capability/failure evidence even when the identity value is removed.
                for(key in listOf("name","pid","uid","discoveryUid","ppid","startTicks")) {
                    val metric=fields.getJSONObject(key);clean.put(key,JSONObject().put("value","[REDACTED]").put("source",metric.getString("source")).put("reason",metric.getString("reason")))
                }
                exported.put(phase,JSONObject().put("offsetMs",(point.getLong("atNs")-base)/1000000).put("clockTicksPerSecond",point.get("clockTicksPerSecond"))
                    .put("clockTicksReason",point.getString("clockTicksReason"))
                    .put("fields",clean).put("cpuPercent",point.getJSONObject("cpuPercent")))
            }
            put(exported)
        } })
        return out
    }
}

/** Fixed metadata files only. No shell, cmdline, environ, maps, fd, memory or smaps access. */
object ProcMetadata {
    fun read(pid:Int,field:ProcField):ProcText {
        if(pid<=0) return ProcText(reason="INVALID_PID")
        return try {
            val fd=android.system.Os.open("/proc/$pid/${field.fileName}",android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_CLOEXEC or android.system.OsConstants.O_NOFOLLOW,0)
            java.io.FileInputStream(fd).use { input ->
                val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(1024)
                while(true) { val n=input.read(buffer);if(n<0) break;if(bytes.size()+n>16384) return ProcText(reason="OUTPUT_LIMIT");bytes.write(buffer,0,n) }
                ProcText(bytes.toString("UTF-8"))
            }
        } catch(e:android.system.ErrnoException) {
            ProcText(reason=when(e.errno) { android.system.OsConstants.EACCES,android.system.OsConstants.EPERM->"ACCESS_DENIED";android.system.OsConstants.ENOENT,android.system.OsConstants.ESRCH->"PROCESS_EXITED_OR_NOT_VISIBLE";else->"READ_FAILED" })
        } catch(_:Exception) { ProcText(reason="READ_FAILED") }
    }
}
