package cn.onan.sentinel

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class CollectionSafetyTest {
    @Test fun lateConnectedCallbacksCannotReplaceCurrentRemote() {
        val state = BindingEpoch<String>()
        val old = state.begin(); state.invalidate(); val fresh = state.begin()
        assertFalse(state.connected(old,"stale"))
        assertTrue(state.connected(fresh,"fresh"))
        assertFalse(state.connected(fresh,"duplicate"))
        assertEquals("fresh",state.remote)
        state.invalidate(); assertNull(state.remote); assertFalse(state.matches(fresh))
    }
    @Test fun pauseStopAndResumeRejectEveryOldResult() {
        val state = CollectionControl(); var writes = 0
        val before = state.resume()
        assertTrue(state.accept(before) { writes++ })
        state.halt()
        assertFalse(state.accept(before) { writes++ })
        val after = state.resume()
        assertFalse(state.accept(before) { writes++ })
        assertTrue(state.accept(after) { writes++ })
        state.halt(); assertFalse(state.active(after)); assertEquals(2,writes)
    }
    @Test fun uninterruptibleTimeoutCannotCreateThreadsOrQueueMoreCalls() {
        val lane = BoundedCallLane(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val count = AtomicInteger()
        try {
            assertThrows(TimeoutException::class.java) {
                lane.call(100) {
                    count.incrementAndGet(); entered.countDown()
                    while(release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                    "late"
                }
            }
            assertTrue(entered.await(2,TimeUnit.SECONDS)); assertTrue(lane.isBusy())
            repeat(100) { assertThrows(IllegalStateException::class.java) { lane.call(10) { count.incrementAndGet() } } }
            assertEquals(1,count.get())
            assertEquals("other source",BoundedCallLane().call(1000) { "other source" })
        } finally { release.countDown() }
    }
    @Test fun failedCallCanBeRetriedWithoutReplacingExecutor() {
        val lane = BoundedCallLane()
        assertThrows(java.util.concurrent.ExecutionException::class.java) { lane.call(1000) { error("read failure") } }
        // The exception's task-finally may finish a moment after Future.get returns.
        val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
        while(lane.isBusy() && System.nanoTime()<deadline) Thread.yield()
        assertFalse(lane.isBusy())
        assertEquals(42,lane.call(1000) { 42 })
    }
}
