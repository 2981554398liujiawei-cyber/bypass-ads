package li.songe.gkd.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One bounded matcher window for a possible ad. This is deliberately separate
 * from debug diagnostics: only finalized, unsuccessful sessions become product
 * failure records.
 */
@Entity(
    tableName = "bypass_detection_session",
    indices = [
        Index(value = ["end_time"]),
        Index(value = ["success", "final_failure_reason"]),
    ],
)
data class BypassDetectionSession(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "activity_name") val activityName: String?,
    @ColumnInfo(name = "start_time") val startTime: Long,
    @ColumnInfo(name = "end_time") val endTime: Long = 0L,
    @ColumnInfo(name = "candidate_seen") val candidateSeen: Boolean = false,
    @ColumnInfo(name = "matched_rules") val matchedRules: String = "",
    @ColumnInfo(name = "actions") val actions: String = "",
    @ColumnInfo(name = "success") val success: Boolean = false,
    @ColumnInfo(name = "final_failure_reason") val finalFailureReason: String? = null,
    @ColumnInfo(name = "diagnostic_timeline") val diagnosticTimeline: String = "",
    @ColumnInfo(name = "candidate_snapshots") val candidateSnapshots: String = "",
) {
    @Dao
    interface BypassDetectionSessionDao {
        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun upsert(session: BypassDetectionSession)

        @Query(
            """
            SELECT * FROM bypass_detection_session
            WHERE success = 0 AND final_failure_reason IS NOT NULL
            ORDER BY end_time DESC
            LIMIT 300
            """
        )
        fun queryFailures(): Flow<List<BypassDetectionSession>>

        @Query(
            """
            DELETE FROM bypass_detection_session
            WHERE end_time > 0 AND end_time < :cutoff
            """
        )
        suspend fun deleteBefore(cutoff: Long): Int
    }
}
