package za.ac.richfield.fnr.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// All SQL uses bound parameters (:name), so user input can never change a query
// (protection against SQL injection, Phase 4 section 4.8.3).

@Dao
interface UserDao {
    @Insert suspend fun insert(user: UserEntity): Long
    @Update suspend fun update(user: UserEntity)

    @Query("SELECT * FROM users WHERE email = :email COLLATE NOCASE LIMIT 1")
    suspend fun findByEmail(email: String): UserEntity?

    @Query("SELECT * FROM users WHERE user_id = :id")
    suspend fun findById(id: Long): UserEntity?

    @Query("SELECT * FROM users WHERE user_id = :id")
    fun observe(id: Long): Flow<UserEntity?>

    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int

    @Query("SELECT * FROM users ORDER BY role, first_name")
    fun observeAll(): Flow<List<UserEntity>>
}

@Dao
interface SubjectDao {
    @Insert suspend fun insert(subject: SubjectEntity): Long
    @Update suspend fun update(subject: SubjectEntity)
    @Delete suspend fun delete(subject: SubjectEntity)

    @Query("SELECT * FROM subjects WHERE subject_id = :id")
    suspend fun findById(id: Long): SubjectEntity?

    @Query("SELECT * FROM subjects WHERE user_id = :userId AND subject_name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(userId: Long, name: String): SubjectEntity?

    @Query("SELECT * FROM subjects WHERE user_id = :userId ORDER BY subject_name COLLATE NOCASE")
    fun observeForUser(userId: Long): Flow<List<SubjectEntity>>

    @Query("SELECT * FROM subjects WHERE user_id = :userId")
    suspend fun listForUser(userId: Long): List<SubjectEntity>

    @Query(
        """SELECT s.subject_id AS subjectId, s.subject_name AS subjectName, s.description AS description,
           (SELECT COUNT(*) FROM flash_notes f WHERE f.subject_id = s.subject_id AND f.is_deleted = 0) AS noteCount,
           (SELECT COUNT(*) FROM audio_recordings r WHERE r.subject_id = s.subject_id) AS recordingCount
           FROM subjects s WHERE s.user_id = :userId ORDER BY s.subject_name COLLATE NOCASE"""
    )
    fun observeWithCounts(userId: Long): Flow<List<SubjectWithCount>>

    @Query("SELECT COUNT(*) FROM audio_recordings WHERE subject_id = :subjectId")
    suspend fun recordingCount(subjectId: Long): Int

    @Query("DELETE FROM subjects WHERE user_id = :userId")
    suspend fun deleteForUser(userId: Long)
}

@Dao
interface RecordingDao {
    @Insert suspend fun insert(recording: AudioRecordingEntity): Long
    @Update suspend fun update(recording: AudioRecordingEntity)
    @Delete suspend fun delete(recording: AudioRecordingEntity)

    @Query("SELECT * FROM audio_recordings WHERE recording_id = :id")
    suspend fun findById(id: Long): AudioRecordingEntity?

    @Query("SELECT * FROM audio_recordings WHERE recording_id = :id")
    fun observe(id: Long): Flow<AudioRecordingEntity?>

    @Query("UPDATE audio_recordings SET status = :status WHERE recording_id = :id")
    suspend fun setStatus(id: Long, status: RecordingStatus)

    /** Blocking version, used from the upload-progress callback on OkHttp's thread. */
    @Query("UPDATE audio_recordings SET status = :status WHERE recording_id = :id")
    fun setStatusBlocking(id: Long, status: RecordingStatus)

    @Query(
        """SELECT r.recording_id AS recordingId, r.title AS title, r.subject_id AS subjectId,
           s.subject_name AS subjectName, r.duration_ms AS durationMs, r.file_size_bytes AS fileSizeBytes,
           r.status AS status, r.source AS source, r.recording_date AS recordingDate,
           (SELECT COUNT(*) FROM flash_notes f WHERE f.recording_id = r.recording_id AND f.is_deleted = 0) AS noteCount
           FROM audio_recordings r JOIN subjects s ON s.subject_id = r.subject_id
           WHERE r.user_id = :userId ORDER BY r.recording_date DESC"""
    )
    fun observeForUser(userId: Long): Flow<List<RecordingListItem>>

