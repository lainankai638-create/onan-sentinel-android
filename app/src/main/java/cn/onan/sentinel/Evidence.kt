package cn.onan.sentinel

import android.app.AppOpsManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.Executors

val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
const val GUARD = "com.miui.guardprovider"
const val SECURITY = "com.miui.securitycenter"
const val PCAP = "com.emanuelef.remote_capture"
const val DAY = 86_400_000L
const val HOUR = 3_600_000L
val worker = Executors.newSingleThreadExecutor()

fun Context.options() = getSharedPreferences("sentinel", Context.MODE_PRIVATE)
fun Context.targets(): List<String> = listOf(SECURITY, GUARD) +
    options().getStringSet("targets", emptySet()).orEmpty().sorted()
fun label(target: String) = when (target) {
    SECURITY -> "手机管家"
    GUARD -> "系统安全组件"
    else -> target
}
fun midnight(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Suppress("DEPRECATION")
fun Context.pkg(target: String): PackageInfo? = try {
    packageManager.getPackageInfo(target, PackageManager.GET_PERMISSIONS)
} catch (_: PackageManager.NameNotFoundException) {
    null
}

fun Context.usageAllowed() = getSystemService(AppOpsManager::class.java)
    .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) ==
    AppOpsManager.MODE_ALLOWED

@Suppress("DEPRECATION")
fun Context.vpnVisible(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java)
    return manager.allNetworks.any {
        manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
}

data class Evidence(
    val time: Long, val target: String, val kind: String, val grade: String,
    val source: String, val data: JSONObject
)

class Store(context: Context) : SQLiteOpenHelper(context.applicationContext, "evidence.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE evidence(id INTEGER PRIMARY KEY, time INTEGER NOT NULL, target TEXT NOT NULL, kind TEXT NOT NULL, grade TEXT NOT NULL, source TEXT NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE INDEX evidence_target_time ON evidence(target,time)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("缺少数据库迁移")

    fun add(row: Evidence) {
        writableDatabase.insertOrThrow("evidence", null, ContentValues().apply {
            put("time", row.time)
            put("target", row.target)
            put("kind", row.kind)
            put("grade", row.grade)
            put("source", row.source)
            put("data", row.data.toString())
        })
    }

    fun rows(target: String, start: Long, end: Long, limit: Int = 2001,
             kind: String? = null): List<Evidence> {
        val filter = if (kind == null) "" else " AND kind=?"
        val args = mutableListOf(target, start.toString(), end.toString())
        kind?.let(args::add)
        return readableDatabase.rawQuery(
            "SELECT time,target,kind,grade,source,data FROM evidence WHERE target=? AND time>=? AND time<=?$filter ORDER BY time DESC,id DESC LIMIT $limit",
            args.toTypedArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Evidence(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                        cursor.getString(3), cursor.getString(4), JSONObject(cursor.getString(5))))
                }
            }
        }
    }

    fun prune(days: Int) {
        writableDatabase.delete("evidence", "time < ?", arrayOf((System.currentTimeMillis() - days * DAY).toString()))
    }
    fun clear() { writableDatabase.delete("evidence", null, null) }
}

@Suppress("DEPRECATION")
fun Context.sample(store: Store, target: String, now: Long) {
    val info = pkg(target)
    val uid = info?.applicationInfo?.uid
    val payload = JSONObject()
    val grade = if (uid == null || !usageAllowed()) "无法获取" else "已确认"
    if (uid == null) payload.put("限制", "包未安装或不可见")
    if (uid != null) {
        val peers = packageManager.getPackagesForUid(uid).orEmpty()
        payload.put("UID", uid).put("版本", info.versionName)
            .put("最近安装", info.firstInstallTime).put("最近更新", info.lastUpdateTime)
            .put("同UID可见包", peers.joinToString(","))
        payload.put("归属", "UID 汇总，包可见性有限")
    }
    var result = grade
    if (uid != null && grade == "已确认") {
        val stats = getSystemService(NetworkStatsManager::class.java)
        val dayStart = midnight().coerceAtMost(now)
        for ((network, name) in listOf(ConnectivityManager.TYPE_WIFI to "Wi-Fi",
            ConnectivityManager.TYPE_MOBILE to "移动网络")) {
            try {
                var up = 0L
                var down = 0L
                var count = 0
                val queried = stats.queryDetailsForUid(network, null, dayStart, now, uid)
                    ?: error("系统未返回统计")
                queried.use { buckets ->
                    val bucket = NetworkStats.Bucket()
                    while (buckets.getNextBucket(bucket)) {
                        up += bucket.txBytes
                        down += bucket.rxBytes
                        count++
                    }
                }
                val data = JSONObject().put("统计起点", dayStart).put("统计终点", now).put("桶数", count)
                if (count > 0) data.put("上传字节", up).put("下载字节", down)
                else data.put("限制", "无可用统计桶，可能延迟入账")
                payload.put(name, data)
            } catch (e: Exception) {
                payload.put(name, JSONObject().put("限制", "统计权限受限或系统未返回数据"))
            }
        }
        if (listOf("Wi-Fi", "移动网络").none { payload.optJSONObject(it)?.has("上传字节") == true }) result = "无法获取"
    } else if (uid != null) payload.put("限制", "权限受限，请授权使用情况访问")
    store.add(Evidence(now, target, "流量快照", result, "Android NetworkStatsManager / PackageManager", payload))
}

class SampleJob : JobService() {
    @Volatile private var active: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        active = params
        worker.execute {
            try {
                Store(this).use { db ->
                    val now = System.currentTimeMillis()
                    targets().forEach { target ->
                        if (active === params) runCatching { sample(db, target, now) }
                    }
                    if (active === params) db.prune(options().getInt("retention", 14))
                }
            } finally {
                mainExecutor.execute {
                    if (active === params) {
                        active = null
                        jobFinished(params, false)
                    }
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (active === params) active = null
        return true
    }
}
