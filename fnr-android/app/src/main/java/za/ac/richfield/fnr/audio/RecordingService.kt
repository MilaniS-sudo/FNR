package za.ac.richfield.fnr.audio

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import za.ac.richfield.fnr.FnrApp
import za.ac.richfield.fnr.R
import za.ac.richfield.fnr.util.Format
import za.ac.richfield.fnr.util.Notifier
import java.io.File

enum class RecPhase { IDLE, RECORDING, PAUSED, SAVING, SAVED, ERROR }

data class RecState(
    val phase: RecPhase = RecPhase.IDLE,
    val elapsedMs: Long = 0,
    val level: Int = 0, // 0-100, for the level meter
    val savedRecordingId: Long? = null,
    val message: String? = null,
)

/** Shared recorder state that the Record screen observes. */
object Recorder {
    internal val mutable = MutableStateFlow(RecState())
    val state: StateFlow<RecState> = mutable.asStateFlow()

    fun start(context: Context, userId: Long, subjectId: Long, title: String) {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_USER, userId)
            putExtra(RecordingService.EXTRA_SUBJECT, subjectId)
            putExtra(RecordingService.EXTRA_TITLE, title)
        }
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    fun pause(context: Context) = send(context, RecordingService.ACTION_PAUSE)
    fun resume(context: Context) = send(context, RecordingService.ACTION_RESUME)
    fun stop(context: Context) = send(context, RecordingService.ACTION_STOP)

    /** The Record screen calls this after it has shown a SAVED or ERROR result. */
    fun acknowledge() {
        val p = mutable.value.phase
        if (p == RecPhase.SAVED || p == RecPhase.ERROR) mutable.value = RecState()
    }

    private fun send(context: Context, action: String) {
        context.startService(Intent(context, RecordingService::class.java).setAction(action))
    }
}

/**
 * Foreground service that owns the MediaRecorder (FR-REC-01, FR-REC-02, FR-REC-06).
 * Recording continues with the screen off, and the file is saved even if the app is closed.
 * Format: AAC, mono, 16 kHz, 32 kbps -> about 14 MB per hour (NFR-AUD-01).
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var userId = -1L
    private var subjectId = -1L
    private var title = ""
    private var accumulatedMs = 0L
    private var segmentStart = 0L
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stopAndSave()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (recorder != null) return
        userId = intent.getLongExtra(EXTRA_USER, -1L)
        subjectId = intent.getLongExtra(EXTRA_SUBJECT, -1L)
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val container = (application as FnrApp).container

        val notification = NotificationCompat.Builder(this, Notifier.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Recording study session")
            .setContentText("Tap to return to Flash Note Recorder")
            .setContentIntent(Notifier.openAppIntent(this))
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)

        try {
            val out = container.recordings.newRecordingFile(userId)
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
            r.setMaxDuration(AudioValidator.MAX_MS.toInt())
            r.setOutputFile(out.absolutePath)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopAndSave()
            }
            r.prepare()
            r.start()
            recorder = r
            file = out
            accumulatedMs = 0
            segmentStart = SystemClock.elapsedRealtime()
            Recorder.mutable.value = RecState(RecPhase.RECORDING)
            startTicker()
        } catch (e: Exception) {
            fail("Could not start recording. Is another app using the microphone?")
        }
    }

    private fun pause() {
        val r = recorder ?: return
        if (Recorder.state.value.phase != RecPhase.RECORDING) return
        r.pause()
        accumulatedMs += SystemClock.elapsedRealtime() - segmentStart
        Recorder.mutable.value = Recorder.state.value.copy(phase = RecPhase.PAUSED, elapsedMs = accumulatedMs, level = 0)
    }

    private fun resume() {
        val r = recorder ?: return
        if (Recorder.state.value.phase != RecPhase.PAUSED) return
        r.resume()
        segmentStart = SystemClock.elapsedRealtime()
        Recorder.mutable.value = Recorder.state.value.copy(phase = RecPhase.RECORDING)
    }

    private fun elapsed(): Long =
        if (Recorder.state.value.phase == RecPhase.RECORDING) accumulatedMs + SystemClock.elapsedRealtime() - segmentStart
        else accumulatedMs

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val r = recorder ?: break
                val phase = Recorder.state.value.phase
                if (phase == RecPhase.RECORDING) {
                    val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
                    Recorder.mutable.value = Recorder.state.value.copy(
                        elapsedMs = elapsed(),
                        level = (amp * 100 / 32767).coerceIn(0, 100),
                    )
                }
                delay(200)
            }
        }
    }

    /** Stops the recorder and hands the file to the repository on a background thread. */
    private fun stopAndSave() {
        val r = recorder ?: run { stopSelf(); return }
        val total = elapsed()
        ticker?.cancel()
        recorder = null
        val stoppedOk = runCatching { r.stop() }.isSuccess
        r.release()
        val out = file
        file = null
        if (!stoppedOk || out == null) {
            out?.delete()
            fail("Recording too short (minimum 10 seconds).")
            return
        }
        Recorder.mutable.value = RecState(RecPhase.SAVING, elapsedMs = total)
        val container = (application as FnrApp).container
        container.appScope.launch {
            try {
                val id = container.recordings.saveRecorded(userId, subjectId, title, out, total)
                Recorder.mutable.value = RecState(RecPhase.SAVED, elapsedMs = total, savedRecordingId = id,
                    message = "Saved (${Format.duration(total)})")
            } catch (e: Exception) {
                out.delete()
                Recorder.mutable.value = RecState(RecPhase.ERROR, message = e.message ?: "Could not save the recording")
            } finally {
                scope.launch { finish() }
            }
        }
    }

    private fun fail(message: String) {
        recorder?.runCatching { release() }
        recorder = null
        Recorder.mutable.value = RecState(RecPhase.ERROR, message = message)
        finish()
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // App closed or the system stopped us while recording: keep what was recorded.
        if (recorder != null) stopAndSave()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "fnr.record.START"
        const val ACTION_PAUSE = "fnr.record.PAUSE"
        const val ACTION_RESUME = "fnr.record.RESUME"
        const val ACTION_STOP = "fnr.record.STOP"
        const val EXTRA_USER = "user"
        const val EXTRA_SUBJECT = "subject"
        const val EXTRA_TITLE = "title"
        private const val NOTIFICATION_ID = 1001
    }
}
