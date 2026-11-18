package cn.onan.sentinel

import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Read-only UI smoke test. Unlike StoreInstrumentedTest, never clears or seeds history. */
class UiAcceptanceTest {
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    @Test fun eightRealPagesAndScreenshots() {
        check(Build.HARDWARE in setOf("ranchu","goldfish")) { "Use disposable emulator only" }
        val instrument=InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun capture(name:String,title:String) {
                instrument.waitForIdleSync();Thread.sleep(700)
                scenario.onActivity { a ->
                    val texts=all(a.window.decorView).filterIsInstance<TextView>()
                    assertTrue(texts.any { it.text.toString()==title })
                    texts.filter { it.isShown && it.width>0 }.forEach { view ->
                        view.layout?.let { layout -> repeat(layout.lineCount) { assertEquals("Truncated label: ${view.text}",0,layout.getEllipsisCount(it)) } }
                    }
                }
                val bitmap=instrument.uiAutomation.takeScreenshot();assertNotNull(bitmap)
                val dir=File(instrument.targetContext.getExternalFilesDir(null),"ui-evidence").apply { mkdirs() }
                File(dir,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
            fun tab(id:Int) { scenario.onActivity { a->all(a.window.decorView).filterIsInstance<BottomNavigationView>().single().selectedItemId=id };instrument.waitForIdleSync() }
            fun click(text:String) { scenario.onActivity { a->all(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString()==text }.performClick() };instrument.waitForIdleSync() }
            capture("01-overview","安全总览")
            tab(R.id.tab_apps);capture("02-processes","运行进程")
            click("手机管家");capture("03-detail","应用详情")
            tab(R.id.tab_overview);click("敏感操作模式");capture("04-appops","敏感行为")
            tab(R.id.tab_overview);click("网络统计实验");capture("05-network","网络活动")
            tab(R.id.tab_timeline);capture("06-timeline","事件时间线")
            tab(R.id.tab_overview);click("授权与监控设置");capture("07-settings","监控设置")
            tab(R.id.tab_report);capture("08-report","本地报告")
        }
    }
}
