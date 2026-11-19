package cn.onan.sentinel

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=android.app.Application::class)
class ResourceDiagnosticsTest {
    private val row=VisibleProcess(82345,4321,"com.example.visible")
    private fun stat(pid:Int=4321,u:Long=100,s:Long=50,start:Long=987654321,comm:String="worker (io)"):String {
        val f=MutableList(50) { "0" };f[0]="S";f[1]="32123";f[11]=u.toString();f[12]=s.toString();f[17]="6";f[19]=start.toString();f[21]="100"
        return "$pid ($comm) "+f.joinToString(" ")+"\n"
    }
    private fun point(at:Long=1000000000,u:Long=100,s:Long=50,start:Long=987654321,uid:Int=82345,hz:Long=100):ResourcePoint =
        ResourceDiagnostics.point(row,hz,{_,file->ProcText(if(file==ProcField.STAT) stat(u=u,s=s,start=start) else "Uid:\t$uid\t$uid\t$uid\t$uid\nVmRSS:\t4096 kB\nName: PRIVATE_CONTENT_NOT_A_METRIC\n")},{at})
    @Test fun statWithSpacesAndParenthesesUsesCorrectKernelFieldOffsets() {
        val s=ResourceDiagnostics.parseStat(stat(),4321)!!
        assertEquals("worker (io)",s.name);assertEquals(32123,s.ppid);assertEquals(100,s.userTicks);assertEquals(50,s.systemTicks);assertEquals(6,s.threads);assertEquals(987654321,s.startTicks)
    }
    @Test fun truncatedNegativeAndWrongPidStatFailClosed() {
        assertNull(ResourceDiagnostics.parseStat("4321 (worker) S 0 0",4321))
        assertNull(ResourceDiagnostics.parseStat(stat(u=-1),4321))
        assertNull(ResourceDiagnostics.parseStat(stat(pid=4322),4321))
    }
    @Test fun rssIsStatusKiBNotStatPagesOrPss() {
        val p=point();assertEquals("4096",p.fields.getValue("rssKiB").value)
        assertEquals("/proc/<PID>/status:VmRSS",p.fields.getValue("rssKiB").source)
        assertFalse(p.fields.containsKey("pss"))
    }
    @Test fun missingRssAndUnexpectedUnitsAreDifferentFromRealZero() {
        assertEquals("FIELD_NOT_RETURNED",ResourceDiagnostics.statusMetric(ProcText("Uid: 1 1 1 1"),"VmRSS").reason)
        assertEquals("FORMAT_UNSUPPORTED",ResourceDiagnostics.statusMetric(ProcText("VmRSS: 4 MB"),"VmRSS").reason)
        assertEquals("0",ResourceDiagnostics.statusMetric(ProcText("VmRSS: 0 kB"),"VmRSS").value)
        assertEquals("FORMAT_UNSUPPORTED",ResourceDiagnostics.statusMetric(ProcText("VmRSS: 1 kB\nVmRSS: 2 kB"),"VmRSS").reason)
    }
    @Test fun firstCpuSampleIsUnavailableNeverZero() {
        val m=ResourceDiagnostics.cpu(null,point());assertNull(m.value);assertEquals("FIRST_SAMPLE",m.reason)
    }
    @Test fun twoValidSamplesUseTicksHzAndActualElapsedInterval() {
        val m=ResourceDiagnostics.cpu(point(),point(at=3000000000,u=200,s=100))
        assertEquals("75.00",m.value);assertEquals("OK",m.reason)
    }
    @Test fun validUnchangedCountersCanReportZeroAndMulticoreCanExceed100() {
        assertEquals("0.00",ResourceDiagnostics.cpu(point(),point(at=2000000000)).value)
        assertEquals("250.00",ResourceDiagnostics.cpu(point(),point(at=2000000000,u=350)).value)
    }
    @Test fun reusedPidOrChangedUidNeverCombinesCpuCounters() {
        assertEquals("PROCESS_IDENTITY_CHANGED",ResourceDiagnostics.cpu(point(),point(at=2000000000,start=987654322)).reason)
        assertEquals("PROCESS_IDENTITY_CHANGED",ResourceDiagnostics.cpu(point(),point(at=2000000000,uid=82346)).reason)
    }
    @Test fun invalidIntervalHzAndCounterReversalAreExplicit() {
        assertEquals("INVALID_INTERVAL",ResourceDiagnostics.cpu(point(),point()).reason)
        assertEquals("CLOCK_TICKS_UNAVAILABLE",ResourceDiagnostics.cpu(point(hz=-1),point(at=2000000000,hz=-1)).reason)
        assertEquals("CLOCK_TICKS_UNAVAILABLE",ResourceDiagnostics.cpu(point(),point(at=2000000000,hz=250)).reason)
        assertEquals("COUNTER_REVERSED",ResourceDiagnostics.cpu(point(),point(at=2000000000,u=99)).reason)
    }
    @Test fun inaccessibleMetadataRetainsDiscoveryAndFailureNotInventedNumbers() {
        val p=ResourceDiagnostics.point(row,100,{_,_->ProcText(reason="ACCESS_DENIED")},{1000000000})
        assertEquals("com.example.visible",p.fields.getValue("name").value)
        assertEquals("82345",p.fields.getValue("discoveryUid").value)
        assertNull(p.fields.getValue("rssKiB").value);assertEquals("ACCESS_DENIED",p.fields.getValue("userTicks").reason)
        assertEquals("TWO_VALID_SAMPLES_REQUIRED",ResourceDiagnostics.cpu(p,p.copy(atNs=2000000000)).reason)
    }
    @Test fun readableStatusWithoutStatIsReportedButIdentityUnverified() {
        val p=ResourceDiagnostics.point(row,100,{_,file->if(file==ProcField.STAT) ProcText(reason="ACCESS_DENIED") else ProcText("Uid: 82345 82345 82345 82345\nVmRSS: 4096 kB")},{1000})
        assertEquals("4096",p.fields.getValue("rssKiB").value);assertEquals("IDENTITY_UNVERIFIED",p.fields.getValue("uid").reason)
    }
    @Test fun pidReusedInsideOneReadPreventsCpuIdentityVerification() {
        var reads=0
        val p=ResourceDiagnostics.point(row,100,{_,file->if(file==ProcField.STAT) ProcText(stat(start=if(reads++==0) 1 else 2)) else ProcText("Uid: 1 1 1 1\nVmRSS: 2 kB")},{1000})
        assertEquals("IDENTITY_CHANGED_DURING_READ",p.fields.getValue("startTicks").reason);assertNull(p.fields.getValue("startTicks").value)
        assertEquals("IDENTITY_UNVERIFIED",p.fields.getValue("rssKiB").reason)
    }
    @Test fun representativesAreBoundedDistinctAndUnavailableRolesNotFabricated() {
        val rows=listOf(VisibleProcess(2000,7,"resource"),VisibleProcess(1000,8,"system_server"),VisibleProcess(1000,9,Policy.targets[0]))+(10..100).map { VisibleProcess(10123,it,"third") }
        assertEquals(listOf(7,9,8,10),ResourceDiagnostics.select(rows,7).map { it.pid })
        assertTrue(ResourceDiagnostics.select(listOf(VisibleProcess(0,1,"init")),7).isEmpty())
        assertEquals(1,ResourceDiagnostics.select(listOf(VisibleProcess(1000,8,"system_server")),8).size)
    }
    @Test fun oneManualRunOnlyReadsTwoRoundsAndWaitsOnce() {
        val rows=listOf(VisibleProcess(2000,7,"resource"),VisibleProcess(1000,8,"system_server"),VisibleProcess(1000,9,Policy.targets[0]),VisibleProcess(10123,10,"third"))
        var reads=0;var waits=0;var now=1000000000L
        val r=ResourceDiagnostics.collect(rows,7,100,{pid,file->reads++;ProcText(if(file==ProcField.STAT) stat(pid=pid) else "Uid: 2000 2000 2000 2000\nVmRSS: 1 kB")},{++now},{ ms->assertEquals(3000,ms);waits++;now+=ms*1000000 })
        assertEquals(24,reads);assertEquals(1,waits);assertEquals(4,r.getJSONArray("samples").length())
    }
    @Test fun exportRedactsIdentityButPreservesResourceNumbersAndReasons() {
        val a=point();val b=point(at=4000000000,u=250)
        val report=JSONObject().put("status","COMPLETED").put("createdAt",123456789).put("samples",org.json.JSONArray().put(JSONObject().put("first",a.json().put("cpuPercent",ResourceDiagnostics.cpu(null,a).json())).put("second",b.json().put("cpuPercent",ResourceDiagnostics.cpu(a,b).json()))))
        val exported=ResourceDiagnostics.redacted(report);val text=exported.toString()
        for(secret in listOf("worker (io)","82345","32123","987654321","PRIVATE_CONTENT","123456789")) assertFalse(secret,text.contains(secret))
        assertEquals("4096",exported.getJSONArray("samples").getJSONObject(0).getJSONObject("first").getJSONObject("fields").getJSONObject("rssKiB").getString("value"))
        assertTrue(text.contains("FIRST_SAMPLE"));assertTrue(text.contains("PROCESS_1"));assertEquals(123420000,exported.getLong("createdAtMinute"))
    }
    @Test fun failureExportRemainsUnavailableAndDoesNotInventSamples() {
        val j=ResourceDiagnostics.redacted(ResourceDiagnostics.failure("SHIZUKU_NOT_RUNNING"))
        assertEquals(0,j.getJSONArray("samples").length());assertEquals("SHIZUKU_NOT_RUNNING",j.getString("reason"))
    }
    @Test fun noNewShellCommandsOrPathInputsAndOnlyMetadataFiles() {
        assertEquals(listOf("stat","status"),ProcField.entries.map { it.fileName })
        assertEquals(listOf("/system/bin/ps","-A","-w","-o","UID,PID,NAME"),Policy.command(Policy.PROCESS,0))
        assertThrows(IllegalArgumentException::class.java) { Policy.command(3,0) }
        assertEquals("INVALID_PID",ProcMetadata.read(-1,ProcField.STAT).reason)
    }
    @Test fun diagnosticScreenIsManualAndDoesNotCreateHistoryDatabase() {
        val context=RuntimeEnvironment.getApplication();val before=context.databaseList().toSet()
        fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
        Robolectric.buildActivity(ResourceDiagnosticActivity::class.java).setup().use { activity->
            val text=all(activity.get().window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(text.any { it.startsWith("尚未诊断") });assertTrue(text.contains("手动开始两次采样"));assertTrue(text.contains("导出脱敏诊断 JSON…"))
            assertEquals(before,context.databaseList().toSet())
        }
    }
}
