package cn.onan.sentinel

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager

data class TrafficBucket(val start:Long,val end:Long,val rx:Long,val tx:Long)
data class TrafficResult(val uid:Int,val source:String,val status:String,val reason:String,val buckets:Int=0,val rx:Long?=null,val tx:Long?=null,val first:Long?=null,val last:Long?=null) {
    fun evidence()=if(status=="OK") "BUCKETS=$buckets;RX=$rx;TX=$tx;START_MIN=${first!!/60000*60000};END_MIN=${last!!/60000*60000}" else reason
}
interface TrafficBackend {
    fun capability():String
    fun query(uid:Int,mobile:Boolean,start:Long,end:Long):List<TrafficBucket>
}
/** Public Android usage statistics. No VPN, sockets, subscriber identifiers or settings writes. */
class AndroidTrafficBackend(private val context:Context):TrafficBackend {
    override fun capability():String = try {
        if(context.getSystemService(NetworkStatsManager::class.java)==null) "NETWORK_STATS_UNSUPPORTED"
        else if(context.getSystemService(AppOpsManager::class.java).checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),context.packageName)!=AppOpsManager.MODE_ALLOWED) "USAGE_ACCESS_REQUIRED"
        else "READY"
    } catch(_:Exception) { "NETWORK_STATS_UNSUPPORTED" }
    @Suppress("DEPRECATION")
    override fun query(uid:Int,mobile:Boolean,start:Long,end:Long):List<TrafficBucket> {
        val manager=context.getSystemService(NetworkStatsManager::class.java)?:error("NETWORK_STATS_UNSUPPORTED")
        val stats=manager.queryDetailsForUid(if(mobile) ConnectivityManager.TYPE_MOBILE else ConnectivityManager.TYPE_WIFI,null,start,end,uid)?:error("NETWORK_STATS_UNSUPPORTED")
        return stats.use { buildList {
            val bucket=NetworkStats.Bucket()
            while(stats.hasNextBucket()) {
                check(!Thread.currentThread().isInterrupted);check(size<10000) { "NETWORK_BUCKET_LIMIT" }
                check(stats.getNextBucket(bucket)) { "NETWORK_BUCKET_INVALID" }
                add(TrafficBucket(bucket.startTimeStamp,bucket.endTimeStamp,bucket.rxBytes,bucket.txBytes))
            }
        } }
    }
}
class NetworkExperiment(private val backend:TrafficBackend) {
    private val control=CollectionControl()
    companion object { private val lane=BoundedCallLane() }
    fun enabled(value:Boolean) { if(value) control.resume() else control.halt() }
    fun capability()=backend.capability()
    fun ticket()=control.token()
    fun query(uids:List<Int>,start:Long,end:Long,token:Long=control.token()):List<TrafficResult> {
        require(start>=0 && end>start && end-start<=31L*86400000)
        if(!control.active(token)) return listOf(TrafficResult(-1,"NETWORK_STATS","UNAVAILABLE","EXPERIMENT_DISABLED"))
        val capability=backend.capability()
        if(capability!="READY") return listOf(TrafficResult(-1,"NETWORK_STATS","UNAVAILABLE",capability))
        val results=mutableListOf<TrafficResult>()
        for(uid in uids.filter { it>=0 }.distinct().take(4)) for(mobile in listOf(false,true)) {
            if(!control.active(token)) return emptyList()
            val source=if(mobile) "NETWORK_STATS_MOBILE" else "NETWORK_STATS_WIFI"
            val result=try { aggregate(uid,source,lane.call(6000) { backend.query(uid,mobile,start,end) }) }
                catch(e:Exception) { TrafficResult(uid,source,"UNAVAILABLE",when { e is java.util.concurrent.TimeoutException->"NETWORK_QUERY_TIMEOUT";generateSequence<Throwable>(e) { it.cause }.any { it is SecurityException }->"USAGE_ACCESS_DENIED";else->"NETWORK_STATS_UNSUPPORTED" }) }
            if(backend.capability()!="READY") return listOf(TrafficResult(-1,"NETWORK_STATS","UNAVAILABLE","USAGE_ACCESS_DENIED"))
            if(!control.accept(token) { results+=result }) return emptyList()
        }
        return if(control.active(token)) results else emptyList()
    }
    fun accept(token:Long,write:()->Unit):Boolean = control.accept(token,write)
    fun aggregate(uid:Int,source:String,buckets:List<TrafficBucket>):TrafficResult {
        if(buckets.isEmpty()) return TrafficResult(uid,source,"UNAVAILABLE","NO_BUCKETS")
        if(buckets.size>10000 || buckets.any { it.rx<0 || it.tx<0 || it.start<0 || it.end<it.start }) return TrafficResult(uid,source,"UNSUPPORTED","NETWORK_BUCKET_INVALID")
        return try { TrafficResult(uid,source,"OK","SYSTEM_BUCKETS_NOT_PACKETS",buckets.size,buckets.fold(0L) { n,b->Math.addExact(n,b.rx) },buckets.fold(0L) { n,b->Math.addExact(n,b.tx) },buckets.minOf { it.start },buckets.maxOf { it.end }) }
        catch(_:ArithmeticException) { TrafficResult(uid,source,"UNSUPPORTED","NETWORK_BUCKET_INVALID") }
    }
}
