package za.ac.richfield.fnr.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// The nine tables from Phase 4, section 4.5. Enums are stored by name, so the
// database can only ever hold the allowed values (domain integrity).

enum class Role { STUDENT, ADMIN }
enum class RecordingSource { RECORDED, UPLOADED }
enum class RecordingStatus { SAVED, QUEUED, UPLOADING, PROCESSING, DONE, FAILED }
enum class JobStatus { QUEUED, RUNNING, SUCCESS, FAILED }
enum class VoiceType { ORIGINAL, TTS }
enum class BackupType { MANUAL, AUTO }
enum class BackupStatus { SUCCESS, FAILED }

@Entity(tableName = "users", indices = [Index(value = ["email"], unique = true)])
data class UserEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "user_id") val userId: Long = 0,
    @ColumnInfo(name = "first_name") val firstName: String,
    @ColumnInfo(name = "last_name") val lastName: String,
    @ColumnInfo(name = "email") val email: String,
    @ColumnInfo(name = "password_hash") val passwordHash: String,
    @ColumnInfo(name = "password_salt") val passwordSalt: String,
    @ColumnInfo(name = "role") val role: Role = Role.STUDENT,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
    @ColumnInfo(name = "failed_logins") val failedLogins: Int = 0,
    @ColumnInfo(name = "locked_until") val lockedUntil: Long? = null,
    @ColumnInfo(name = "purpose") val purpose: String? = null,
    @ColumnInfo(name = "last_login_at") val lastLoginAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
) {
    val fullName: String get() = "$firstName $lastName"
    fun isLocked(now: Long = System.currentTimeMillis()): Boolean = (lockedUntil ?: 0L) > now
}

@Entity(
    tableName = "subjects",
    foreignKeys = [ForeignKey(
        entity = UserEntity::class, parentColumns = ["user_id"], childColumns = ["user_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("user_id"), Index(value = ["user_id", "subject_name"], unique = true)],
)
data class SubjectEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "subject_id") val subjectId: Long = 0,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "subject_name") val subjectName: String,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "audio_recordings",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class, parentColumns = ["user_id"], childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SubjectEntity::class, parentColumns = ["subject_id"], childColumns = ["subject_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("user_id"), Index("subject_id")],
)
data class AudioRecordingEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "recording_id") val recordingId: Long = 0,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "subject_id") val subjectId: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "source") val source: RecordingSource,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "file_size_bytes") val fileSizeBytes: Long,
    @ColumnInfo(name = "status") val status: RecordingStatus = RecordingStatus.SAVED,
    @ColumnInfo(name = "recording_date") val recordingDate: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "transcriptions",
    foreignKeys = [ForeignKey(
        entity = AudioRecordingEntity::class, parentColumns = ["recording_id"], childColumns = ["recording_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["recording_id"], unique = true)],
)
data class TranscriptionEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "transcription_id") val transcriptionId: Long = 0,
    @ColumnInfo(name = "recording_id") val recordingId: Long,
    @ColumnInfo(name = "transcribed_text") val transcribedText: String,
    @ColumnInfo(name = "segments_json") val segmentsJson: String,
    @ColumnInfo(name = "language") val language: String = "en",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "ai_processing",
    foreignKeys = [ForeignKey(
        entity = AudioRecordingEntity::class, parentColumns = ["recording_id"], childColumns = ["recording_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("recording_id"), Index("started_at")],
)
data class AiProcessingEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "processing_id") val processingId: Long = 0,
    @ColumnInfo(name = "recording_id") val recordingId: Long,
    @ColumnInfo(name = "model_used") val modelUsed: String,
    @ColumnInfo(name = "status") val status: JobStatus,
    @ColumnInfo(name = "started_at") val startedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "processed_at") val processedAt: Long? = null,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
)

@Entity(
    tableName = "flash_notes",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class, parentColumns = ["user_id"], childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SubjectEntity::class, parentColumns = ["subject_id"], childColumns = ["subject_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AudioRecordingEntity::class, parentColumns = ["recording_id"], childColumns = ["recording_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("user_id"), Index("subject_id"), Index("recording_id")],
)
data class FlashNoteEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "flash_note_id") val flashNoteId: Long = 0,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "subject_id") val subjectId: Long,
    @ColumnInfo(name = "recording_id") val recordingId: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "key_concept") val keyConcept: String,
    @ColumnInfo(name = "is_favourite") val isFavourite: Boolean = false,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean = false,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "voice_flash_notes",
    foreignKeys = [ForeignKey(
        entity = FlashNoteEntity::class, parentColumns = ["flash_note_id"], childColumns = ["flash_note_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("flash_note_id")],
)
data class VoiceFlashNoteEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "voice_note_id") val voiceNoteId: Long = 0,
    @ColumnInfo(name = "flash_note_id") val flashNoteId: Long,
    @ColumnInfo(name = "voice_type") val voiceType: VoiceType,
    // ORIGINAL: the clip is played straight from the recording between these times (no copy).
    @ColumnInfo(name = "start_ms") val startMs: Long? = null,
    @ColumnInfo(name = "end_ms") val endMs: Long? = null,
    @ColumnInfo(name = "audio_path") val audioPath: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "playback_logs",
    foreignKeys = [
        ForeignKey(
            entity = VoiceFlashNoteEntity::class, parentColumns = ["voice_note_id"], childColumns = ["voice_note_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = UserEntity::class, parentColumns = ["user_id"], childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("voice_note_id"), Index("user_id"), Index("played_at")],
)
data class PlaybackLogEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "playback_id") val playbackId: Long = 0,
    @ColumnInfo(name = "voice_note_id") val voiceNoteId: Long,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "played_at") val playedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "seconds_listened") val secondsListened: Int,
)

@Entity(
    tableName = "backup_logs",
    foreignKeys = [ForeignKey(
        entity = UserEntity::class, parentColumns = ["user_id"], childColumns = ["user_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("user_id")],
)
data class BackupLogEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "backup_id") val backupId: Long = 0,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "type") val type: BackupType,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "checksum") val checksum: String,
    @ColumnInfo(name = "status") val status: BackupStatus,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)
