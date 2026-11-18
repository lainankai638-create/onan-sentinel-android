package cn.onan.sentinel

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import org.json.JSONObject
import java.io.Writer
import java.io.File

data class Event(val id: Long, val time: Long, val target: String, val type: String,
    val source: String, val status: String, val evidence: String)

class EventStore(private val context: Context) : SQLiteOpenHelper(context, "sentinel_v020.db", null, 2) {
    private val exportLock = Any()
    override fun onConfigure(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA journal_mode=TRUNCATE", null).use { it.moveToFirst() }
        db.execSQL("PRAGMA synchronous=FULL")
        val pageSize = db.rawQuery("PRAGMA page_size", null).use { it.moveToFirst(); it.getLong(0) }
        db.rawQuery("PRAGMA max_page_count=${Policy.DB_BYTES / pageSize}", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA journal_size_limit=1048576", null).use { it.moveToFirst() }
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, elapsed INTEGER NOT NULL, target TEXT NOT NULL, type TEXT NOT NULL, source TEXT NOT NULL, status TEXT NOT NULL, evidence TEXT NOT NULL)")
        db.execSQL("CREATE INDEX events_time ON events(timestamp)")
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        SchemaMigration.createV2(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "MIGRATION_VERSION_UNSUPPORTED" }
        SchemaMigration.backup(db, context.noBackupFilesDir)
        val max = db.rawQuery("SELECT coalesce(max(id),0) FROM events", null).use { it.moveToFirst(); it.getLong(0) }
        SchemaMigration.createV2(db, max)
    }
    @Synchronized fun meta(key: String): String = readableDatabase.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getString(0) else ""
    }
    @Synchronized fun setMeta(key: String, value: String) {
        writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply {
            put("key", key); put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }
    @Synchronized fun add(type: String, status: String, evidence: String, target: String = "monitor", source: String = "APP_LIFECYCLE", time: Long = System.currentTimeMillis()) {
        trim()
        writableDatabase.insertOrThrow("events", null, ContentValues().apply {
            put("timestamp", time); put("elapsed", SystemClock.elapsedRealtime()); put("target", target)
            put("type", type); put("source", source); put("status", status); put("evidence", evidence.take(Policy.MAX_EVIDENCE))
        })
    }
    @Synchronized fun atomic(block: EventStore.() -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    @Synchronized fun gap(reason: String, earliest: Long = System.currentTimeMillis()) = atomic {
        val previous = meta("gapAt").toLongOrNull()
        if (previous == null) {
            setMeta("gapAt", earliest.toString())
            add("GAP_BEGIN", "UNAVAILABLE", reason, time = earliest)
        } else if (earliest < previous) {
            setMeta("gapAt", earliest.toString())
            add("GAP_BOUND_ADJUSTED", "UNKNOWN", "Earlier conservative gap bound; exact interruption time unknown", time = earliest)
        }
        setMeta("reason", reason)
    }
    @Synchronized fun recovered() = atomic {
        if (openSourceGaps() != 0) return@atomic
        if (meta("gapAt").isNotEmpty()) {
            add("GAP_END", "PARTIAL", "BATCH_WITH_VALID_SAMPLE_COMPLETED; start=${meta("gapAt")}; intermittent sampling only")
            setMeta("gapAt", "")
            setMeta("reason", "")
        }
    }
    @Synchronized fun recoverUnclean() = atomic {
        if (meta("active") == "true") {
            val earliest = meta("lastTick").toLongOrNull() ?: System.currentTimeMillis()
            gap("PROCESS_LOST; exact stop time unknown; earliest bound is last heartbeat", earliest)
            allSourcesUnavailable("PROCESS_LOST_EXACT_TIME_UNKNOWN", earliest, "UNKNOWN_LOWER_BOUND")
            add("UNCLEAN_STOP_DETECTED", "UNKNOWN", "Discovered on next app launch; no samples fabricated")
            setMeta("active", "false")
            setMeta("state", "STOPPED_UNCLEAN")
        }
    }
    @Synchronized fun finishSession(owner: String, reason: String, stoppedAt: Long): Boolean {
        var finished=false
        atomic {
            if(meta("activeOwner") == owner) {
                gap(reason,stoppedAt);add("SESSION_STOP","UNAVAILABLE",reason,time=stoppedAt)
                allSourcesUnavailable(reason,stoppedAt,"KNOWN_STOP")
                setMeta("active","false");setMeta("state","STOPPED");finished=true
            }
        }
        return finished
    }
    @Synchronized fun trim() {
        val db = writableDatabase
        val count = db.rawQuery("SELECT count(*) FROM events", null).use { it.moveToFirst(); it.getLong(0) }
        val pageSize = db.rawQuery("PRAGMA page_size", null).use { it.moveToFirst(); it.getLong(0) }
        val pages = db.rawQuery("PRAGMA page_count", null).use { it.moveToFirst(); it.getLong(0) }
        val free = db.rawQuery("PRAGMA freelist_count", null).use { it.moveToFirst(); it.getLong(0) }
        if (count >= Policy.MAX_ROWS || (pages - free) * pageSize > 24L * 1024 * 1024) {
            val keep = minOf(Policy.RETAIN_ROWS.toLong(), count * 3 / 4)
            val bounds = db.rawQuery("SELECT min(id),max(id) FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT ?)", arrayOf(keep.toString())).use {
                it.moveToFirst(); it.getLong(0) to it.getLong(1)
            }
            db.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT ?)", arrayOf(keep))
            logRetention(count - keep, bounds.first, bounds.second, "CAPACITY")
            setMeta("retentionAt", System.currentTimeMillis().toString())
        }
    }
    @Synchronized fun latest(filter: String = "all", limit: Int = 200): List<Event> {
        val condition = when (filter) {
            "anomalies" -> "WHERE status NOT IN ('OK','PARTIAL') OR type='STATE_CHANGED'"
            "evidence" -> "WHERE source IN ('SHIZUKU_PS','SHIZUKU_APPOPS') AND status IN ('OK','PARTIAL')"
            "startup" -> "WHERE type IN ('SESSION_START','SESSION_STOP','BASELINE','STATE_CHANGED','UNCLEAN_STOP_DETECTED','GAP_BEGIN','GAP_END','GAP_BOUND_ADJUSTED','SHIZUKU_CONNECTED','SHIZUKU_DISCONNECTED')"
            else -> ""
        }
        return readableDatabase.rawQuery("SELECT id,timestamp,target,type,source,status,evidence FROM events $condition ORDER BY id DESC LIMIT ?", arrayOf(limit.coerceIn(1, Policy.MAX_ROWS).toString())).use { c ->
            buildList { while (c.moveToNext()) add(Event(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6))) }
        }
    }
    @Synchronized fun clearHistory() = atomic {
        check(meta("active") != "true") { "Stop monitoring before cleanup" }
        val count = writableDatabase.rawQuery("SELECT count(*) FROM events", null).use { it.moveToFirst(); it.getLong(0) }
        writableDatabase.delete("events", null, null)
        for (table in listOf("collection_runs", "source_state", "source_gaps")) writableDatabase.delete(table, null, null)
        logRetention(count, null, null, "USER_CLEANUP")
        setMeta("gapAt", "")
        setMeta("retentionAt", "")
        add("LOCAL_CLEANUP", "OK", "User explicitly deleted preview history")
        gap("USER_CLEARED_HISTORY")
    }
    private fun logRetention(count: Long, first: Long?, last: Long?, reason: String) {
        writableDatabase.execSQL("INSERT INTO retention_log(at,rows_removed,first_id,last_id,reason) VALUES(?,?,?,?,?)",
            arrayOf(System.currentTimeMillis(), count, first, last, reason))
        writableDatabase.execSQL("DELETE FROM retention_log WHERE id NOT IN (SELECT id FROM retention_log ORDER BY id DESC LIMIT 100)")
    }
    @Synchronized fun openSourceGaps(): Int = readableDatabase.rawQuery("SELECT count(*) FROM source_state WHERE open_gap_id IS NOT NULL", null).use { it.moveToFirst(); it.getInt(0) }
    @Synchronized fun sourceSummary(): String = readableDatabase.rawQuery("SELECT source,target,status,last_success FROM source_state ORDER BY target,source", null).use { c ->
        buildList { while (c.moveToNext()) add("${c.getString(1)} · ${c.getString(0)}: ${c.getString(2)}；最近成功=${if (c.isNull(3)) "未知" else c.getLong(3)}") }.joinToString("\n")
    }
    @Synchronized fun sourceUnavailable(source: String, target: String, reason: String,
        earliest: Long? = null, startKind: String = "UNKNOWN_LOWER_BOUND", status: String = "UNAVAILABLE") = atomic {
        val now = System.currentTimeMillis()
        writableDatabase.execSQL("INSERT OR IGNORE INTO source_state(source,target,status,reason) VALUES(?,?,?,?)", arrayOf(source,target,status,reason))
        val state = readableDatabase.rawQuery("SELECT last_success,open_gap_id FROM source_state WHERE source=? AND target=?", arrayOf(source,target)).use {
            it.moveToFirst(); (if (it.isNull(0)) null else it.getLong(0)) to (if (it.isNull(1)) null else it.getLong(1))
        }
        val start = earliest ?: if (startKind == "KNOWN_STOP") now else state.first ?: now
        var id = state.second
        if (id == null) {
            id = writableDatabase.insertOrThrow("source_gaps", null, ContentValues().apply {
                put("source",source); put("target",target); put("start_at",start); put("start_kind",startKind); put("reason",reason)
            })
            add("SOURCE_GAP_BEGIN", status, "$startKind:$reason", target, source, start)
        } else {
            writableDatabase.execSQL("UPDATE source_gaps SET start_at=?,start_kind=? WHERE id=? AND start_at>?", arrayOf(start,startKind,id,start))
        }
        writableDatabase.execSQL("UPDATE source_state SET status=?,reason=?,open_gap_id=? WHERE source=? AND target=?", arrayOf(status,reason,id,source,target))
    }
    @Synchronized fun allSourcesUnavailable(reason: String, earliest: Long? = null, startKind: String = "UNKNOWN_LOWER_BOUND") = atomic {
        val status = if (reason.contains("PERMISSION")) "PERMISSION_DENIED" else if(reason == "SHIZUKU_ADB_SHELL_REQUIRED") "UNSUPPORTED" else "UNAVAILABLE"
        for (target in Policy.targets) for (source in listOf("SHIZUKU_PS","SHIZUKU_APPOPS")) sourceUnavailable(source,target,reason,earliest,startKind,status)
    }
    @Synchronized fun recordSample(source: String, target: String, evidence: Evidence, type: String,
        started: Long, ended: Long, session: String, generation: Long) = atomic {
        val success = evidence.status in setOf("OK","PARTIAL")
        val status = when {
            evidence.status == "DENIED" -> "PERMISSION_DENIED"
            evidence.value.endsWith("FORMAT_UNKNOWN") -> "PARSE_ERROR"
            else -> evidence.status
        }
        writableDatabase.execSQL("INSERT INTO collection_runs(source,target,started,ended,status,reason,session,generation) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(source,target,started,ended,status,if (success) "" else evidence.value,session,generation))
        writableDatabase.execSQL("INSERT OR IGNORE INTO source_state(source,target,status,reason) VALUES(?,?,?,?)", arrayOf(source,target,status,""))
        if (success) {
            val gapId = readableDatabase.rawQuery("SELECT open_gap_id FROM source_state WHERE source=? AND target=?", arrayOf(source,target)).use { it.moveToFirst(); if (it.isNull(0)) null else it.getLong(0) }
            if (gapId != null) {
                writableDatabase.execSQL("UPDATE source_gaps SET end_at=? WHERE id=?", arrayOf(ended,gapId))
                add("SOURCE_GAP_END", "PARTIAL", "VALID_SAMPLE_OBSERVED; intermittent coverage only", target,source,ended)
            }
            writableDatabase.execSQL("UPDATE source_state SET status=?,reason='',last_success=?,last_attempt=?,open_gap_id=NULL WHERE source=? AND target=?", arrayOf(status,ended,ended,source,target))
        } else {
            sourceUnavailable(source,target,evidence.value,status=status)
            writableDatabase.execSQL("UPDATE source_state SET last_attempt=? WHERE source=? AND target=?", arrayOf(ended,source,target))
        }
        add(type,status,evidence.value,target,source,ended)
        // Auxiliary history is independently bounded; disclose eviction, never delete open gaps.
        for (table in listOf("collection_runs","source_gaps")) {
            val where = if (table == "source_gaps") "WHERE end_at IS NOT NULL" else ""
            val count = writableDatabase.rawQuery("SELECT count(*) FROM $table $where",null).use { it.moveToFirst(); it.getLong(0) }
            if (count > 2000) {
                writableDatabase.execSQL("DELETE FROM $table WHERE id IN (SELECT id FROM $table $where ORDER BY id LIMIT ?)", arrayOf(count-1000))
                logRetention(count-1000,null,null,"AUXILIARY_$table")
            }
        }
    }
    @Synchronized fun deleteMigrationBackup() {
        check(meta("active") != "true") { "Stop monitoring first" }
        check(readableDatabase.version == 2)
        val file=File(context.noBackupFilesDir,"sentinel-schema1-backup.db")
        if(file.exists()) check(SQLiteDatabase.deleteDatabase(file)) { "BACKUP_DELETE_FAILED" }
    }
    fun export(writer: Writer) = synchronized(exportLock) {
        // Bounded-memory private snapshot; external providers never hold the database lock.
        check(context.cacheDir.usableSpace >= Policy.DB_BYTES * 2) { "EXPORT_SPACE_REQUIRED" }
        val spool = File.createTempFile("sentinel-redacted-", ".jsonl", context.cacheDir)
        try {
            synchronized(this) { spool.bufferedWriter().use { writeSnapshot(it) } }
            spool.bufferedReader().use { input ->
                val buffer = CharArray(8192)
                while (true) {
                    check(!Thread.currentThread().isInterrupted) { "EXPORT_CANCELLED" }
                    val n = input.read(buffer); if (n < 0) break
                    writer.write(buffer,0,n)
                }
            }
        } finally { spool.delete() }
    }
    private fun alias(target: String) = Policy.targets.indexOf(target).let { if (it < 0) "MONITOR" else "TARGET_${it+1}" }
    private fun writeSnapshot(writer: Writer) {
        writer.appendLine(JSONObject().put("schema", 2).put("app", "0.3.0-p0-preview")
            .put("scope", "User 0; sampled process visibility and partial AppOps modes only")
            .put("privacy", "Targets aliased, PID removed, timestamps rounded to minute; timeline still sensitive")
            .put("retentionAtMinute", meta("retentionAt").toLongOrNull()?.let { it / 60000 * 60000 })
            .put("openGapAtMinute", meta("gapAt").toLongOrNull()?.let { it / 60000 * 60000 }).toString())
        val legacyMax = readableDatabase.rawQuery("SELECT legacy_max_id FROM schema_history WHERE version=2", null).use { it.moveToFirst(); it.getLong(0) }
        readableDatabase.rawQuery("SELECT id,timestamp,target,type,source,status,evidence FROM events ORDER BY id", null).use { c ->
        while (c.moveToNext()) {
            check(!Thread.currentThread().isInterrupted) { "EXPORT_CANCELLED" }
            val evidence = c.getString(6).replace(Regex("OBSERVED_PIDS=[0-9,]+"), "OBSERVED_PIDS=[REDACTED]")
                .replace(Regex("start=[0-9]+"), "start=[REDACTED]")
            writer.appendLine(JSONObject().put("id",c.getLong(0)).put("origin",if(c.getLong(0)<=legacyMax) "legacy_schema_1" else "schema_2")
                .put("timeMinute", c.getLong(1) / 60000 * 60000).put("target", alias(c.getString(2))).put("type", c.getString(3)).put("source", c.getString(4))
                .put("status", c.getString(5)).put("evidence", evidence).toString())
        } }
        readableDatabase.rawQuery("SELECT source,target,start_at,end_at,start_kind,reason FROM source_gaps ORDER BY id",null).use { c ->
            while(c.moveToNext()) writer.appendLine(JSONObject().put("type","SOURCE_GAP").put("source",c.getString(0)).put("target",alias(c.getString(1)))
                .put("startMinute",c.getLong(2)/60000*60000).put("endMinute",if(c.isNull(3)) JSONObject.NULL else c.getLong(3)/60000*60000)
                .put("startKind",c.getString(4)).put("reason",c.getString(5)).toString())
        }
        val reportAt=System.currentTimeMillis()
        readableDatabase.rawQuery("SELECT source,target,status,last_success FROM source_state ORDER BY source,target",null).use { state ->
            while(state.moveToNext()) {
                val args=arrayOf(state.getString(0),state.getString(1))
                var total=0;var valid=0;var last:Long?=null;var longest=0L;var clockReversed=false
                readableDatabase.rawQuery("SELECT ended,status FROM collection_runs WHERE source=? AND target=? ORDER BY id",args).use { c ->
                    while(c.moveToNext()) {
                        total++
                        if(c.getString(1) in setOf("OK","PARTIAL")) {
                            valid++;val time=c.getLong(0)
                            last?.let { if(time<it) clockReversed=true else longest=maxOf(longest,time-it) };last=time
                        }
                    }
                }
                var longestGap=0L
                readableDatabase.rawQuery("SELECT start_at,end_at FROM source_gaps WHERE source=? AND target=?",args).use { c ->
                    while(c.moveToNext()) { val end=if(c.isNull(1)) reportAt else c.getLong(1)
                        if(end<c.getLong(0)) clockReversed=true else longestGap=maxOf(longestGap,end-c.getLong(0)) }
                }
                writer.appendLine(JSONObject().put("type","SOURCE_HEALTH").put("source",args[0]).put("target",alias(args[1]))
                    .put("status",state.getString(2)).put("retainedAttempts",total).put("validRetainedAttempts",valid)
                    .put("maxRetainedSampleIntervalMinutes",longest/60000).put("maxRetainedGapMinutes",longestGap/60000)
                    .put("clockReversed",clockReversed).put("scope","retained history only; not event capture rate").toString())
            }
        }
        readableDatabase.rawQuery("SELECT at,rows_removed,reason FROM retention_log ORDER BY id",null).use { c ->
            while(c.moveToNext()) writer.appendLine(JSONObject().put("type","RETENTION").put("timeMinute",c.getLong(0)/60000*60000).put("removed",c.getLong(1)).put("reason",c.getString(2)).toString())
        }
    }
}