    @Query("SELECT * FROM audio_recordings WHERE user_id = :userId")
    suspend fun listForUser(userId: Long): List<AudioRecordingEntity>

    @Query("SELECT COALESCE(SUM(file_size_bytes), 0) FROM audio_recordings WHERE user_id = :userId")
    fun observeStorageBytes(userId: Long): Flow<Long>

    @Query(
        """SELECT COALESCE(SUM(duration_ms), 0) FROM audio_recordings
           WHERE user_id = :userId AND recording_date >= :since"""
    )
    fun observeRecordedSince(userId: Long, since: Long): Flow<Long>

    @Query("DELETE FROM audio_recordings WHERE user_id = :userId")
    suspend fun deleteForUser(userId: Long)
}

@Dao
interface TranscriptionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transcription: TranscriptionEntity): Long

    @Query("SELECT * FROM transcriptions WHERE recording_id = :recordingId")
    suspend fun findByRecording(recordingId: Long): TranscriptionEntity?

    @Query("SELECT t.* FROM transcriptions t JOIN audio_recordings r ON r.recording_id = t.recording_id WHERE r.user_id = :userId")
    suspend fun listForUser(userId: Long): List<TranscriptionEntity>
}

@Dao
interface AiProcessingDao {
    @Insert suspend fun insert(job: AiProcessingEntity): Long
    @Update suspend fun update(job: AiProcessingEntity)

    @Query("SELECT * FROM ai_processing WHERE processing_id = :id")
    suspend fun findById(id: Long): AiProcessingEntity?

    @Query("SELECT * FROM ai_processing WHERE recording_id = :recordingId ORDER BY started_at DESC LIMIT 1")
    fun observeLatest(recordingId: Long): Flow<AiProcessingEntity?>

    @Query("SELECT a.* FROM ai_processing a JOIN audio_recordings r ON r.recording_id = a.recording_id WHERE r.user_id = :userId")
    suspend fun listForUser(userId: Long): List<AiProcessingEntity>
}

@Dao
interface FlashNoteDao {
    @Insert suspend fun insert(note: FlashNoteEntity): Long
    @Update suspend fun update(note: FlashNoteEntity)

    @Query("SELECT * FROM flash_notes WHERE flash_note_id = :id")
    suspend fun findById(id: Long): FlashNoteEntity?

    @Query(
        """SELECT fn.flash_note_id AS flashNoteId, fn.title AS title, fn.content AS content,
           fn.key_concept AS keyConcept, fn.is_favourite AS isFavourite, fn.subject_id AS subjectId,
           s.subject_name AS subjectName, fn.recording_id AS recordingId, r.title AS recordingTitle,
           r.duration_ms AS recordingDurationMs, r.file_path AS filePath, v.voice_note_id AS voiceNoteId,
           v.start_ms AS startMs, v.end_ms AS endMs,
           (SELECT COUNT(*) FROM playback_logs p WHERE p.voice_note_id = v.voice_note_id) AS timesPlayed,
           fn.created_at AS createdAt
           FROM flash_notes fn
           JOIN subjects s ON s.subject_id = fn.subject_id
           JOIN audio_recordings r ON r.recording_id = fn.recording_id
           JOIN voice_flash_notes v ON v.flash_note_id = fn.flash_note_id AND v.voice_type = 'ORIGINAL'
           WHERE fn.user_id = :userId AND fn.is_deleted = 0
             AND (:subjectId = -1 OR fn.subject_id = :subjectId)
             AND (:favouritesOnly = 0 OR fn.is_favourite = 1)
             AND (:query = '' OR fn.title LIKE '%' || :query || '%'
                  OR fn.key_concept LIKE '%' || :query || '%' OR fn.content LIKE '%' || :query || '%')
           ORDER BY fn.created_at DESC"""
    )
    fun observeNotes(userId: Long, subjectId: Long, favouritesOnly: Boolean, query: String): Flow<List<FlashNoteView>>

