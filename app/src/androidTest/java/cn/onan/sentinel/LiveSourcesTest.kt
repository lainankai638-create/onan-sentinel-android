package cn.onan.sentinel

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import com.google.android.material.bottomnavigation.BottomNavigationView

/** Requires an explicitly authorized shell-mode Shizuku and Usage Access on a disposable emulator. */
class LiveSourcesTest {
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    @Test fun realShellSnapshotAndPublicNetworkApi() {
        check(Build.HARDWARE in setOf("ranchu","goldfish"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.getExternalFilesDir(null),"ui-evidence").apply { mkdirs() }
        val instrument=InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun texts():List<String> { var result=emptyList<String>();scenario.onActivity { a->result=all(a.window.decorView).filterIsInstance<TextView>().map { it.text.toString() } };return result }
            fun click(text:String) { scenario.onActivity { a->all(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString()==text }.performClick() };instrument.waitForIdleSync() }
            fun capture(name:String) { instrument.waitForIdleSync();val bitmap=instrument.uiAutomation.takeScreenshot();File(dir,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
            scenario.onActivity { a->all(a.window.decorView).filterIsInstance<BottomNavigationView>().single().selectedItemId=R.id.tab_apps }
            click("刷新进程快照")
            // Cold app_process startup in software emulation can exceed the UI's bounded wait.
            Thread.sleep(60000)
            if(texts().none { it.contains("采样成功") }) { click("刷新进程快照");Thread.sleep(10000) }
            val snapshot=texts();capture("09-live-processes.png")
            File(dir,"live-sources.txt").writeText(snapshot.filter { it.startsWith("可见 ") || it.startsWith("采样成功") }.joinToString("\n")+"\n")
            assertTrue("Real Shizuku snapshot unavailable",snapshot.any { it.contains("采样成功") })
            scenario.onActivity { a->all(a.window.decorView).filterIsInstance<BottomNavigationView>().single().selectedItemId=R.id.tab_overview }
            click("网络统计实验");click("开启网络统计实验");click("查询最近 24 小时统计桶");Thread.sleep(15000)
            val traffic=texts();capture("10-live-network.png")
            File(dir,"live-sources.txt").appendText(traffic.filter { it.contains("统计桶") || it.contains("系统返回") }.joinToString("\n"))
            assertTrue("Public NetworkStats query unavailable",traffic.any { it.contains("系统未返回统计桶") || it.contains("系统返回 ") })
            click("关闭实验并停止后续查询")
        }
    }
}
