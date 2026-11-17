package cn.onan.sentinel

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MonitorService : Service() {
    companion object {
        const val START = "start"
        const val PAUSE = "pause"
        const val STOP = "stop"
        const val CHANNEL = "visible_monitor"
        @Volatile var live = false
        @Volatile var runtimeStatus = "STOPPED"
    }
    private val store get() = (application as SentinelApp).store
    private val thread = HandlerThread("sentinel-monitor")
    private lateinit var worker: Handler
    private lateinit var bridge: ShizukuBridge
    private val calls = Executors.newSingleThreadExecutor()
    private val baselines = Baselines()
    private var paused = false
    @Volatile private var closing = false
    @Volatile private var haltRequested = false
    private var nextSample = 0L
    private var lastSampleWall = 0L
    private var lastSampleElapsed = 0L
    private var delayReported = false
    private var lastReason = ""
    private var initialized = false
    private val interval get() = getSharedPreferences("settings", MODE_PRIVATE).getInt("intervalMinutes", 5).coerceIn(1, 15) * 60000L
    private val tick = object : Runnable {
        override fun run() {
            if (closing) return
            safe { poll() }
            if (!closing) worker.postDelayed(this, 15000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        thread.start(); worker = Handler(thread.looper)
        bridge = ShizukuBridge(this) { reason ->
            val observed = System.currentTimeMillis()
            worker.post {
                if (!closing && initialized) safe {
                    store.add(reason, if (reason == "SHIZUKU_CONNECTED") "OK" else "PARTIAL", "BINDER_CALLBACK_OBSERVED", source = "SHIZUKU_BINDER", time = observed)
                    if (reason.contains("DISCONNECT") || reason.contains("DENIED") || reason.contains("DIED")) {
                        baselines.clear(); store.gap(reason, observed); update(reason)
                    }
                }
            }
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "可见监控状态", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action in setOf(PAUSE, STOP)) haltRequested = true
        try {
            if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
                runtimeStatus = "NOTIFICATION_PERMISSION_REQUIRED"; stopSelf(); return START_NOT_STICKY
            }
            val n = notification("准备监控")
            if (Build.VERSION.SDK_INT >= 34) startForeground(20, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(20, n)
        } catch (e: Exception) {
            runtimeStatus = "FGS_START_FAILED:${e.javaClass.simpleName}"; stopSelf(); return START_NOT_STICKY
        }
        worker.post { safe {
            when (intent?.action) {
                STOP -> finish("USER_STOPPED")
                PAUSE -> if (initialized && !paused) {
                    paused = true; bridge.release(); baselines.clear()
                    store.gap("USER_PAUSED"); store.add("PAUSED", "UNAVAILABLE", "User paused collection")
                    update("PAUSED")
                }
                START -> {
                    if (!initialized) {
                        store.atomic {
                            add("SESSION_START", "OK", "Manual visible foreground service; interval=${interval / 60000} minutes; user=0")
                            setMeta("active", "true"); setMeta("lastTick", System.currentTimeMillis().toString())
                            gap("AWAITING_FIRST_VALID_SAMPLE")
                        }
                        initialized = true; live = true; bridge.start(); worker.post(tick)
                    } else if (paused) store.add("RESUMED", "PARTIAL", "User resumed; next valid sample ends gap")
                    paused = false; nextSample = 0; lastSampleElapsed = 0
                    haltRequested = false
                    update("CONNECTING")
                }
                else -> finish("NO_MANUAL_START_INTENT")
            }
        } }
        return START_NOT_STICKY
    }
    private fun poll() {
        if (!initialized || paused || haltRequested) return
        val notifications = getSystemService(NotificationManager::class.java)
        if (!notifications.areNotificationsEnabled() || notifications.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            finish("NOTIFICATIONS_DISABLED"); return
        }
        val now = SystemClock.elapsedRealtime()
        if (!delayReported && lastSampleElapsed > 0 && now - lastSampleElapsed > interval + 30000) {
            store.gap("SCHEDULER_DELAY; last sample is earliest bound; sleep or scheduling cause not known", lastSampleWall)
            baselines.clear(); delayReported = true
        }
        val available = bridge.connect()
        if (available != "READY" || bridge.remote == null) {
            if (available != lastReason) {
                store.gap(available); store.add("COLLECTION_UNAVAILABLE", "UNAVAILABLE", available, source = "SHIZUKU_BINDER")
                lastReason = available
            }
            baselines.clear(); update(available); return
        }
        if (now < nextSample) return
        if (lastSampleElapsed > 0 && kotlin.math.abs((System.currentTimeMillis() - lastSampleWall) - (now - lastSampleElapsed)) > 60000) {
            store.add("CLOCK_CHANGED", "UNKNOWN", "Wall clock differs from monotonic elapsed time")
        }
        var usable = 0
        var failures = 0
        for (target in Policy.targets.indices) {
            for (kind in listOf(Policy.PROCESS, Policy.APPOPS)) {
                if (closing || paused || haltRequested) return
                val remote = bridge.remote
                val generation = bridge.generation
                if (remote == null || bridge.availability() != "READY") {
                    baselines.clear(); store.gap("SHIZUKU_LOST_DURING_BATCH"); update("SHIZUKU_LOST_DURING_BATCH"); return
                }
                val future = calls.submit<String> { remote.sample(kind, target) }
                val evidence = try {
                    val json = JSONObject(future.get(7, TimeUnit.SECONDS))
                    Evidence(json.getString("status"), json.getString("evidence").take(Policy.MAX_EVIDENCE))
                } catch (_: Exception) {
                    future.cancel(true)
                    bridge.release(); baselines.clear()
                    store.gap("USER_SERVICE_CALL_FAILED_OR_TIMEOUT")
                    store.add("COLLECTION_UNAVAILABLE", "UNAVAILABLE", "USER_SERVICE_CALL_FAILED_OR_TIMEOUT", Policy.targets[target], "SHIZUKU_BINDER")
                    update("USER_SERVICE_CALL_FAILED_OR_TIMEOUT"); return
                }
                if (haltRequested || closing) return
                if (generation != bridge.generation || bridge.availability() != "READY") {
                    baselines.clear(); store.gap("AUTHORIZATION_CHANGED; in-flight result discarded"); update("AUTHORIZATION_CHANGED"); return
                }
                val source = if (kind == Policy.PROCESS) "SHIZUKU_PS" else "SHIZUKU_APPOPS"
                val type = baselines.compare("$target:$kind", evidence)
                store.add(type, evidence.status, evidence.value, Policy.targets[target], source)
                if (evidence.status in setOf("OK", "PARTIAL")) usable++ else failures++
            }
        }
        lastSampleWall = System.currentTimeMillis(); lastSampleElapsed = SystemClock.elapsedRealtime()
        delayReported = false
        nextSample = lastSampleElapsed + interval
        store.setMeta("lastTick", lastSampleWall.toString())
        if (usable > 0) { store.recovered(); lastReason = "" }
        else store.gap("ALL_SOURCES_UNAVAILABLE")
        store.setMeta("lastSummary", "usable=$usable unavailable=$failures; AppOps modes only")
        update(if (usable == 0) "ALL_SOURCES_UNAVAILABLE" else if (failures > 0) "MONITORING_PARTIAL" else "MONITORING_SAMPLED")
    }
    private fun update(status: String) {
        if (runtimeStatus != status) {
            runtimeStatus = status; store.setMeta("state", status)
            getSystemService(NotificationManager::class.java).notify(20, notification(status))
        }
    }
    private fun notification(status: String): Notification {
        fun serviceAction(action: String, code: Int) = PendingIntent.getService(this, code, Intent(this, MonitorService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_sentinel)
            .setContentTitle("欧南哨兵 · 本地采样").setContentText(status).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, if (paused) "恢复" else "暂停", serviceAction(if (paused) START else PAUSE, 1)).build())
            .addAction(Notification.Action.Builder(null, "停止", serviceAction(STOP, 2)).build()).build()
    }
    private fun finish(reason: String) {
        if (closing) return
        closing = true; live = false
        bridge.close(); worker.removeCallbacks(tick)
        store.atomic {
            gap(reason); add("SESSION_STOP", "UNAVAILABLE", reason)
            setMeta("active", "false"); setMeta("state", "STOPPED")
        }
        runtimeStatus = "STOPPED"
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun safe(block: () -> Unit) {
        try { block() } catch (e: Exception) {
            // A failed database cannot record its own failure reliably. Keep this visible and stop collecting.
            runtimeStatus = "STORAGE_OR_RUNTIME_FAILURE:${e.javaClass.simpleName}"
            closing = true; live = false; bridge.close(); worker.removeCallbacks(tick)
            getSystemService(NotificationManager::class.java).notify(20, notification(runtimeStatus))
            stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
        }
    }
    override fun onDestroy() {
        haltRequested = true
        live = false
        worker.removeCallbacks(tick)
        worker.post {
            if (!closing && initialized) safe {
                store.atomic {
                    gap("SERVICE_DESTROYED"); add("SESSION_STOP", "UNAVAILABLE", "SERVICE_DESTROYED")
                    setMeta("active", "false"); setMeta("state", "STOPPED")
                }
                runtimeStatus = "STOPPED"
            }
            closing = true; bridge.close(); calls.shutdownNow(); thread.quitSafely()
        }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
