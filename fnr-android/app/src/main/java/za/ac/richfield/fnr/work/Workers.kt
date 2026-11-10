package za.ac.richfield.fnr.work

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import za.ac.richfield.fnr.FnrApp
import za.ac.richfield.fnr.data.db.AiProcessingEntity
import za.ac.richfield.fnr.data.db.FlashNoteEntity
import za.ac.richfield.fnr.data.db.JobStatus
import za.ac.richfield.fnr.data.db.RecordingStatus
import za.ac.richfield.fnr.data.db.TranscriptionEntity
import za.ac.richfield.fnr.data.db.VoiceFlashNoteEntity
import za.ac.richfield.fnr.data.db.VoiceType
import za.ac.richfield.fnr.data.remote.GatewayClient
import za.ac.richfield.fnr.data.remote.ProgressRequestBody
import za.ac.richfield.fnr.util.Notifier
import za.ac.richfield.fnr.util.UserFacingException
import java.io.File

/**
 * Module M3 AI Processing (Phase 4, section 4.6.5, app side).
 * Runs in WorkManager so processing carries on even if the user leaves the app.
 */
class ProcessRecordingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as FnrApp).container
        val db = container.db
        val recordingId = inputData.getLong(KEY_RECORDING_ID, -1L)
        val rec = db.recordingDao().findById(recordingId) ?: return Result.failure()

        val jobId = db.aiProcessingDao().insert(
            AiProcessingEntity(recordingId = recordingId, modelUsed = "pending", status = JobStatus.RUNNING)
        )
        db.recordingDao().setStatus(recordingId, RecordingStatus.UPLOADING)

        return try {
            val file = File(rec.filePath)
            if (!file.exists()) throw UserFacingException("The audio file is missing from the device.")
            val mime = when (file.extension.lowercase()) {
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "aac" -> "audio/aac"
                else -> "audio/mp4"
            }
            val body = ProgressRequestBody(file.asRequestBody(mime.toMediaType())) {
                // Whole file sent: the Gateway is now transcribing and analysing
                db.recordingDao().setStatusBlocking(recordingId, RecordingStatus.PROCESSING)
            }
            val text = "text/plain".toMediaType()
            val response = container.gateway().process(
                MultipartBody.Part.createFormData("audio", file.name, body),
                "en".toRequestBody(text),
                rec.durationMs.toString().toRequestBody(text),
            )

            var created = 0
            db.withTransaction {
                db.transcriptionDao().insert(
                    TranscriptionEntity(
                        recordingId = recordingId,
                        transcribedText = response.text.orEmpty(),
                        segmentsJson = Gson().toJson(response.segments.orEmpty()),
                        language = response.language ?: "en",
                    )
                )
                for (point in response.keyPoints.orEmpty()) {
                    // Never trust times blindly: keep each clip inside the recording, 5-120 s long.
                    val start = (point.startMs ?: continue).coerceIn(0L, rec.durationMs)
                    var end = (point.endMs ?: continue).coerceIn(0L, rec.durationMs)
                    if (end - start > MAX_CLIP_MS) end = start + MAX_CLIP_MS
                    if (end - start < MIN_CLIP_MS) continue
                    val title = point.title?.trim().orEmpty().take(100).ifBlank { "Key point" }.let {
                        if (it.length < 3) "Key point" else it
                    }
                    val noteId = db.flashNoteDao().insert(
                        FlashNoteEntity(
                            userId = rec.userId,
                            subjectId = rec.subjectId,
                            recordingId = recordingId,
                            title = title,
                            content = point.summary?.trim().orEmpty().take(500),
                            keyConcept = point.concept?.trim().orEmpty().take(60),
                        )
                    )
                    db.voiceNoteDao().insert(
                        VoiceFlashNoteEntity(flashNoteId = noteId, voiceType = VoiceType.ORIGINAL, startMs = start, endMs = end)
                    )
                    created++
                }
                val job = db.aiProcessingDao().findById(jobId)!!
                db.aiProcessingDao().update(
                    job.copy(
                        status = JobStatus.SUCCESS,
                        modelUsed = response.models ?: "unknown",
                        processedAt = System.currentTimeMillis(),
                        errorMessage = if (created == 0) "No key points were found in this recording" else null,
                    )
                )
                db.recordingDao().setStatus(recordingId, RecordingStatus.DONE)
            }
            Notifier.processingDone(applicationContext, rec.title, created)
            Result.success(workDataOf(KEY_NOTES_CREATED to created))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val message = if (e is UserFacingException) e.message.orEmpty() else GatewayClient.friendlyError(e)
            db.aiProcessingDao().findById(jobId)?.let {
                db.aiProcessingDao().update(
                    it.copy(status = JobStatus.FAILED, processedAt = System.currentTimeMillis(), errorMessage = message)
                )
            }
            db.recordingDao().findById(recordingId)?.let { db.recordingDao().setStatus(recordingId, RecordingStatus.FAILED) }
            Notifier.processingFailed(applicationContext, rec.title, message)
            Result.failure(workDataOf(KEY_ERROR to message))
        }
    }

    companion object {
        const val KEY_RECORDING_ID = "recording_id"
        const val KEY_NOTES_CREATED = "notes_created"
        const val KEY_ERROR = "error"
        private const val MIN_CLIP_MS = 5_000L
        private const val MAX_CLIP_MS = 120_000L
    }
}

/** Weekly automatic backup (FR-BAK-03). Runs only while charging and idle. */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as FnrApp).container
        val userId = inputData.getLong(KEY_USER_ID, -1L)
        if (userId <= 0 || !container.settings.autoBackupEnabled(userId)) return Result.success()
        return try {
            container.backups.createAutoBackup(userId)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_USER_ID = "user_id"
    }
}
