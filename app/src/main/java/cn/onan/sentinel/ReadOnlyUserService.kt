package cn.onan.sentinel

import android.os.Process
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs as Shizuku shell. No shell interpreter, no arbitrary command/arguments, no write operations. */
open class ReadOnlyUserService : IReadOnlyProbe.Stub() {
    private val reader = Executors.newSingleThreadExecutor()

    @Synchronized override fun sample(kind: Int, target: Int): String = execute(kind,target,false)
    @Synchronized override fun snapshot(): String = execute(Policy.PROCESS,0,true)
    @Synchronized override fun resources(): String {
        if(Process.myUid()!=2000) return ResourceDiagnostics.failure("SHIZUKU_ADB_SHELL_REQUIRED").toString()
        val visible=ProcessSnapshots.fromJson(execute(Policy.PROCESS,0,true))
        if(visible.status!="OK") return ResourceDiagnostics.failure(visible.reason).toString()
        val hz=try { android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK) } catch(_:Exception) { -1L }
        return ResourceDiagnostics.collect(visible.rows,Process.myPid(),hz,ProcMetadata::read,
            android.os.SystemClock::elapsedRealtimeNanos,Thread::sleep).toString()
    }
    private fun execute(kind: Int, target: Int, snapshot: Boolean): String {
        if (Process.myUid() != 2000) return result("DENIED", "SHIZUKU_ADB_SHELL_REQUIRED")
        val command = try { Policy.command(kind, target) } catch (_: IllegalArgumentException) {
            return result("DENIED", "NOT_ALLOWLISTED")
        }
        var process: java.lang.Process? = null
        try {
            process = ProcessBuilder(command).redirectErrorStream(true).start()
            val p = process
            val output = reader.submit<String> {
                p.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    val bytes = ByteArrayOutputStream()
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (bytes.size() + n > Policy.MAX_OUTPUT) throw IllegalStateException("OUTPUT_LIMIT")
                        bytes.write(buffer, 0, n)
                    }
                    bytes.toString("UTF-8")
                }
            }
            if (!p.waitFor(4, TimeUnit.SECONDS)) {
                p.destroyForcibly(); output.cancel(true)
                return result("TIMEOUT", "COMMAND_TIMEOUT")
            }
            val raw = try { output.get(1, TimeUnit.SECONDS) } catch (_: Exception) {
                output.cancel(true); return result("UNAVAILABLE", "OUTPUT_LIMIT_OR_READ_FAILURE")
            }
            if (p.exitValue() != 0) return result("UNAVAILABLE", "COMMAND_EXIT_${p.exitValue()}")
            if(snapshot) return ProcessSnapshots.parse(raw).toJson()
            val evidence = if (kind == Policy.PROCESS) Parsers.process(raw, Policy.targets[target]) else Parsers.appOps(raw)
            return result(evidence.status, evidence.value)
        } catch (_: Exception) { return result("UNAVAILABLE", "COMMAND_EXECUTION_FAILED") }
        finally { process?.destroy() }
    }
    private fun result(status: String, evidence: String) = JSONObject()
        .put("status", status).put("evidence", evidence).toString()

    override fun destroy() { reader.shutdownNow(); kotlin.system.exitProcess(0) }
}

/** Separate component identity prevents an on-screen snapshot from unbinding the monitor service. */
class SnapshotUserService : ReadOnlyUserService()
class ResourceUserService : ReadOnlyUserService()
