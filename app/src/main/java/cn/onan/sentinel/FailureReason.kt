package cn.onan.sentinel

object FailureReason {
    private val safe = setOf("MIGRATION_VERSION_UNSUPPORTED","MIGRATION_SCHEMA_UNSUPPORTED",
        "MIGRATION_BACKUP_DIRECTORY_FAILED","MIGRATION_BACKUP_MISMATCH","MIGRATION_SPACE_REQUIRED",
        "MIGRATION_BACKUP_VERIFY_FAILED","MIGRATION_BACKUP_RENAME_FAILED","EXPORT_SPACE_REQUIRED","EXPORT_CANCELLED")
    fun of(error: Throwable): String {
        var current: Throwable? = error
        repeat(8) {
            val cause = current ?: return error.javaClass.simpleName
            if(cause.message in safe) return cause.message!!
            if(cause is android.database.sqlite.SQLiteFullException) return "LOCAL_STORAGE_FULL"
            if(cause is android.database.sqlite.SQLiteDatabaseCorruptException) return "DATABASE_CORRUPT_NO_RESET"
            current=cause.cause
        }
        return error.javaClass.simpleName
    }
}
