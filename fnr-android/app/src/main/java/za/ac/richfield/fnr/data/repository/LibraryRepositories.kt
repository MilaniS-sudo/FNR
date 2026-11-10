package za.ac.richfield.fnr.data.repository

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import za.ac.richfield.fnr.audio.AudioValidator
import za.ac.richfield.fnr.data.db.AiProcessingEntity
import za.ac.richfield.fnr.data.db.AudioRecordingEntity
import za.ac.richfield.fnr.data.db.DashboardStats
import za.ac.richfield.fnr.data.db.FlashNoteView
import za.ac.richfield.fnr.data.db.FnrDatabase
import za.ac.richfield.fnr.data.db.PlaybackLogEntity
import za.ac.richfield.fnr.data.db.RecordingListItem
import za.ac.richfield.fnr.data.db.RecordingSource
import za.ac.richfield.fnr.data.db.RecordingStatus
import za.ac.richfield.fnr.data.db.SubjectEntity
import za.ac.richfield.fnr.data.db.SubjectWithCount
import za.ac.richfield.fnr.data.remote.SegmentDto
import za.ac.richfield.fnr.security.SessionManager
import za.ac.richfield.fnr.security.Validators
import za.ac.richfield.fnr.util.Dates
import za.ac.richfield.fnr.util.Format
import za.ac.richfield.fnr.util.UserFacingException
import za.ac.richfield.fnr.work.ProcessRecordingWorker
import java.io.File

/** Subjects (FR-LIB-01). */
class SubjectRepository(private val db: FnrDatabase, private val session: SessionManager) {
    private val dao = db.subjectDao()

    fun observe(): Flow<List<SubjectEntity>> = dao.observeForUser(session.userId)
    fun observeWithCounts(): Flow<List<SubjectWithCount>> = dao.observeWithCounts(session.userId)

    suspend fun create(name: String, description: String): Long {
        val userId = session.requireUserId()
        validate(name, description)
        if (dao.findByName(userId, name.trim()) != null) throw UserFacingException("Subject already exists")
        return dao.insert(SubjectEntity(userId = userId, subjectName = name.trim(), description = description.trim().ifBlank { null }))
    }

    suspend fun update(subjectId: Long, name: String, description: String) {
        val subject = owned(subjectId)
        validate(name, description)
        val clash = dao.findByName(subject.userId, name.trim())
        if (clash != null && clash.subjectId != subjectId) throw UserFacingException("Subject already exists")
        dao.update(subject.copy(subjectName = name.trim(), description = description.trim().ifBlank { null }))
    }

    suspend fun delete(subjectId: Long) {
        val subject = owned(subjectId)
        val recordings = dao.recordingCount(subjectId)
        if (recordings > 0) {
            throw UserFacingException("This subject still has $recordings recording(s). Delete them first in Recordings.")
        }
        dao.delete(subject)
    }

    private suspend fun owned(subjectId: Long): SubjectEntity {
        val subject = dao.findById(subjectId) ?: throw UserFacingException("Subject not found")
        if (subject.userId != session.userId) throw SecurityException("Not your subject")
        return subject
    }

    private fun validate(name: String, description: String) {
        Validators.subjectName(name)?.let { throw UserFacingException(it) }
        Validators.description(description)?.let { throw UserFacingException(it) }
    }
}

