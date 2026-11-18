package cn.onan.sentinel

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** One bounded lane per evidence source, shared across service restarts in this app process. */
class BoundedCallLane {
    private val busy = AtomicBoolean(false)
    // One handoff slot covers the tiny interval between callable completion and worker return.
    // busy still rejects every submission while an operation (including a timed-out one) runs.
    private val executor = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "sentinel-bounded-probe").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    fun <T> call(timeoutMs: Long, action: () -> T): T {
        check(busy.compareAndSet(false, true)) { "SOURCE_CALL_STILL_RUNNING" }
        val finished = CountDownLatch(1)
        val future = FutureTask(Callable(action))
        try { executor.execute { try { future.run() } finally { busy.set(false);finished.countDown() } } }
        catch (e: RejectedExecutionException) { busy.set(false); throw e }
        try { return future.get(timeoutMs, TimeUnit.MILLISECONDS) }
        catch (e: Exception) { future.cancel(true); throw e }
        finally { if(future.isDone && !future.isCancelled) finished.await(timeoutMs,TimeUnit.MILLISECONDS) }
    }
    fun isBusy() = busy.get()
}

object ProbeLanes {
    private val process = BoundedCallLane()
    private val appOps = BoundedCallLane()
    fun forKind(kind: Int) = if (kind == Policy.PROCESS) process else appOps
}

class CollectionControl {
    private var epoch = 0L
    private var enabled = false
    @Synchronized fun resume(): Long { epoch++; enabled = true; return epoch }
    @Synchronized fun halt() { epoch++; enabled = false }
    @Synchronized fun token(): Long = epoch
    @Synchronized fun active(token: Long) = enabled && epoch == token
    @Synchronized fun accept(token: Long, write: () -> Unit): Boolean {
        if (!enabled || epoch != token) return false
        write(); return true
    }
}

/** Fresh callback token per bind; every invalidation makes prior callbacks permanently stale. */
class BindingEpoch<T> {
    var generation = 0L; private set
    var remote: T? = null; private set
    private var pending = false
    @Synchronized fun begin(): Long { invalidate(); pending = true; return generation }
    @Synchronized fun connected(token: Long, value: T): Boolean {
        if (token != generation || !pending) return false
        pending = false; remote = value; return true
    }
    @Synchronized fun invalidate() { generation++; remote = null; pending = false }
    @Synchronized fun matches(token: Long) = token == generation
}
