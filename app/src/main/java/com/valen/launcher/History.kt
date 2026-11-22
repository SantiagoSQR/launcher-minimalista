package com.valen.launcher

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(indices = [Index("day", "appId")])
data class LaunchEvent(
    val appId: String,
    val label: String,
    val day: String,
    val timestamp: Long,
    val outcome: String,
    val waitSeconds: Int?,
    val interruptionStartedAt: Long?,
    @PrimaryKey(autoGenerate = true) val id: Long = 0
)

@Dao
interface HistoryDao {
    @Insert suspend fun record(event: LaunchEvent)

    @Query("""
        SELECT day, appId, MAX(label) AS label,
            SUM(CASE WHEN outcome = 'opened' THEN 1 ELSE 0 END) AS openings,
            SUM(CASE WHEN outcome = 'opened' AND waitSeconds IS NOT NULL THEN 1 ELSE 0 END) AS completed,
            SUM(CASE WHEN outcome = 'cancelled' THEN 1 ELSE 0 END) AS cancelled
        FROM LaunchEvent
        GROUP BY day, appId
        ORDER BY day DESC
    """)
    fun days(): kotlinx.coroutines.flow.Flow<List<DailyActivity>>

    @Query("SELECT count(*) FROM LaunchEvent WHERE day = :day AND appId = :appId AND outcome = 'opened'")
    suspend fun openingCount(day: String, appId: String): Int
}

@Database(entities = [LaunchEvent::class, Widget::class], version = 3)
abstract class LauncherDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
    abstract fun widgets(): WidgetDao
}

internal val addWidgets = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE Widget (id INTEGER NOT NULL PRIMARY KEY, provider TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, columns INTEGER NOT NULL, rows INTEGER NOT NULL)")
    }
}

internal val addInterruptionStart = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE LaunchEvent ADD COLUMN interruptionStartedAt INTEGER")
        db.execSQL("UPDATE LaunchEvent SET interruptionStartedAt = timestamp WHERE waitSeconds IS NOT NULL")
    }
}
