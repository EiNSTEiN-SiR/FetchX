package org.fetchx.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class Engine { ARIA2, YTDLP }

enum class Status { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELED }

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val pageUrl: String? = null,
    val title: String,
    val engine: Engine,
    val status: Status = Status.QUEUED,
    /** yt-dlp format selector, null for aria2. */
    val formatSelector: String? = null,
    /** JSON-encoded yt-dlp options (codec, container, audio settings, extras). */
    val ytOptions: String? = null,
    val audioOnly: Boolean = false,
    val threads: Int = 16,
    /** SAF tree uri string; null means public Downloads/FetchX through MediaStore. */
    val destTreeUri: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    /** Extra headers, one "Name: value" per line. */
    val headers: String? = null,
    val useCookies: Boolean = false,
    val totalBytes: Long = 0,
    val doneBytes: Long = 0,
    val speedBps: Long = 0,
    val etaSec: Long = -1,
    val connections: Int = 0,
    /** Current aria2 gid, if any. */
    val gid: String? = null,
    /** Fixed output file name for aria2, so a refreshed URL resumes into the same partial file. */
    val outName: String? = null,
    val outputUri: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0,
)

class Converters {
    @TypeConverter fun engineToString(e: Engine): String = e.name
    @TypeConverter fun stringToEngine(s: String): Engine = Engine.valueOf(s)
    @TypeConverter fun statusToString(s: Status): String = s.name
    @TypeConverter fun stringToStatus(s: String): Status = Status.valueOf(s)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED','RUNNING','PAUSED','FAILED') ORDER BY createdAt DESC")
    fun observeActive(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status IN ('COMPLETED','CANCELED') ORDER BY CASE WHEN completedAt = 0 THEN createdAt ELSE completedAt END DESC")
    fun observeCompleted(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY createdAt ASC")
    suspend fun byStatus(status: Status): List<DownloadEntity>

    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'RUNNING'")
    suspend fun runningCount(): Int

    @Insert
    suspend fun insert(e: DownloadEntity): Long

    @Update
    suspend fun update(e: DownloadEntity)

    @Query("UPDATE downloads SET status = :status, error = :error, speedBps = 0, etaSec = -1, connections = 0 WHERE id = :id")
    suspend fun setStatus(id: Long, status: Status, error: String? = null)

    @Query("UPDATE downloads SET totalBytes = :total, doneBytes = :done, speedBps = :speed, etaSec = :eta, connections = :conns WHERE id = :id")
    suspend fun setProgress(id: Long, total: Long, done: Long, speed: Long, eta: Long, conns: Int)

    @Query("UPDATE downloads SET gid = :gid WHERE id = :id")
    suspend fun setGid(id: Long, gid: String?)

    @Query("UPDATE downloads SET url = :url, status = 'PAUSED', error = NULL, gid = NULL WHERE id = :id")
    suspend fun refreshUrl(id: Long, url: String)

    @Query("UPDATE downloads SET status = 'COMPLETED', outputUri = :outputUri, completedAt = :at, speedBps = 0, etaSec = -1, connections = 0, error = NULL WHERE id = :id")
    suspend fun markCompleted(id: Long, outputUri: String?, at: Long)

    @Query("UPDATE downloads SET status = 'PAUSED', speedBps = 0, etaSec = -1, connections = 0 WHERE status IN ('RUNNING','QUEUED')")
    suspend fun pauseAllInterrupted()

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM downloads WHERE status IN ('COMPLETED','CANCELED')")
    suspend fun clearFinished()
}

@Database(entities = [DownloadEntity::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDb : RoomDatabase() {
    abstract fun downloads(): DownloadDao

    companion object {
        @Volatile private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "fetchx.db")
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
