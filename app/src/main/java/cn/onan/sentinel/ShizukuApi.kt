package cn.onan.sentinel

import android.content.ServiceConnection
import rikka.shizuku.Shizuku
import moe.shizuku.server.IShizukuService

/** Narrow platform adapter: permits deterministic lifecycle tests without a privileged service. */
interface ShizukuApi {
    fun alive(): Boolean
    fun verifyPermission(): Boolean
    fun permission(): Int
    fun uid(): Int
    fun bind(args: Shizuku.UserServiceArgs, connection: ServiceConnection)
    fun unbind(args: Shizuku.UserServiceArgs, connection: ServiceConnection)
    fun observe(add: Boolean, received: Shizuku.OnBinderReceivedListener, dead: Shizuku.OnBinderDeadListener,
        permission: Shizuku.OnRequestPermissionResultListener)
}
object PlatformShizukuApi : ShizukuApi {
    override fun alive() = Shizuku.pingBinder()
    // The SDK's checkSelfPermission() can return a cached grant.
    override fun verifyPermission(): Boolean =
        IShizukuService.Stub.asInterface(checkNotNull(Shizuku.getBinder())).checkSelfPermission()
    override fun permission() = Shizuku.checkSelfPermission()
    override fun uid() = Shizuku.getUid()
    override fun bind(args: Shizuku.UserServiceArgs, connection: ServiceConnection) = Shizuku.bindUserService(args,connection)
    override fun unbind(args: Shizuku.UserServiceArgs, connection: ServiceConnection) = Shizuku.unbindUserService(args,connection,true)
    override fun observe(add: Boolean, received: Shizuku.OnBinderReceivedListener, dead: Shizuku.OnBinderDeadListener,
        permission: Shizuku.OnRequestPermissionResultListener) {
        if(add) {
            Shizuku.addBinderReceivedListenerSticky(received); Shizuku.addBinderDeadListener(dead); Shizuku.addRequestPermissionResultListener(permission)
        } else {
            Shizuku.removeBinderReceivedListener(received); Shizuku.removeBinderDeadListener(dead); Shizuku.removeRequestPermissionResultListener(permission)
        }
    }
}