    @Query(
        """SELECT fn.flash_note_id AS flashNoteId, fn.title AS title, fn.content AS content,
           fn.key_concept AS keyConcept, fn.is_favourite AS isFavourite, fn.subject_id AS subjectId,
           s.subject_name AS subjectName, fn.recording_id AS recordingId, r.title AS recordingTitle,
           r.duration_ms AS recordingDurationMs, r.file_path AS filePath, v.voice_note_id AS voiceNoteId,
           v.start_ms AS startMs, v.end_ms AS endMs,
           (SELECT COUNT(*) FROM playback_logs p WHERE p.voice_note_id = v.voice_note_id) AS timesPlayed,
           fn.created_at AS createdAt
           FROM flash_notes fn
           JOIN subjects s ON s.subject_id = fn.subject_id
           JOIN audio_recordings r ON r.recording_id = fn.recording_id
           JOIN voice_flash_notes v ON v.flash_note_id = fn.flash_note_id AND v.voice_type = 'ORIGINAL'
           WHERE fn.flash_note_id = :id"""
    )
    fun observeNote(id: Long): Flow<FlashNoteView?>

    @Query("SELECT * FROM flash_notes WHERE user_id = :userId")
    suspend fun listForUser(userId: Long): List<FlashNoteEntity>

    @Query("UPDATE flash_notes SET is_favourite = :favourite WHERE flash_note_id = :id")
    suspend fun setFavourite(id: Long, favourite: Boolean)

    @Query("UPDATE flash_notes SET is_deleted = 1, deleted_at = :now WHERE flash_note_id = :id")
    suspend fun softDelete(id: Long, now: Long)

    /** Notes deleted more than 30 days ago are removed for good (Phase 3, business rules). */
    @Query("DELETE FROM flash_notes WHERE is_deleted = 1 AND deleted_at < :before")
    suspend fun purgeDeletedBefore(before: Long): Int

    @Query(
        """SELECT
           (SELECT COALESCE(SUM(duration_ms), 0) FROM audio_recordings WHERE user_id = :userId AND recording_date >= :since) AS minutesThisWeekMs,
           (SELECT COUNT(*) FROM flash_notes WHERE user_id = :userId AND is_deleted = 0) AS totalNotes,
           (SELECT COUNT(*) FROM flash_notes f JOIN voice_flash_notes v ON v.flash_note_id = f.flash_note_id
              WHERE f.user_id = :userId AND f.is_deleted = 0
              AND NOT EXISTS (SELECT 1 FROM playback_logs p WHERE p.voice_note_id = v.voice_note_id)) AS neverPlayed"""
    )
    fun observeDashboard(userId: Long, since: Long): Flow<DashboardStats>
}

@Dao
interface VoiceNoteDao {
    @Insert suspend fun insert(voiceNote: VoiceFlashNoteEntity): Long

    @Query("UPDATE voice_flash_notes SET start_ms = :startMs, end_ms = :endMs WHERE voice_note_id = :id")
    suspend fun updateClip(id: Long, startMs: Long, endMs: Long)

    @Query("SELECT v.* FROM voice_flash_notes v JOIN flash_notes f ON f.flash_note_id = v.flash_note_id WHERE f.user_id = :userId")
    suspend fun listForUser(userId: Long): List<VoiceFlashNoteEntity>
}

@Dao
interface PlaybackDao {
    @Insert suspend fun insert(log: PlaybackLogEntity): Long

    @Query("SELECT * FROM playback_logs WHERE user_id = :userId")
    suspend fun listForUser(userId: Long): List<PlaybackLogEntity>
}

@Dao
interface BackupDao {
    @Insert suspend fun insert(log: BackupLogEntity): Long

    @Query("SELECT * FROM backup_logs WHERE user_id = :userId ORDER BY created_at DESC")
    fun observeForUser(userId: Long): Flow<List<BackupLogEntity>>
}

