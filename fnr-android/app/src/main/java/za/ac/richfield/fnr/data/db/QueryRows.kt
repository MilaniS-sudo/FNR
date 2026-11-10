package za.ac.richfield.fnr.data.db

// Read-only result rows for joined queries and reports. Property names match
// the column aliases in the SQL in Daos.kt.

data class SubjectWithCount(
    val subjectId: Long,
    val subjectName: String,
    val description: String?,
    val noteCount: Int,
    val recordingCount: Int,
)

data class RecordingListItem(
    val recordingId: Long,
    val title: String,
    val subjectId: Long,
    val subjectName: String,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val status: RecordingStatus,
    val source: RecordingSource,
    val recordingDate: Long,
    val noteCount: Int,
)

data class FlashNoteView(
    val flashNoteId: Long,
    val title: String,
    val content: String,
    val keyConcept: String,
    val isFavourite: Boolean,
    val subjectId: Long,
    val subjectName: String,
    val recordingId: Long,
    val recordingTitle: String,
    val recordingDurationMs: Long,
    val filePath: String,
    val voiceNoteId: Long,
    val startMs: Long,
    val endMs: Long,
    val timesPlayed: Int,
    val createdAt: Long,
) {
    val lengthMs: Long get() = endMs - startMs
}

// R1 Study Activity
data class StudyActivityRow(
    val week: String,
    val subjectName: String,
    val sessions: Int,
    val totalMs: Long,
)

// R2 Flash Note Summary by Subject
data class SubjectSummaryRow(
    val subjectName: String,
    val notes: Int,
    val clipMs: Long,
    val lastRevised: Long?,
)

// R3 Revision Frequency
data class RevisionRow(
    val title: String,
    val subjectName: String,
    val timesPlayed: Int,
    val lastPlayed: Long?,
)

// R4 AI Processing (admin)
data class AiJobRow(
    val processingId: Long,
    val startedAt: Long,
    val processedAt: Long?,
    val recordingTitle: String,
    val userEmail: String,
    val modelUsed: String,
    val status: JobStatus,
    val errorMessage: String?,
)

// R5 User Accounts and Usage (admin)
data class UserUsageRow(
    val userId: Long,
    val firstName: String,
    val lastName: String,
    val email: String,
    val role: Role,
    val isActive: Boolean,
    val lockedUntil: Long?,
    val lastLoginAt: Long?,
    val recordings: Int,
    val storageBytes: Long,
)

data class DashboardStats(
    val minutesThisWeekMs: Long,
    val totalNotes: Int,
    val neverPlayed: Int,
)
