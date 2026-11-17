package cn.onan.sentinel

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import org.json.JSONObject
import java.io.Writer

data class Event(val id: Long, val time: Long, val target: String, val type: String,
    val source: String, val status: String, val evidence: String)

class EventStore(context: Context) : SQLiteOpenHelper(context, "sentinel_v020.db", null, 1) {
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
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Explicit migration required: $oldVersion -> $newVersion")
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
            add("UNCLEAN_STOP_DETECTED", "UNKNOWN", "Discovered on next app launch; no samples fabricated")
            setMeta("active", "false")
            setMeta("state", "STOPPED_UNCLEAN")
        }
    }
    @Synchronized fun trim() {
        val db = writableDatabase
        val count = db.rawQuery("SELECT count(*) FROM events", null).use { it.moveToFirst(); it.getLong(0) }
        val pageSize = db.rawQuery("PRAGMA page_size", null).use { it.moveToFirst(); it.getLong(0) }
        val pages = db.rawQuery("PRAGMA page_count", null).use { it.moveToFirst(); it.getLong(0) }
        val free = db.rawQuery("PRAGMA freelist_count", null).use { it.moveToFirst(); it.getLong(0) }
        if (count >= Policy.MAX_ROWS || (pages - free) * pageSize > 24L * 1024 * 1024) {
            val keep = minOf(Policy.RETAIN_ROWS.toLong(), count * 3 / 4)
            db.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT ?)", arrayOf(keep))
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
        writableDatabase.delete("events", null, null)
        setMeta("gapAt", "")
        setMeta("retentionAt", "")
        add("LOCAL_CLEANUP", "OK", "User explicitly deleted preview history")
        gap("USER_CLEARED_HISTORY")
    }
    fun export(writer: Writer) {
        // Never hold the SQLite/store lock while writing to a potentially stalled document provider.
        val snapshot = synchronized(this) {
            Triple(meta("retentionAt"), meta("gapAt"), latest(limit = Policy.MAX_ROWS).asReversed())
        }
        writer.appendLine(JSONObject().put("schema", 1).put("app", "0.2.0-preview")
            .put("scope", "User 0; sampled process visibility and partial AppOps modes only")
            .put("privacy", "Targets aliased, PID removed, timestamps rounded to minute; timeline still sensitive")
            .put("retentionAtMinute", snapshot.first.toLongOrNull()?.let { it / 60000 * 60000 })
            .put("openGapAtMinute", snapshot.second.toLongOrNull()?.let { it / 60000 * 60000 }).toString())
        for (event in snapshot.third) {
            val target = Policy.targets.indexOf(event.target).let { if (it < 0) "MONITOR" else "TARGET_${it + 1}" }
            val evidence = event.evidence.replace(Regex("OBSERVED_PIDS=[0-9,]+"), "OBSERVED_PIDS=[REDACTED]")
                .replace(Regex("start=[0-9]+"), "start=[REDACTED]")
            writer.appendLine(JSONObject().put("timeMinute", event.time / 60000 * 60000)
                .put("target", target).put("type", event.type).put("source", event.source)
                .put("status", event.status).put("evidence", evidence).toString())
        }
    }
}
