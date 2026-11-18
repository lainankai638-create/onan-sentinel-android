package cn.onan.sentinel

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NavigationTest {
    private fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun texts(a:MainActivity)=all(a.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    @Test fun fourTabsOpenRealPagesWithTruthfulEmptyStates() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { c -> val a=c.get();val nav=all(a.window.decorView).filterIsInstance<BottomNavigationView>().single()
            assertEquals(4,nav.menu.size());assertTrue(texts(a).contains("安全总览"))
            nav.selectedItemId=R.id.tab_apps;assertTrue(texts(a).contains("运行进程"));assertTrue(texts(a).any { it.contains("尚未读取") })
            nav.selectedItemId=R.id.tab_timeline;assertTrue(texts(a).contains("事件时间线"));assertTrue(texts(a).contains("此范围没有保留记录"))
            nav.selectedItemId=R.id.tab_report;assertTrue(texts(a).contains("本地报告"));assertTrue(texts(a).contains("预览统计并确认导出"))
        }
    }
    @Test fun sensitiveAndNetworkPagesStateEvidenceLimits() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { c -> val a=c.get()
            all(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString()=="敏感操作模式" }.performClick()
            assertTrue(texts(a).any { it.contains("allow 表示规则允许") });a.onBackPressed()
            all(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString()=="网络统计实验" }.performClick()
            assertTrue(texts(a).any { it.contains("默认关闭，未开始采集") });assertTrue(texts(a).any { it.contains("现有 VPN 保持不变") })
        }
    }
    @Test fun settingsAndComponentDetailsAreReachable() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { c -> val a=c.get()
            all(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString()=="授权与监控设置" }.performClick()
            assertTrue(texts(a).contains("监控设置"));assertTrue(texts(a).contains("界面主题"));a.onBackPressed()
            all(a.window.decorView).filterIsInstance<BottomNavigationView>().single().selectedItemId=R.id.tab_apps
            all(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString()=="手机管家" }.performClick()
            assertTrue(texts(a).contains("应用详情"));assertTrue(texts(a).contains("尚无该组件的历史证据"))
        }
    }
}
