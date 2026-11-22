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
    @PrimaryKey(autoGenerate = true) val id: Long = 0
)

@Dao
interface HistoryDao {
    @Insert suspend fun record(event: LaunchEvent)

    @Query("SELECT count(*) FROM LaunchEvent WHERE day = :day AND appId = :appId AND outcome = 'opened'")
    suspend fun openingCount(day: String, appId: String): Int
}

@Database(entities = [LaunchEvent::class, Widget::class], version = 2)
abstract class LauncherDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
    abstract fun widgets(): WidgetDao
}

internal val addWidgets = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE Widget (id INTEGER NOT NULL PRIMARY KEY, provider TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, columns INTEGER NOT NULL, rows INTEGER NOT NULL)")
    }
}
