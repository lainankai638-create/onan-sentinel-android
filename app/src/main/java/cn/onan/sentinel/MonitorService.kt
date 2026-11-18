package cn.onan.sentinel

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import org.json.JSONObject
import java.util.UUID

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
    private val control = CollectionControl()
    private var session = UUID.randomUUID().toString()
    private val owner = UUID.randomUUID().toString()
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
                        baselines.clear(); store.gap(reason, observed); store.allSourcesUnavailable(reason); update(reason)
                    }
                }
            }
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "可见监控状态", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedAt = System.currentTimeMillis()
        if (intent?.action in setOf(START, PAUSE, STOP)) { haltRequested = true; control.halt() }
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
                STOP -> finish("USER_STOPPED",requestedAt)
                PAUSE -> if (initialized && !paused) {
                    paused = true; bridge.release(); baselines.clear()
                    store.allSourcesUnavailable("USER_PAUSED", requestedAt, "KNOWN_STOP")
                    store.gap("USER_PAUSED"); store.add("PAUSED", "UNAVAILABLE", "User paused collection")
                    update("PAUSED")
                }
                START -> {
                    if (!initialized) {
                        store.atomic {
                            add("SESSION_START", "OK", "Manual visible foreground service; interval=${interval / 60000} minutes; user=0")
                            val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(Build.FINGERPRINT.toByteArray())
                                .joinToString("") { "%02x".format(it) }
                            add("DEVICE_CONTEXT", "PARTIAL", "app=${BuildConfig.VERSION_NAME}; sdk=${Build.VERSION.SDK_INT}; fingerprintSha256=$fingerprint; intervalMs=$interval; battery/network settings not independently recorded")
                            setMeta("active", "true"); setMeta("lastTick", System.currentTimeMillis().toString())
                            setMeta("activeOwner", owner)
                            gap("AWAITING_FIRST_VALID_SAMPLE")
                            allSourcesUnavailable("AWAITING_FIRST_VALID_SAMPLE", System.currentTimeMillis(), "OBSERVED_UNAVAILABLE")
                        }
                        initialized = true; live = true; bridge.start(); worker.post(tick)
                    } else if (paused) store.add("RESUMED", "PARTIAL", "User resumed; next valid sample ends gap")
                    paused = false; nextSample = 0; lastSampleElapsed = 0
                    session = UUID.randomUUID().toString(); control.resume()
                    baselines.clear()
                    haltRequested = false
                    update("CONNECTING")
                }
                else -> finish("NO_MANUAL_START_INTENT")
            }
        } }
        return START_NOT_STICKY
    }
    private fun poll() {
        if (!initialized) return
        val notifications = getSystemService(NotificationManager::class.java)
        for ((key,value) in bridge.health()) if (store.meta("link.$key") != value) {
            store.setMeta("link.$key", value)
            if (key != "retryAfterMs") store.add("LINK_STATE", "PARTIAL", "$key=$value", source="SHIZUKU_BINDER")
        }
        val notificationEnabled = notifications.areNotificationsEnabled() && notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        store.setMeta("link.notification", if(notificationEnabled) "CHANNEL_ENABLED" else "DISABLED")
        if (!notificationEnabled) {
            finish("NOTIFICATIONS_DISABLED"); return
        }
        if (paused || haltRequested) return
        val controlToken = control.token()
        val now = SystemClock.elapsedRealtime()
        if (!delayReported && lastSampleElapsed > 0 && now - lastSampleElapsed > interval + 30000) {
            store.gap("SCHEDULER_DELAY; last sample is earliest bound; sleep or scheduling cause not known", lastSampleWall)
            store.allSourcesUnavailable("SCHEDULER_DELAY_CAUSE_UNKNOWN", lastSampleWall, "UNKNOWN_LOWER_BOUND")
            baselines.clear(); delayReported = true
        }
        val available = bridge.connect()
        if (available != "READY" || bridge.remote == null) {
            if (available != lastReason) {
                store.gap(available); store.add("COLLECTION_UNAVAILABLE", "UNAVAILABLE", available, source = "SHIZUKU_BINDER")
                store.allSourcesUnavailable(available)
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
        val processBatch = ProcessBatch()
        for (target in Policy.targets.indices) {
            for (kind in listOf(Policy.PROCESS, Policy.APPOPS)) {
                if (closing || paused || haltRequested) return
                val lease = bridge.lease()
                if (lease == null || bridge.availability() != "READY") {
                    baselines.clear(); store.gap("SHIZUKU_LOST_DURING_BATCH"); store.allSourcesUnavailable("SHIZUKU_LOST_DURING_BATCH"); update("SHIZUKU_LOST_DURING_BATCH"); return
                }
                val (generation,remote) = lease
                val source = if (kind == Policy.PROCESS) "SHIZUKU_PS" else "SHIZUKU_APPOPS"
                val started = System.currentTimeMillis()
                var callFailed = false
                val evidence = try {
                    if(kind==Policy.PROCESS) processBatch.sample(generation,Policy.targets[target]) {
                        ProcessSnapshots.fromJson(ProbeLanes.forKind(kind).call(7000) { remote.snapshot() })
                    } else {
                        val json = JSONObject(ProbeLanes.forKind(kind).call(7000) { remote.sample(kind, target) })
                        Evidence(json.getString("status"), json.getString("evidence").take(Policy.MAX_EVIDENCE))
                    }
                } catch (e: Exception) {
                    callFailed = e !is IllegalStateException && e !is java.util.concurrent.RejectedExecutionException
                    Evidence("UNAVAILABLE", if(e is IllegalStateException || e is java.util.concurrent.RejectedExecutionException) "SOURCE_CALL_STILL_RUNNING" else "USER_SERVICE_CALL_FAILED_OR_TIMEOUT")
                }
                if (haltRequested || closing || !control.active(controlToken)) return
                var accepted = false
                control.accept(controlToken) {
                    accepted = bridge.accept(generation) {
                        val type = baselines.compare("$target:$kind", evidence)
                        store.recordSample(source,Policy.targets[target],evidence,type,started,System.currentTimeMillis(),session,generation)
                    }
                }
                if (!accepted) {
                    baselines.clear(); store.gap("AUTHORIZATION_OR_SESSION_CHANGED; in-flight result discarded")
                    store.allSourcesUnavailable("AUTHORIZATION_OR_SESSION_CHANGED"); update("AUTHORIZATION_CHANGED"); return
                }
                if (evidence.status in setOf("OK", "PARTIAL")) usable++ else failures++
                if (callFailed) {
                    bridge.invalidate("USER_SERVICE_CALL_FAILED_OR_TIMEOUT"); baselines.clear()
                    store.gap("USER_SERVICE_CALL_FAILED_OR_TIMEOUT"); store.allSourcesUnavailable("USER_SERVICE_CALL_FAILED_OR_TIMEOUT")
                    update("USER_SERVICE_CALL_FAILED_OR_TIMEOUT"); return
                }
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
            .setContentTitle("欧南哨兵 · 本地采样").setContentText(Chinese.status(status)).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, if (paused) "恢复" else "暂停", serviceAction(if (paused) START else PAUSE, 1)).build())
            .addAction(Notification.Action.Builder(null, "停止", serviceAction(STOP, 2)).build()).build()
    }
    private fun finish(reason: String, stoppedAt: Long = System.currentTimeMillis()) {
        if (closing) return
        control.halt(); closing = true; live = false
        bridge.close(); worker.removeCallbacks(tick)
        if(store.finishSession(owner,reason,stoppedAt)) runtimeStatus = "STOPPED"
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun safe(block: () -> Unit) {
        try { block() } catch (e: Exception) {
            // A failed database cannot record its own failure reliably. Keep this visible and stop collecting.
            runtimeStatus = "STORAGE_OR_RUNTIME_FAILURE:${FailureReason.of(e)}"
            control.halt(); closing = true; live = false; bridge.close(); worker.removeCallbacks(tick)
            getSystemService(NotificationManager::class.java).notify(20, notification(runtimeStatus))
            stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
        }
    }
    override fun onDestroy() {
        val destroyedAt = System.currentTimeMillis()
        haltRequested = true
        control.halt()
        bridge.close() // Release before a replacement Service instance can bind the same UserService.
        live = false
        worker.removeCallbacks(tick)
        worker.post {
            if (!closing && initialized) safe {
                if(store.finishSession(owner,"SERVICE_DESTROYED",destroyedAt)) runtimeStatus = "STOPPED"
            }
            closing = true; thread.quitSafely()
        }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
