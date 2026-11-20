package cn.onan.sentinel

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import rikka.shizuku.Shizuku

internal data class ShizukuAuthorization(val binder: String, val authorization: String, val availability: String) {
    companion object {
        fun read(api: ShizukuApi): ShizukuAuthorization {
            val disconnected = ShizukuAuthorization("DISCONNECTED", "UNKNOWN", "SHIZUKU_NOT_RUNNING")
            return try {
                if (!api.alive()) return disconnected
                val status = when {
                    api.permission() != PackageManager.PERMISSION_GRANTED ->
                        ShizukuAuthorization("CONNECTED", "DENIED", "SHIZUKU_PERMISSION_REQUIRED")
                    else -> {
                        val verified = runCatching { api.verifyPermission() }.getOrNull()
                        when (verified) {
                            null -> ShizukuAuthorization("CONNECTED", "REPORTED_GRANTED", "SHIZUKU_AUTHORIZATION_UNVERIFIED")
                            false -> ShizukuAuthorization("CONNECTED", "REVOKED", "SHIZUKU_PERMISSION_DENIED")
                            true -> ShizukuAuthorization("CONNECTED", "VERIFIED",
                                if (api.uid() == 2000) "READY" else "SHIZUKU_ADB_SHELL_REQUIRED")
                        }
                    }
                }
                if (api.alive()) status else disconnected
            } catch (_: Exception) {
                if (runCatching { api.alive() }.getOrNull() == false) disconnected
                else ShizukuAuthorization("UNKNOWN", "UNKNOWN", "SHIZUKU_BINDER_UNAVAILABLE")
            }
        }
    }
}

class ShizukuBridge(context: Context, private val api: ShizukuApi = PlatformShizukuApi,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    serviceClass: Class<*> = ReadOnlyUserService::class.java, private val changed: (String) -> Unit) {
    private val epoch = BindingEpoch<IReadOnlyProbe>()
    val remote: IReadOnlyProbe? get() = synchronized(this) { epoch.remote }
    val generation: Long get() = synchronized(this) { epoch.generation }
    @Synchronized fun lease(): Pair<Long,IReadOnlyProbe>? = epoch.remote?.let { epoch.generation to it }
    private var connection: ServiceConnection? = null
    private var bindAt = 0L
    private var retryAt = 0L
    private var failures = 0
    private var lastFailure = ""
    private var previouslyBound = false
    private var reconnected = false
    private var listening = false
    private val args = Shizuku.UserServiceArgs(ComponentName(context, serviceClass))
        .daemon(false).processNameSuffix(when(serviceClass) { SnapshotUserService::class.java->"visible_snapshot";ResourceUserService::class.java->"resource_diagnostic";else->"readonly_probe" })
        .debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
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
    fun availability(): String = ShizukuAuthorization.read(api).availability

    @Synchronized fun health(): Map<String,String> {
        val status = ShizukuAuthorization.read(api)
        val remote = epoch.remote
        val phase = when {
            status.availability != "READY" -> "UNBOUND"
            remote?.asBinder()?.isBinderAlive == true -> "BOUND"
            remote != null -> "FAILED"
            connection != null -> "CONNECTING"
            lastFailure.isNotEmpty() -> "FAILED"
            else -> "UNBOUND"
        }
        return mapOf("binder" to status.binder, "authorization" to status.authorization,
            "userService" to phase, "availability" to status.availability,
            "generation" to epoch.generation.toString(), "reconnected" to reconnected.toString(),
            "failure" to (if (remote != null && phase == "FAILED") "USER_SERVICE_DISCONNECTED" else lastFailure),
            "retryAfterMs" to (retryAt-clock()).coerceAtLeast(0).toString())
    }
    @Synchronized fun connect(): String {
        val status = availability()
        if (status != "READY") { if(connection != null) release(); return status }
        if (epoch.remote?.asBinder()?.isBinderAlive == true) return "READY"
        if (epoch.remote != null) {
            invalidate("USER_SERVICE_DISCONNECTED")
            return "USER_SERVICE_DISCONNECTED"
        }
        val now = clock()
        if (now < retryAt) return "USER_SERVICE_RETRY_BACKOFF"
        if (connection != null) {
            if (now - bindAt < 15000) return "USER_SERVICE_CONNECTING"
            invalidate("USER_SERVICE_BIND_TIMEOUT"); return "USER_SERVICE_BIND_TIMEOUT"
        }
        lastFailure = ""
        val token = epoch.begin()
        val candidate = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) = synchronized(this@ShizukuBridge) {
                if (connection !== this || availability() != "READY" || !binder.isBinderAlive) return@synchronized
                if (epoch.connected(token, IReadOnlyProbe.Stub.asInterface(binder))) {
                    reconnected = previouslyBound
                    previouslyBound = true
                    failures = 0; retryAt = 0; lastFailure = ""; changed("SHIZUKU_CONNECTED")
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
    @Synchronized fun invalidateIfCurrent(generation: Long, reason: String) {
        if (epoch.matches(generation)) invalidate(reason)
    }

    @Synchronized fun invalidate(reason: String) {
        release()
        lastFailure = reason
        failures = (failures+1).coerceAtMost(6)
        retryAt = clock() + (1000L shl failures).coerceAtMost(60000)
        changed(reason)
    }
    @Synchronized fun release() {
        val previous = connection
        connection = null; lastFailure = ""; epoch.invalidate()
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
