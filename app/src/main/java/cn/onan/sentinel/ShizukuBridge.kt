package cn.onan.sentinel

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import rikka.shizuku.Shizuku

class ShizukuBridge(context: Context, private val api: ShizukuApi = PlatformShizukuApi,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }, private val changed: (String) -> Unit) {
    private val epoch = BindingEpoch<IReadOnlyProbe>()
    val remote: IReadOnlyProbe? get() = synchronized(this) { epoch.remote }
    val generation: Long get() = synchronized(this) { epoch.generation }
    @Synchronized fun lease(): Pair<Long,IReadOnlyProbe>? = epoch.remote?.let { epoch.generation to it }
    private var connection: ServiceConnection? = null
    private var bindAt = 0L
    private var retryAt = 0L
    private var failures = 0
    private var listening = false
    private val args = Shizuku.UserServiceArgs(ComponentName(context, ReadOnlyUserService::class.java))
        .daemon(false).processNameSuffix("readonly_probe").debuggable(BuildConfig.DEBUG).version(300)
    private val received = Shizuku.OnBinderReceivedListener {
        synchronized(this) { release(); retryAt = 0 }
        changed("SHIZUKU_BINDER_RECEIVED")
    }
    private val dead = Shizuku.OnBinderDeadListener { invalidate("SHIZUKU_DISCONNECTED") }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, grant ->
        if (grant != PackageManager.PERMISSION_GRANTED) invalidate("SHIZUKU_PERMISSION_DENIED")
        else { synchronized(this) { retryAt = 0 }; changed("SHIZUKU_PERMISSION_GRANTED") }
    }
    @Synchronized fun start() {
        if (listening) return
        listening = true
        api.observe(true,received,dead,permission)
    }
    fun availability(): String = try {
        when {
            !api.alive() -> "SHIZUKU_NOT_RUNNING"
            api.permission() != PackageManager.PERMISSION_GRANTED -> "SHIZUKU_PERMISSION_REQUIRED"
            api.uid() != 2000 -> "SHIZUKU_ADB_SHELL_REQUIRED"
            else -> "READY"
        }
    } catch (_: Exception) { "SHIZUKU_BINDER_UNAVAILABLE" }
    @Synchronized fun health(): Map<String,String> {
        val available = availability()
        if (available != "READY" && connection != null) release()
        val binder = try { if (api.alive()) "CONNECTED" else "DISCONNECTED" } catch (_: Exception) { "UNKNOWN" }
        val auth = if (binder != "CONNECTED") "UNKNOWN" else try {
            if (api.permission() == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"
        } catch (_: Exception) { "UNKNOWN" }
        return mapOf("binder" to binder, "authorization" to auth,
            "userService" to if (epoch.remote != null) "BOUND" else if (connection != null) "CONNECTING" else "UNBOUND",
            "availability" to available, "generation" to epoch.generation.toString(),
            "retryAfterMs" to (retryAt-clock()).coerceAtLeast(0).toString())
    }
    @Synchronized fun connect(): String {
        val status = availability()
        if (status != "READY") { if(connection != null) release(); return status }
        if (epoch.remote?.asBinder()?.isBinderAlive == true) return "READY"
        val now = clock()
        if (now < retryAt) return "USER_SERVICE_RETRY_BACKOFF"
        if (connection != null) {
            if (now - bindAt < 15000) return "USER_SERVICE_CONNECTING"
            invalidate("USER_SERVICE_BIND_TIMEOUT"); return "USER_SERVICE_BIND_TIMEOUT"
        }
        val token = epoch.begin()
        val candidate = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) = synchronized(this@ShizukuBridge) {
                if (connection !== this || availability() != "READY" || !binder.isBinderAlive) return@synchronized
                if (epoch.connected(token, IReadOnlyProbe.Stub.asInterface(binder))) {
                    failures = 0; retryAt = 0; changed("SHIZUKU_CONNECTED")
                }
            }
            override fun onServiceDisconnected(name: ComponentName) = failed("USER_SERVICE_DISCONNECTED")
            override fun onBindingDied(name: ComponentName) = failed("USER_SERVICE_BINDING_DIED")
            override fun onNullBinding(name: ComponentName) = failed("USER_SERVICE_NULL_BINDING")
            private fun failed(reason: String) = synchronized(this@ShizukuBridge) {
                if (connection === this && epoch.matches(token)) invalidate(reason)
            }
        }
        connection = candidate; bindAt = now
        return try { api.bind(args, candidate); "USER_SERVICE_CONNECTING" }
        catch (_: Exception) { invalidate("USER_SERVICE_BIND_FAILED"); "USER_SERVICE_BIND_FAILED" }
    }
    @Synchronized fun accept(token: Long, write: () -> Unit): Boolean {
        if (!epoch.matches(token) || epoch.remote?.asBinder()?.isBinderAlive != true || availability() != "READY") return false
        write(); return true
    }
    @Synchronized fun invalidate(reason: String) {
        release()
        failures = (failures+1).coerceAtMost(6)
        retryAt = clock() + (1000L shl failures).coerceAtMost(60000)
        changed(reason)
    }
    @Synchronized fun release() {
        val previous = connection
        connection = null; epoch.invalidate()
        if (previous != null) try { api.unbind(args, previous) } catch (_: Exception) { }
    }
    @Synchronized fun close() {
        if (listening) {
            listening = false
            api.observe(false,received,dead,permission)
        }
        release()
    }
}
