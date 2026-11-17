package cn.onan.sentinel

object Policy {
    const val PROCESS = 1
    const val APPOPS = 2
    val targets = listOf("com.miui.securitycenter", "com.miui.guardprovider", "com.miui.powerkeeper")
    const val MAX_OUTPUT = 65536
    const val MAX_EVIDENCE = 4096
    const val MAX_ROWS = 20000
    const val RETAIN_ROWS = 16000
    const val DB_BYTES = 32L * 1024 * 1024
    fun command(kind: Int, target: Int): List<String> {
        require(target in targets.indices) { "TARGET_NOT_ALLOWED" }
        return when (kind) {
            PROCESS -> listOf("/system/bin/ps", "-A", "-w", "-o", "UID,PID,NAME")
            APPOPS -> listOf("/system/bin/cmd", "appops", "get", "--user", "0", targets[target])
            else -> throw IllegalArgumentException("COMMAND_NOT_ALLOWED")
        }
    }
}

data class Evidence(val status: String, val value: String)

object Parsers {
    fun process(raw: String, target: String): Evidence {
        val lines = raw.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty() || !Regex("\\s*UID\\s+PID\\s+NAME\\s*").matches(lines[0]))
            return Evidence("UNSUPPORTED", "PS_FORMAT_UNKNOWN")
        val matches = mutableListOf<String>()
        for (line in lines.drop(1)) {
            val columns = line.trim().split(Regex("\\s+"))
            if (columns.size != 3 || columns[0].toIntOrNull() == null || columns[1].toIntOrNull() == null)
                return Evidence("UNSUPPORTED", "PS_FORMAT_UNKNOWN")
            if (columns[0].toInt() in 0..99999 && (columns[2] == target || columns[2].startsWith("$target:"))) {
                // Retain only target PIDs, not the unrelated process listing or arbitrary names.
                matches += columns[1]
            }
        }
        return Evidence("OK", if (matches.isEmpty()) "NOT_OBSERVED_IN_PS" else "OBSERVED_PIDS=" + matches.sorted().joinToString(","))
    }

    fun appOps(raw: String): Evidence {
        if (raw.contains("SecurityException", true) || raw.contains("Permission Denial", true))
            return Evidence("DENIED", "APPOPS_PERMISSION_DENIED")
        if (raw.contains("Unknown package", true) || raw.contains("No UID", true))
            return Evidence("UNAVAILABLE", "TARGET_NOT_AVAILABLE_FOR_USER_0")
        if (raw.trim() == "No operations.") return Evidence("OK", "NO_RECORDED_OPERATIONS")
        val values = sortedSetOf<String>()
        val op = Regex("^\\s*(?:Uid mode:\\s*)?([A-Z][A-Z0-9_]{0,79}):\\s*(allow|ignore|deny|default|foreground|errored|ask)(?:[;\\s].*)?$", RegexOption.IGNORE_CASE)
        for (line in raw.lineSequence()) {
            val m = op.matchEntire(line) ?: continue
            val scope = if (line.trimStart().startsWith("Uid mode:", true)) "UID" else "PACKAGE"
            // Relative timestamps change on every poll; deliberately exclude them from comparisons.
            values += "$scope:${m.groupValues[1].uppercase()}=${m.groupValues[2].lowercase()}"
        }
        if (values.isEmpty()) return Evidence("UNSUPPORTED", "APPOPS_FORMAT_UNKNOWN")
        val normalized = values.joinToString("\n")
        return if (normalized.length > Policy.MAX_EVIDENCE) Evidence("UNAVAILABLE", "NORMALIZED_OUTPUT_LIMIT")
        else Evidence("PARTIAL", normalized)
    }
}

class Baselines {
    private val data = mutableMapOf<String, String>()
    fun compare(key: String, evidence: Evidence): String {
        if (evidence.status !in setOf("OK", "PARTIAL")) { data.remove(key); return "SAMPLE_UNAVAILABLE" }
        val previous = data.put(key, evidence.value)
        return when { previous == null -> "BASELINE"; previous != evidence.value -> "STATE_CHANGED"; else -> "SAMPLE" }
    }
    fun clear() = data.clear()
}
