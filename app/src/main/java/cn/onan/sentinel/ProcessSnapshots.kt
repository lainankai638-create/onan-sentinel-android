package cn.onan.sentinel

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

data class VisibleProcess(val uid: Int,val pid: Int,val name: String)
data class ProcessSnapshot(val status: String,val reason: String,val observedAt: Long,val rows: List<VisibleProcess>) {
    fun toJson(): String = JSONObject().put("status",status).put("evidence",reason).put("observedAt",observedAt)
        .put("rows",JSONArray().apply { rows.forEach { put(JSONObject().put("uid",it.uid).put("pid",it.pid).put("name",it.name)) } }).toString()
}
object ProcessSnapshots {
    fun parse(raw: String, at: Long = System.currentTimeMillis()): ProcessSnapshot {
        fun fail(reason:String)=ProcessSnapshot("UNSUPPORTED",reason,at,emptyList())
        if(raw.toByteArray().size>Policy.MAX_OUTPUT) return fail("OUTPUT_LIMIT_OR_READ_FAILURE")
        val lines=raw.lineSequence().filter { it.isNotBlank() }.toList()
        if(lines.isEmpty() || !Regex("\\s*UID\\s+PID\\s+NAME\\s*").matches(lines.first())) return fail("PS_FORMAT_UNKNOWN")
        if(lines.size>2001) return fail("OUTPUT_LIMIT_OR_READ_FAILURE")
        val rows=mutableListOf<VisibleProcess>();val pids=mutableSetOf<Int>()
        for(line in lines.drop(1)) {
            val c=line.trim().split(Regex("\\s+"),limit=3)
            if(c.size!=3) return fail("PS_FORMAT_UNKNOWN")
            val uid=c[0].toIntOrNull()?:return fail("PS_FORMAT_UNKNOWN")
            val pid=c[1].toIntOrNull()?:return fail("PS_FORMAT_UNKNOWN")
            if(uid<0 || pid<=0 || !pids.add(pid)) return fail("PS_FORMAT_UNKNOWN")
            // Only argv[0], never a command line, URL, account path or arbitrary untrusted text.
            val name=c[2].substringAfterLast('/')
            val safe=if(Regex("[A-Za-z0-9_.:-]{1,200}").matches(name)) name else "[名称已脱敏]"
            rows+=VisibleProcess(uid,pid,safe)
        }
        return ProcessSnapshot("OK","VISIBLE_SNAPSHOT_ONLY",at,rows)
    }
    fun fromJson(raw:String): ProcessSnapshot {
        require(raw.length<400000)
        val j=JSONObject(raw);val rows=j.optJSONArray("rows")?:JSONArray()
        require(rows.length()<=2000)
        return ProcessSnapshot(j.getString("status"),j.optString("evidence"),j.optLong("observedAt",System.currentTimeMillis()),
            (0 until rows.length()).map { i -> rows.getJSONObject(i).let { VisibleProcess(it.getInt("uid"),it.getInt("pid"),it.getString("name")) } })
    }
}
data class PackageIdentity(val packageName:String,val label:String,val uid:Int,val system:Boolean,val version:String="")
data class ProcessIdentity(val process:VisibleProcess,val candidates:List<PackageIdentity>,val category:String) {
    val shared get()=candidates.size>1
    val nameClue get()=candidates.firstOrNull { process.name==it.packageName || process.name.startsWith(it.packageName+":") }
}
object ProcessAttribution {
    fun resolve(row:VisibleProcess, packages:List<PackageIdentity>):ProcessIdentity {
        val candidates=packages.filter { it.uid==row.uid }.distinctBy { it.packageName }
        val category=when {
            candidates.isNotEmpty() && candidates.all { it.system } -> "system"
            candidates.isNotEmpty() && candidates.none { it.system } -> "third"
            candidates.isEmpty() && row.uid<10000 -> "system"
            else -> "unknown"
        }
        return ProcessIdentity(row,candidates,category)
    }
    fun packages(context:Context,uid:Int):List<PackageIdentity> {
        if(uid/100000!=android.os.Process.myUid()/100000) return emptyList()
        val pm=context.packageManager
        return (pm.getPackagesForUid(uid)?:emptyArray()).mapNotNull { name -> try {
            val app=pm.getApplicationInfo(name,0)
            if(app.uid!=uid) null else PackageIdentity(name,pm.getApplicationLabel(app).toString(),uid,app.flags and ApplicationInfo.FLAG_SYSTEM !=0,pm.getPackageInfo(name,0).versionName.orEmpty())
        } catch(_:Exception) { null } }
    }
}

/** One immutable observation per background batch, reused for all fixed targets. */
class ProcessBatch {
    private var snapshot:ProcessSnapshot?=null
    private var generation:Long?=null
    fun sample(token:Long,target:String,read:()->ProcessSnapshot):Evidence {
        if(generation!=token) { snapshot=null;generation=token }
        val value=snapshot?:read().also { snapshot=it }
        if(value.status!="OK") return Evidence(value.status,value.reason)
        val pids=value.rows.filter { it.uid in 0..99999 && (it.name==target || it.name.startsWith("$target:")) }.map { it.pid.toString() }.sorted()
        val normalized=if(pids.isEmpty()) "NOT_OBSERVED_IN_PS" else "OBSERVED_PIDS="+pids.joinToString(",")
        return if(normalized.length>Policy.MAX_EVIDENCE) Evidence("UNAVAILABLE","NORMALIZED_OUTPUT_LIMIT") else Evidence("OK",normalized)
    }
}
class SnapshotClient(context:Context) {
    private val bridge=ShizukuBridge(context,serviceClass=SnapshotUserService::class.java) { }
    private val control=CollectionControl()
    companion object { private val lane=BoundedCallLane() }
    fun start() { bridge.start();control.resume() }
    fun stop() { control.halt();bridge.close() }
    fun read():ProcessSnapshot {
        val token=control.token();val deadline=SystemClock.elapsedRealtime()+12000
        while(control.active(token)) {
            val state=bridge.connect()
            if(state=="READY") {
                val lease=bridge.lease()?:continue
                val raw=lane.call(7000) { lease.second.snapshot() }
                var result:ProcessSnapshot?=null
                control.accept(token) { bridge.accept(lease.first) { result=ProcessSnapshots.fromJson(raw) } }
                return result?:ProcessSnapshot("UNAVAILABLE","AUTHORIZATION_CHANGED",System.currentTimeMillis(),emptyList())
            }
            if(state !in setOf("USER_SERVICE_CONNECTING","USER_SERVICE_RETRY_BACKOFF") || SystemClock.elapsedRealtime()>deadline)
                return ProcessSnapshot("UNAVAILABLE",state,System.currentTimeMillis(),emptyList())
            Thread.sleep(100)
        }
        return ProcessSnapshot("UNAVAILABLE","USER_PAUSED",System.currentTimeMillis(),emptyList())
    }
}
