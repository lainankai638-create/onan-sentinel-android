package cn.onan.sentinel

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rikka.shizuku.Shizuku

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=android.app.Application::class)
class ShizukuBridgeTest {
    private class Fake : ShizukuApi {
        var running=true;var granted=true;var shellUid=2000
        val binds=mutableListOf<ServiceConnection>();var observers=0;var unbinds=0
        var remotelyGranted: Boolean? = true
        var samples = 0
        override fun verifyPermission() = checkNotNull(remotelyGranted)
        lateinit var received: Shizuku.OnBinderReceivedListener
        lateinit var dead: Shizuku.OnBinderDeadListener
        override fun alive()=running
        override fun permission()=if(granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        override fun uid()=shellUid
        override fun bind(args: Shizuku.UserServiceArgs, connection: ServiceConnection) { binds+=connection }
        override fun unbind(args: Shizuku.UserServiceArgs, connection: ServiceConnection) { unbinds++ }
        override fun observe(add: Boolean, received: Shizuku.OnBinderReceivedListener, dead: Shizuku.OnBinderDeadListener, permission: Shizuku.OnRequestPermissionResultListener) {
            this.received=received;this.dead=dead;observers+=if(add) 1 else -1
        }
        fun deliver(index: Int) = binds[index].onServiceConnected(ComponentName("test","service"), object:IReadOnlyProbe.Stub() {
            override fun sample(kind:Int,target:Int): String { samples++; return "{}" }
            override fun snapshot(): String { samples++; return "{}" }
            override fun resources()="{}"
            override fun destroy()=Unit
        })
    }
    @Test fun delayedCallbackFromReleasedBindCannotOverwriteNewConnection() {
        val api=Fake();val bridge=ShizukuBridge(RuntimeEnvironment.getApplication(),api,{0}) { }
        bridge.start();bridge.start();assertEquals(1,api.observers)
        bridge.connect();bridge.release();bridge.connect();api.deliver(0);assertNull(bridge.remote)
        api.deliver(1);val current=bridge.lease()!!;api.deliver(0);assertEquals(current,bridge.lease())
        bridge.close();assertEquals(0,api.observers);api.deliver(1);assertNull(bridge.remote)
    }
    @Test fun revokeAfterReadRejectsCommitAndHealthWhilePausedRemainsAccurate() {
        val api=Fake();val bridge=ShizukuBridge(RuntimeEnvironment.getApplication(),api,{0}) { }
        bridge.connect();api.deliver(0);val token=bridge.lease()!!.first
        api.granted=false;var writes=0
        assertFalse(bridge.accept(token) { writes++ });assertEquals(0,writes)
        assertEquals("DENIED",bridge.health()["authorization"]);assertEquals("SHIZUKU_PERMISSION_REQUIRED",bridge.connect());assertNull(bridge.remote)
        api.granted=true;bridge.connect();api.deliver(1)
        assertFalse(bridge.accept(token) { writes++ });assertTrue(bridge.accept(bridge.generation) { writes++ });assertEquals(1,writes)
    }
    @Test fun reconnectUsesNewEpochAndBindTimeoutHasBackoff() {
        val api=Fake();var now=0L;val bridge=ShizukuBridge(RuntimeEnvironment.getApplication(),api,{now}) { }
        bridge.start();bridge.connect();now=15000
        assertEquals("USER_SERVICE_BIND_TIMEOUT",bridge.connect())
        assertEquals("USER_SERVICE_BIND_TIMEOUT", SamplingDiagnosis(bridge.health(), emptyMap()).status)
        repeat(10) { assertEquals("USER_SERVICE_RETRY_BACKOFF",bridge.connect()) };assertEquals(1,api.binds.size)
        now=18000;bridge.connect();api.deliver(1);val before=bridge.generation
        api.running=false;api.dead.onBinderDead();assertNull(bridge.remote)
        api.running=true;api.received.onBinderReceived();bridge.connect();api.deliver(2)
        assertTrue(bridge.generation>before);assertEquals("READY",bridge.connect())
    }
    @Test fun missingPermissionOrRootModeNeverBinds() {
        val api=Fake();val bridge=ShizukuBridge(RuntimeEnvironment.getApplication(),api,{0}) { }
        api.running=false;assertEquals("SHIZUKU_NOT_RUNNING",bridge.connect())
        api.running=true;api.granted=false;assertEquals("SHIZUKU_PERMISSION_REQUIRED",bridge.connect())
        api.granted=true;api.shellUid=0;assertEquals("SHIZUKU_ADB_SHELL_REQUIRED",bridge.connect())
        assertTrue(api.binds.isEmpty())
    }
    @Test fun refreshingHealthNeverChangesConnectionOrRunsASample() {
        val api = Fake()
        val bridge = ShizukuBridge(RuntimeEnvironment.getApplication(), api, { 0 }) { }
        repeat(3) { assertEquals("UNBOUND", bridge.health()["userService"]) }
        bridge.connect()
        api.deliver(0)
        val generation = bridge.generation
        api.remotelyGranted = false
        repeat(3) { assertEquals("REVOKED", bridge.health()["authorization"]) }
        assertEquals(1, api.binds.size)
        assertEquals(0, api.unbinds)
        assertEquals(generation, bridge.generation)
        assertEquals(0, api.samples)
        assertFalse(bridge.accept(generation) { fail("Revoked sample committed") })
    }

    @Test fun deadMainBinderTakesPrecedenceOverCachedGrant() {
        val api = Fake()
        val bridge = ShizukuBridge(RuntimeEnvironment.getApplication(), api, { 0 }) { }
        bridge.connect()
        api.deliver(0)
        api.running = false
        assertEquals("DISCONNECTED", bridge.health()["binder"])
        assertEquals("UNKNOWN", bridge.health()["authorization"])
        assertEquals("SHIZUKU_NOT_RUNNING", bridge.health()["availability"])
        assertEquals(0, api.unbinds)
    }
    @Test fun unverifiableCachedGrantIsSeparateFromVerifiedPermission() {
        val api = Fake().apply { remotelyGranted = null }
        val bridge = ShizukuBridge(RuntimeEnvironment.getApplication(), api, { 0 }) { }
        assertEquals("REPORTED_GRANTED", bridge.health()["authorization"])
        assertEquals("SHIZUKU_AUTHORIZATION_UNVERIFIED", bridge.connect())
        assertTrue(api.binds.isEmpty())
    }

    @Test fun reconnectRejectsInFlightResultAndWaitsForNewEvidence() {
        val api = Fake()
        val bridge = ShizukuBridge(RuntimeEnvironment.getApplication(), api, { 0 }) { }
        bridge.start()
        assertEquals("USER_SERVICE_CONNECTING", bridge.connect())
        assertEquals("USER_SERVICE_CONNECTING", SamplingDiagnosis(bridge.health(), emptyMap()).status)
        api.deliver(0)
        val old = bridge.lease()!!.first
        assertEquals("AWAITING_FIRST_VALID_SAMPLE", SamplingDiagnosis(bridge.health(), emptyMap()).status)
        api.dead.onBinderDead()
        api.received.onBinderReceived()
        bridge.connect()
        api.deliver(1)
        assertFalse(bridge.accept(old) { fail("Late old result entered the new session") })
        assertEquals("AWAITING_RECOVERY_SAMPLE", SamplingDiagnosis(bridge.health(), emptyMap()).status)
    }
}
