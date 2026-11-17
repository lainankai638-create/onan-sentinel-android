package cn.onan.sentinel

import android.app.Application

class SentinelApp : Application() {
    lateinit var store: EventStore
        private set
    var storageFailure: String? = null
        private set
    override fun onCreate() {
        super.onCreate()
        store = EventStore(this)
        try { store.recoverUnclean() } catch (e: Exception) { storageFailure = "STORAGE_FAILURE: ${e.javaClass.simpleName}" }
    }
}