@Dao
interface ReportDao {
    // R1 Study Activity: sessions and recorded time per week and subject
    @Query(
        """SELECT strftime('%Y-W%W', r.recording_date / 1000, 'unixepoch', 'localtime') AS week,
           s.subject_name AS subjectName, COUNT(*) AS sessions, SUM(r.duration_ms) AS totalMs
           FROM audio_recordings r JOIN subjects s ON s.subject_id = r.subject_id
           WHERE r.user_id = :userId AND r.recording_date BETWEEN :from AND :to
             AND (:subjectId = -1 OR r.subject_id = :subjectId)
           GROUP BY week, s.subject_id ORDER BY week DESC, subjectName"""
    )
    suspend fun studyActivity(userId: Long, from: Long, to: Long, subjectId: Long): List<StudyActivityRow>

    // R2 Flash Note Summary by Subject
    @Query(
        """SELECT s.subject_name AS subjectName,
           (SELECT COUNT(*) FROM flash_notes f WHERE f.subject_id = s.subject_id AND f.is_deleted = 0) AS notes,
           (SELECT COALESCE(SUM(v.end_ms - v.start_ms), 0) FROM voice_flash_notes v
              JOIN flash_notes f ON f.flash_note_id = v.flash_note_id
              WHERE f.subject_id = s.subject_id AND f.is_deleted = 0 AND v.voice_type = 'ORIGINAL') AS clipMs,
           (SELECT MAX(p.played_at) FROM playback_logs p
              JOIN voice_flash_notes v ON v.voice_note_id = p.voice_note_id
              JOIN flash_notes f ON f.flash_note_id = v.flash_note_id
              WHERE f.subject_id = s.subject_id) AS lastRevised
           FROM subjects s WHERE s.user_id = :userId AND (:subjectId = -1 OR s.subject_id = :subjectId)
           ORDER BY notes DESC"""
    )
    suspend fun notesBySubject(userId: Long, subjectId: Long): List<SubjectSummaryRow>

    // R3 Revision Frequency (plays counted inside the date range)
    @Query(
        """SELECT fn.title AS title, s.subject_name AS subjectName,
           COUNT(pl.playback_id) AS timesPlayed, MAX(pl.played_at) AS lastPlayed
           FROM flash_notes fn
           JOIN subjects s ON s.subject_id = fn.subject_id
           JOIN voice_flash_notes v ON v.flash_note_id = fn.flash_note_id
           LEFT JOIN playback_logs pl ON pl.voice_note_id = v.voice_note_id
                AND pl.played_at BETWEEN :from AND :to
           WHERE fn.user_id = :userId AND fn.is_deleted = 0
             AND (:subjectId = -1 OR fn.subject_id = :subjectId)
           GROUP BY fn.flash_note_id ORDER BY timesPlayed DESC, fn.title"""
    )
    suspend fun revisionFrequency(userId: Long, from: Long, to: Long, subjectId: Long): List<RevisionRow>

    // R4 AI Processing (admin, all users)
    @Query(
        """SELECT a.processing_id AS processingId, a.started_at AS startedAt, a.processed_at AS processedAt,
           r.title AS recordingTitle, u.email AS userEmail, a.model_used AS modelUsed,
           a.status AS status, a.error_message AS errorMessage
           FROM ai_processing a
           JOIN audio_recordings r ON r.recording_id = a.recording_id
           JOIN users u ON u.user_id = r.user_id
           WHERE a.started_at BETWEEN :from AND :to
           ORDER BY a.started_at DESC"""
    )
    suspend fun aiProcessing(from: Long, to: Long): List<AiJobRow>

    // R5 User Accounts and Usage (admin)
    @Query(
        """SELECT u.user_id AS userId, u.first_name AS firstName, u.last_name AS lastName, u.email AS email,
           u.role AS role, u.is_active AS isActive, u.locked_until AS lockedUntil, u.last_login_at AS lastLoginAt,
           (SELECT COUNT(*) FROM audio_recordings r WHERE r.user_id = u.user_id) AS recordings,
           (SELECT COALESCE(SUM(r.file_size_bytes), 0) FROM audio_recordings r WHERE r.user_id = u.user_id) AS storageBytes
           FROM users u ORDER BY u.role, u.first_name"""
    )
    suspend fun userUsage(): List<UserUsageRow>
}
