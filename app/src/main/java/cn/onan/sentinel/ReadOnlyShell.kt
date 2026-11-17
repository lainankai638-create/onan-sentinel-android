package cn.onan.sentinel

import android.os.Process
import java.util.concurrent.TimeUnit

class ReadOnlyShell : IReadOnlyShell.Stub() {
    override fun processes(target: String): String {
        require(PACKAGE_NAME.matches(target))
        val child = ProcessBuilder("/system/bin/ps", "-A", "-w", "-o", "PID,NAME")
            .redirectErrorStream(true).start()
        try {
            // Read concurrently so a large process table cannot fill the pipe.
            val reader = java.util.concurrent.FutureTask {
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.filter { line ->
                        val name = line.trim().split(Regex("\\s+"), limit = 2).getOrNull(1).orEmpty()
                        name == target || name.startsWith("$target:")
                    }.take(100).joinToString("\n")
                }
            }
            Thread(reader, "sentinel-ps").apply { isDaemon = true }.start()
            if (!child.waitFor(4, TimeUnit.SECONDS) || child.exitValue() != 0) {
                error("系统拒绝读取进程列表或读取超时")
            }
            return reader.get(1, TimeUnit.SECONDS)
        } finally {
            child.destroyForcibly()
        }
    }

    override fun destroy() = Process.killProcess(Process.myPid())
}
