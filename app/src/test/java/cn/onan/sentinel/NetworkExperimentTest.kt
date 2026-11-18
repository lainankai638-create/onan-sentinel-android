package cn.onan.sentinel

import org.junit.Assert.*
import org.junit.Test

class NetworkExperimentTest {
    private class Backend:TrafficBackend {
        var permission="READY";var calls=0;var rows=listOf(TrafficBucket(1,10,100,20));var hook:(()->Unit)?=null
        override fun capability()=permission
        override fun query(uid:Int,mobile:Boolean,start:Long,end:Long):List<TrafficBucket> { calls++;hook?.invoke();return rows }
    }
    @Test fun disabledByDefaultNeverQueries() { val b=Backend();val n=NetworkExperiment(b);assertEquals("EXPERIMENT_DISABLED",n.query(listOf(1),0,100).single().reason);assertEquals(0,b.calls) }
    @Test fun noUsagePermissionNeverQueries() { val b=Backend().apply { permission="USAGE_ACCESS_REQUIRED" };val n=NetworkExperiment(b);n.enabled(true);assertEquals("USAGE_ACCESS_REQUIRED",n.query(listOf(1),0,100).single().reason);assertEquals(0,b.calls) }
    @Test fun sharedUidIsQueriedOncePerTransport() { val b=Backend();val n=NetworkExperiment(b);n.enabled(true);assertEquals(2,n.query(listOf(1000,1000),0,100).size);assertEquals(2,b.calls) }
    @Test fun emptyBucketsAreUnavailableNotZero() { val n=NetworkExperiment(Backend());val r=n.aggregate(1,"NETWORK_STATS_WIFI",emptyList());assertEquals("NO_BUCKETS",r.reason);assertNull(r.rx) }
    @Test fun realZeroBucketIsDifferentFromNoBuckets() { val r=NetworkExperiment(Backend()).aggregate(1,"NETWORK_STATS_WIFI",listOf(TrafficBucket(0,10,0,0)));assertEquals("OK",r.status);assertEquals(0L,r.rx) }
    @Test fun negativeOrOverflowCountersAreNotTraffic() { val n=NetworkExperiment(Backend());assertEquals("UNSUPPORTED",n.aggregate(1,"x",listOf(TrafficBucket(0,1,-1,0))).status);assertEquals("UNSUPPORTED",n.aggregate(1,"x",listOf(TrafficBucket(0,1,Long.MAX_VALUE,0),TrafficBucket(1,2,1,0))).status) }
    @Test fun actualBucketBoundsAreRetainedNotInterpolated() { val r=NetworkExperiment(Backend()).aggregate(1,"x",listOf(TrafficBucket(0,1000,2,3),TrafficBucket(1000,2000,4,5)));assertEquals(6L,r.rx);assertEquals(8L,r.tx);assertEquals(0L,r.first);assertEquals(2000L,r.last) }
    @Test fun disablingDuringQueryDropsLateResults() { val b=Backend();val n=NetworkExperiment(b);n.enabled(true);b.hook={ n.enabled(false) };assertTrue(n.query(listOf(1),0,100).isEmpty()) }
    @Test fun reenableDoesNotAcceptEarlierTicket() { val n=NetworkExperiment(Backend());n.enabled(true);val token=n.ticket();n.enabled(false);n.enabled(true);assertFalse(n.accept(token) { fail("stale result accepted") }) }
    @Test fun revokedPermissionDropsEarlierBuckets() { val b=Backend();val n=NetworkExperiment(b);n.enabled(true);b.hook={b.permission="USAGE_ACCESS_REQUIRED"};assertEquals("USAGE_ACCESS_DENIED",n.query(listOf(1),0,100).single().reason) }
    @Test fun securityExceptionIsExplicit() { val b=Backend();val n=NetworkExperiment(b);n.enabled(true);b.hook={throw SecurityException("private system message")};assertTrue(n.query(listOf(1),0,100).all { it.reason=="USAGE_ACCESS_DENIED" }) }
    @Test fun boundedQueryRange() { val n=NetworkExperiment(Backend());assertThrows(IllegalArgumentException::class.java) { n.query(listOf(1),0,32L*86400000) } }
}
