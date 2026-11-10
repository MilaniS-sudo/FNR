package za.ac.richfield.fnr.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import za.ac.richfield.fnr.util.UserFacingException
import java.io.File

data class AudioInfo(val displayName: String, val sizeBytes: Long, val durationMs: Long, val extension: String)

/** Upload checks from FR-REC-05 and Phase 4 section 4.6.4. */
object AudioValidator {
    const val MAX_BYTES = 25L * 1024 * 1024
    const val MIN_MS = 10_000L
    const val MAX_MS = 100L * 60 * 1000

    private val MIME_TO_EXT = mapOf(
        "audio/mpeg" to "mp3", "audio/mp3" to "mp3",
        "audio/mp4" to "m4a", "audio/x-m4a" to "m4a", "audio/m4a" to "m4a", "audio/mp4a-latm" to "m4a",
        "audio/aac" to "aac", "audio/x-aac" to "aac",
        "audio/wav" to "wav", "audio/x-wav" to "wav", "audio/wave" to "wav", "audio/vnd.wave" to "wav",
    )
    private val EXTENSIONS = setOf("mp3", "m4a", "mp4", "aac", "wav")

    fun validate(context: Context, uri: Uri): AudioInfo {
        val resolver = context.contentResolver
        var name = "audio"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val mime = resolver.getType(uri)?.lowercase()
        val fileExt = name.substringAfterLast('.', "").lowercase()
        val ext = MIME_TO_EXT[mime] ?: fileExt.takeIf { it in EXTENSIONS }
            ?: throw UserFacingException("Unsupported file type. Use MP3, M4A, AAC or WAV.")
        if (size > MAX_BYTES) throw UserFacingException("File is larger than 25 MB.")
        if (size == 0L) throw UserFacingException("The file is empty.")

        val duration = readDuration { it.setDataSource(context, uri) }
            ?: throw UserFacingException("The file is damaged or is not audio.")
        checkDuration(duration)
        return AudioInfo(name, size, duration, if (ext == "mp4") "m4a" else ext)
    }

    fun durationOf(file: File): Long? = readDuration { it.setDataSource(file.absolutePath) }

    fun checkDuration(durationMs: Long) {
        if (durationMs < MIN_MS) throw UserFacingException("Audio must be at least 10 seconds long.")
        if (durationMs > MAX_MS) throw UserFacingException("Audio must be at most 100 minutes long.")
    }

    private fun readDuration(source: (MediaMetadataRetriever) -> Unit): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            source(retriever)
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (hasAudio == "yes" || duration != null) duration else null
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