/** Module M2 Recording and Upload, plus starting M3 processing. */
class RecordingRepository(
    private val context: Context,
    private val db: FnrDatabase,
    private val session: SessionManager,
) {
    private val dao = db.recordingDao()

    fun recordingsDir(userId: Long): File =
        File(context.filesDir, "recordings/u$userId").apply { mkdirs() }

    fun newRecordingFile(userId: Long): File =
        File(recordingsDir(userId), "rec_${System.currentTimeMillis()}.m4a")

    fun observeForUser(): Flow<List<RecordingListItem>> = dao.observeForUser(session.userId)
    fun observe(recordingId: Long): Flow<AudioRecordingEntity?> = dao.observe(recordingId)
    fun observeLatestJob(recordingId: Long): Flow<AiProcessingEntity?> = db.aiProcessingDao().observeLatest(recordingId)
    fun observeStorage(): Flow<Long> = dao.observeStorageBytes(session.userId)

    /** Called by the RecordingService when a recording stops (also if the app was interrupted). */
    suspend fun saveRecorded(userId: Long, subjectId: Long, title: String, file: File, fallbackMs: Long): Long {
        val duration = withContext(Dispatchers.IO) { AudioValidator.durationOf(file) } ?: fallbackMs
        try {
            AudioValidator.checkDuration(duration)
        } catch (e: UserFacingException) {
            file.delete()
            throw e
        }
        return dao.insert(
            AudioRecordingEntity(
                userId = userId,
                subjectId = subjectId,
                title = title.trim().ifBlank { defaultTitle() },
                fileName = file.name,
                filePath = file.absolutePath,
                source = RecordingSource.RECORDED,
                durationMs = duration,
                fileSizeBytes = file.length(),
            )
        )
    }

    suspend fun importUpload(subjectId: Long, title: String, uri: Uri): Long {
        val userId = session.requireUserId()
        Validators.recordingTitle(title)?.let { throw UserFacingException(it) }
        val info = withContext(Dispatchers.IO) { AudioValidator.validate(context, uri) }
        val target = File(recordingsDir(userId), "upl_${System.currentTimeMillis()}.${info.extension}")
        withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw UserFacingException("Could not open the file.")
            input.use { inp -> target.outputStream().use { out -> inp.copyTo(out) } }
        }
        if (target.length() > AudioValidator.MAX_BYTES) {
            target.delete()
            throw UserFacingException("File is larger than 25 MB.")
        }
        return dao.insert(
            AudioRecordingEntity(
                userId = userId,
                subjectId = subjectId,
                title = title.trim().ifBlank { info.displayName.substringBeforeLast('.').take(80) },
                fileName = target.name,
                filePath = target.absolutePath,
                source = RecordingSource.UPLOADED,
                durationMs = info.durationMs,
                fileSizeBytes = target.length(),
            )
        )
    }

    /** Queues AI processing (FR-AI-01); WorkManager waits for a network connection. */
    suspend fun enqueueProcessing(recordingId: Long) {
        val rec = owned(recordingId)
        if (rec.status == RecordingStatus.UPLOADING || rec.status == RecordingStatus.PROCESSING) {
            throw UserFacingException("This recording is already being processed.")
        }
        dao.setStatus(recordingId, RecordingStatus.QUEUED)
        val request = OneTimeWorkRequestBuilder<ProcessRecordingWorker>()
            .setInputData(workDataOf(ProcessRecordingWorker.KEY_RECORDING_ID to recordingId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("process_$recordingId", ExistingWorkPolicy.KEEP, request)
    }

    suspend fun delete(recordingId: Long) {
        val rec = owned(recordingId)
        WorkManager.getInstance(context).cancelUniqueWork("process_$recordingId")
        dao.delete(rec) // cascades to transcript, AI jobs, flash notes, voice notes, playback logs
        withContext(Dispatchers.IO) { File(rec.filePath).delete() }
    }

    private suspend fun owned(recordingId: Long): AudioRecordingEntity {
        val rec = dao.findById(recordingId) ?: throw UserFacingException("Recording not found")
        if (rec.userId != session.userId) throw SecurityException("Not your recording")
        return rec
    }

    private fun defaultTitle() = "Study session ${Format.dateTime(System.currentTimeMillis())}"
}

/** Module M4 Flash Note Library and Playback. */
class FlashNoteRepository(private val db: FnrDatabase, private val session: SessionManager) {
    private val dao = db.flashNoteDao()
    private val gson = Gson()

    fun observeNotes(subjectId: Long, favouritesOnly: Boolean, query: String): Flow<List<FlashNoteView>> =
        dao.observeNotes(session.userId, subjectId, favouritesOnly, query.trim())

    fun observeNote(id: Long): Flow<FlashNoteView?> = dao.observeNote(id)

    fun observeDashboard(): Flow<DashboardStats> = dao.observeDashboard(session.userId, Dates.startOfWeek())

    suspend fun updateText(flashNoteId: Long, title: String, summary: String) {
        Validators.noteTitle(title)?.let { throw UserFacingException(it) }
        Validators.noteSummary(summary)?.let { throw UserFacingException(it) }
        val note = owned(flashNoteId)
        dao.update(note.copy(title = title.trim(), content = summary.trim()))
    }

    suspend fun updateClip(note: FlashNoteView, startMs: Long, endMs: Long) {
        owned(note.flashNoteId)
        Validators.clip(startMs, endMs, note.recordingDurationMs)?.let { throw UserFacingException(it) }
        db.voiceNoteDao().updateClip(note.voiceNoteId, startMs, endMs)
    }

    suspend fun setFavourite(flashNoteId: Long, favourite: Boolean) {
        owned(flashNoteId)
        dao.setFavourite(flashNoteId, favourite)
    }

    suspend fun delete(flashNoteId: Long) {
        owned(flashNoteId)
        dao.softDelete(flashNoteId, System.currentTimeMillis())
    }

    /** FR-LIB-07: every playback is logged for the revision reports. */
    suspend fun logPlayback(voiceNoteId: Long, seconds: Int) {
        if (seconds < 1 || !session.isLoggedIn) return
        db.playbackDao().insert(PlaybackLogEntity(voiceNoteId = voiceNoteId, userId = session.userId, secondsListened = seconds))
    }

    suspend fun purgeOldDeleted() {
        dao.purgeDeletedBefore(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
    }

    /** The words spoken inside a clip, taken from the stored transcript. */
    suspend fun transcriptFor(recordingId: Long, startMs: Long, endMs: Long): String {
        val t = db.transcriptionDao().findByRecording(recordingId) ?: return ""
        val type = object : TypeToken<List<SegmentDto>>() {}.type
        val segments: List<SegmentDto> = runCatching { gson.fromJson<List<SegmentDto>>(t.segmentsJson, type) }.getOrNull() ?: return ""
        return segments.filter { it.endMs > startMs && it.startMs < endMs }.joinToString(" ") { it.text.orEmpty().trim() }
    }

    private suspend fun owned(id: Long) = (dao.findById(id) ?: throw UserFacingException("Flash note not found")).also {
        if (it.userId != session.userId) throw SecurityException("Not your flash note")
    }
}
