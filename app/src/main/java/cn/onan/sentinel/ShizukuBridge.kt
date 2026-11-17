package cn.onan.sentinel

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import rikka.shizuku.Shizuku

class ShizukuBridge(context: Context, private val changed: (String) -> Unit) {
    @Volatile var remote: IReadOnlyProbe? = null
        private set
    @Volatile var generation = 0
        private set
    private var binding = false
    private var bindAt = 0L
    private val args = Shizuku.UserServiceArgs(ComponentName(context, ReadOnlyUserService::class.java))
        .daemon(false).processNameSuffix("readonly_probe").debuggable(BuildConfig.DEBUG).version(200)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(this@ShizukuBridge) {
                if (!binding) return
                remote = IReadOnlyProbe.Stub.asInterface(binder)
                generation++
                changed("SHIZUKU_CONNECTED")
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { invalidate("USER_SERVICE_DISCONNECTED") }
        override fun onBindingDied(name: ComponentName) { invalidate("USER_SERVICE_BINDING_DIED") }
        override fun onNullBinding(name: ComponentName) { invalidate("USER_SERVICE_NULL_BINDING") }
    }
    private val received = Shizuku.OnBinderReceivedListener { changed("SHIZUKU_BINDER_RECEIVED") }
    private val dead = Shizuku.OnBinderDeadListener { invalidate("SHIZUKU_DISCONNECTED") }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, grant ->
        if (grant != PackageManager.PERMISSION_GRANTED) invalidate("SHIZUKU_PERMISSION_DENIED")
        else changed("SHIZUKU_PERMISSION_GRANTED")
    }
    fun start() {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
    }
    fun availability(): String = try {
        when {
            !Shizuku.pingBinder() -> "SHIZUKU_NOT_RUNNING"
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "SHIZUKU_PERMISSION_REQUIRED"
            Shizuku.getUid() != 2000 -> "SHIZUKU_ADB_SHELL_REQUIRED"
            else -> "READY"
        }
    } catch (_: Exception) { "SHIZUKU_BINDER_UNAVAILABLE" }
    @Synchronized fun connect(): String {
        val status = availability()
        if (status != "READY") { remote = null; generation++; return status }
        if (remote?.asBinder()?.isBinderAlive == true) return "READY"
        if (binding && SystemClock.elapsedRealtime() - bindAt < 15000) return "USER_SERVICE_CONNECTING"
        if (binding) release()
        return try {
            binding = true; bindAt = SystemClock.elapsedRealtime()
            Shizuku.bindUserService(args, connection)
            "USER_SERVICE_CONNECTING"
        } catch (_: Exception) { binding = false; "USER_SERVICE_BIND_FAILED" }
    }
    private fun invalidate(reason: String) { remote = null; generation++; changed(reason) }
    @Synchronized fun release() {
        remote = null; generation++
        if (binding) try { Shizuku.unbindUserService(args, connection, true) } catch (_: Exception) { }
        binding = false
    }
    fun close() {
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
        release()
    }
}
