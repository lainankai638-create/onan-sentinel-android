package cn.onan.sentinel

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.zip.ZipFile
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class VisibleFeaturesTest {
    @Test fun largeTargetPidSetIsUnavailableRatherThanSilentlyTruncated() {
        val result=ProcessBatch().sample(1,Policy.targets[0]) {
            ProcessSnapshot("OK","VISIBLE_SNAPSHOT_ONLY",1,(10000..11000).map { VisibleProcess(10001,it,Policy.targets[0]) })
        }
        assertEquals(Evidence("UNAVAILABLE","NORMALIZED_OUTPUT_LIMIT"),result)
    }
    @Test fun oneBatchReusesOneObservationAndExcludesOtherUsers() {
        val batch=ProcessBatch();var calls=0
        fun read():ProcessSnapshot { calls++;return ProcessSnapshots.parse("UID PID NAME\n10123 1 com.miui.securitycenter:remote\n1010123 2 com.miui.securitycenter\n1000 3 com.miui.guardprovider") }
        assertEquals("OBSERVED_PIDS=1",batch.sample(1,Policy.targets[0],::read).value)
        assertEquals("OBSERVED_PIDS=3",batch.sample(1,Policy.targets[1],::read).value)
        assertEquals("NOT_OBSERVED_IN_PS",batch.sample(1,Policy.targets[2],::read).value);assertEquals(1,calls)
        batch.sample(2,Policy.targets[0],::read);assertEquals(2,calls)
    }
    @Test fun failedBatchNeverBecomesSuccessfulAbsence() {
        val batch=ProcessBatch();var calls=0
        repeat(3) { assertEquals("UNSUPPORTED",batch.sample(1,Policy.targets[it]) { calls++;ProcessSnapshots.parse("truncated") }.status) };assertEquals(1,calls)
    }
    @Test fun boundedLaneSupportsImmediateSequentialCompletionAndFailure() {
        val lane=BoundedCallLane();repeat(100) { assertEquals(it,lane.call(1000) { it }) }
        repeat(20) { assertThrows(java.util.concurrent.ExecutionException::class.java) { lane.call(1000) { throw SecurityException() } };assertEquals(1,lane.call(1000) { 1 }) }
    }
    @Test fun numericAndroid14SnapshotKeepsOtherUsersAndNativeProcesses() {
        val s=ProcessSnapshots.parse("  UID   PID NAME\n0 1 /system/bin/init\n10123 44 cn.demo:worker\n1010123 55 cn.demo\n",42)
        assertEquals("OK",s.status);assertEquals(listOf(0,10123,1010123),s.rows.map { it.uid });assertEquals("init",s.rows[0].name);assertEquals(42,s.observedAt)
    }
    @Test fun userColumnVendorVariantFailsClosed() { assertEquals("PS_FORMAT_UNKNOWN",ProcessSnapshots.parse("USER PID NAME\nu0_a123 44 cn.demo").reason) }
    @Test fun truncatedDuplicateAndNegativePidsAreNotSuccessfulSnapshots() {
        for(raw in listOf("UID PID NAME\n123 44","UID PID NAME\n123 -1 x","UID PID NAME\n123 44 x\n124 44 y")) assertEquals("UNSUPPORTED",ProcessSnapshots.parse(raw).status)
    }
    @Test fun snapshotOutputIsBounded() { assertEquals("OUTPUT_LIMIT_OR_READ_FAILURE",ProcessSnapshots.parse("x".repeat(65537)).reason) }
    @Test fun arbitraryCommandLineAndUrlAreNotDisplayedAsAppNames() {
        val s=ProcessSnapshots.parse("UID PID NAME\n123 44 account@example.com --secret\n124 45 /system/bin/tool")
        assertEquals("[名称已脱敏]",s.rows[0].name);assertEquals("tool",s.rows[1].name)
    }
    @Test fun sharedUidKeepsAllCandidatesDespiteNameMatch() {
        val p=ProcessAttribution.resolve(VisibleProcess(1000,2,"a"),listOf(PackageIdentity("a","甲",1000,true),PackageIdentity("b","乙",1000,true)))
        assertTrue(p.shared);assertEquals(2,p.candidates.size);assertEquals("a",p.nameClue!!.packageName);assertEquals("system",p.category)
    }
    @Test fun mixedSharedUidAndIsolatedUidAreUnknown() {
        assertEquals("unknown",ProcessAttribution.resolve(VisibleProcess(10001,1,"x"),listOf(PackageIdentity("a","",10001,true),PackageIdentity("b","",10001,false))).category)
        assertEquals("unknown",ProcessAttribution.resolve(VisibleProcess(99001,2,"isolated"),emptyList()).category)
    }
    @Test fun mismatchedUidDoesNotGetAttributed() { assertTrue(ProcessAttribution.resolve(VisibleProcess(12345,2,"a"),listOf(PackageIdentity("a","",12346,false))).candidates.isEmpty()) }
    @Test fun thirdPartyClassificationUsesPackageFlags() { assertEquals("third",ProcessAttribution.resolve(VisibleProcess(12345,2,"a"),listOf(PackageIdentity("a","",12345,false))).category) }
    @Test fun snapshotJsonRoundTripAndErrorWithoutRows() {
        val s=ProcessSnapshots.parse("UID PID NAME\n1000 12 system_server",99);assertEquals(s,ProcessSnapshots.fromJson(s.toJson()))
        assertTrue(ProcessSnapshots.fromJson("{\"status\":\"DENIED\",\"evidence\":\"SHIZUKU_ADB_SHELL_REQUIRED\"}").rows.isEmpty())
    }
    @Test fun chineseAllowNeverClaimsAccess() { assertTrue(Chinese.appOps("UNSCOPED:CAMERA=allow").contains("不代表实际访问")) }
    @Test fun emptyOperationsAndDefaultRemainAmbiguousInChinese() { assertTrue(Chinese.appOps(Parsers.appOps("No operations.\nDefault mode: allow").value).contains("不是访问事件")) }
    @Test fun timeFilterIsHalfOpenAndParameterized() { EventStore(RuntimeEnvironment.getApplication()).use { s ->
        s.add("SAMPLE","OK","x",Policy.targets[0],"SHIZUKU_PS",100);s.add("SAMPLE","OK","x",Policy.targets[0],"SHIZUKU_PS",200)
        assertEquals(1,s.events(EvidenceFilter(100,200)).size);assertTrue(s.events(EvidenceFilter(0,300,"' OR 1=1 --")).isEmpty())
    } }
    @Test fun keysetPagingDoesNotRepeatRows() { EventStore(RuntimeEnvironment.getApplication()).use { s ->
        repeat(5) { s.add("SAMPLE","OK","x",time=100L+it) };val a=s.events(EvidenceFilter(0,1000),2);val b=s.events(EvidenceFilter(0,1000),2,a.last().id)
        assertEquals(4,(a+b).map { it.id }.distinct().size)
    } }
    @Test fun reportCountsSourceAttemptsAndIntervalsWithoutMixingSources() { EventStore(RuntimeEnvironment.getApplication()).use { s ->
        s.add("BASELINE","OK","OBSERVED_PIDS=5",Policy.targets[0],"SHIZUKU_PS",100)
        s.add("SAMPLE","PARTIAL","UNSCOPED:CAMERA=allow",Policy.targets[0],"SHIZUKU_APPOPS",150)
        s.add("STATE_CHANGED","OK","NOT_OBSERVED_IN_PS",Policy.targets[0],"SHIZUKU_PS",500)
        s.add("SAMPLE_UNAVAILABLE","UNAVAILABLE","x",Policy.targets[0],"SHIZUKU_PS",600)
        val r=LocalReports.summary(s,EvidenceFilter(0,1000));assertEquals(3,r.valid);assertEquals(400L,r.maxInterval);assertEquals(1,r.changes)
    } }
    @Test fun reportOpenSessionsNeverInventUptime() { EventStore(RuntimeEnvironment.getApplication()).use { s ->
        s.add("SESSION_START","OK","",time=100);assertEquals(0,LocalReports.summary(s,EvidenceFilter(0,1000)).closedSessionMs)
        s.add("SESSION_STOP","UNAVAILABLE","",time=500);assertEquals(200,LocalReports.summary(s,EvidenceFilter(200,400)).closedSessionMs)
    } }
    @Test fun reportClockReversalIsDisclosed() { EventStore(RuntimeEnvironment.getApplication()).use { s ->
        s.add("SAMPLE","OK","",Policy.targets[0],"SHIZUKU_PS",500);s.add("SAMPLE","OK","",Policy.targets[0],"SHIZUKU_PS",100)
        assertTrue(LocalReports.summary(s,EvidenceFilter(0,1000)).uncertainClock)
    } }
    @Test fun reportGapsAreClippedToRangeAndFiltered() { EventStore(RuntimeEnvironment.getApplication()).use { s ->
        s.sourceUnavailable("SHIZUKU_PS",Policy.targets[0],"USER_PAUSED",100,"KNOWN_STOP")
        val r=LocalReports.summary(s,EvidenceFilter(200,400,Policy.targets[0],"SHIZUKU_PS"));assertEquals(1,r.gaps);assertEquals(200,r.longestGap)
        assertEquals(0,LocalReports.summary(s,EvidenceFilter(200,400,Policy.targets[1])).gaps)
    } }
    @Test fun reportBundleRedactsArbitraryEvidenceAndIsValidJsonl() { val context=RuntimeEnvironment.getApplication();EventStore(context).use { s ->
        s.add("SAMPLE","OK","OBSERVED_PIDS=98765",Policy.targets[0],"SHIZUKU_PS",1234567)
        s.add("LINK_STATE","OK","private@example.com\n\"私人文本\"",time=1234568)
        LocalReports.export(context,s,EvidenceFilter(0,2000000)) { f -> ZipFile(f).use { z ->
            val text=z.getInputStream(z.getEntry("明细.jsonl")).bufferedReader().readText();text.lineSequence().filter { it.isNotBlank() }.forEach { JSONObject(it) }
            assertFalse(text.contains("98765"));assertFalse(text.contains("private@"));assertFalse(text.contains("com.miui"));assertFalse(text.contains("1234567"));assertTrue(z.getEntry("概览.md")!=null)
        } };assertTrue(context.cacheDir.listFiles()!!.none { it.name.startsWith("sentinel-redacted-") })
    } }
    @Test fun reportSaveFailureCleansPrivateSpoolWithoutMutatingHistory() { val context=RuntimeEnvironment.getApplication();EventStore(context).use { s ->
        s.add("SAMPLE","OK","x");assertThrows(IllegalStateException::class.java) { LocalReports.export(context,s,EvidenceFilter(0,Long.MAX_VALUE)) { error("provider failure") } }
        assertEquals(1,s.latest().size);assertTrue(context.cacheDir.listFiles()!!.none { it.name.startsWith("sentinel-redacted-") })
    } }
}
