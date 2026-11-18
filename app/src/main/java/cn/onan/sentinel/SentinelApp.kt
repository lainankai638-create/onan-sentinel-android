package cn.onan.sentinel

import android.app.Application

class SentinelApp : Application() {
    lateinit var store: EventStore
        private set
    var storageFailure: String? = null
        private set
    override fun onCreate() {
        super.onCreate()
        // Remove only our redacted temporary exports left by a killed previous app process.
        cacheDir.listFiles()?.filter { it.name.startsWith("sentinel-redacted-") && (it.name.endsWith(".jsonl") || it.name.endsWith(".zip")) }?.forEach { it.delete() }
        store = EventStore(this)
        try { store.recoverUnclean() } catch (e: Exception) { storageFailure = "STORAGE_FAILURE: ${FailureReason.of(e)}" }
    }
}
