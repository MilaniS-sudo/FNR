package za.ac.richfield.fnr.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import za.ac.richfield.fnr.data.db.AiProcessingEntity
import za.ac.richfield.fnr.data.db.AudioRecordingEntity
import za.ac.richfield.fnr.data.db.BackupLogEntity
import za.ac.richfield.fnr.data.db.BackupStatus
import za.ac.richfield.fnr.data.db.BackupType
import za.ac.richfield.fnr.data.db.FlashNoteEntity
import za.ac.richfield.fnr.data.db.FnrDatabase
import za.ac.richfield.fnr.data.db.JobStatus
import za.ac.richfield.fnr.data.db.PlaybackLogEntity
import za.ac.richfield.fnr.data.db.RecordingStatus
import za.ac.richfield.fnr.data.db.SubjectEntity
import za.ac.richfield.fnr.data.db.TranscriptionEntity
import za.ac.richfield.fnr.data.db.VoiceFlashNoteEntity
import za.ac.richfield.fnr.security.AppSettings
import za.ac.richfield.fnr.security.PasswordHasher
import za.ac.richfield.fnr.security.SessionManager
import za.ac.richfield.fnr.util.Format
import za.ac.richfield.fnr.util.UserFacingException
import za.ac.richfield.fnr.work.AutoBackupWorker
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What goes inside a backup's data.json. */
data class BackupData(
    val format: String,
    val schemaVersion: Int,
    val exportedAt: Long,
    val subjects: List<SubjectEntity>,
    val recordings: List<AudioRecordingEntity>,
    val transcriptions: List<TranscriptionEntity>,
    val jobs: List<AiProcessingEntity>,
    val notes: List<FlashNoteEntity>,
    val voiceNotes: List<VoiceFlashNoteEntity>,
    val playbacks: List<PlaybackLogEntity>,
)

data class RestoreSummary(val subjects: Int, val recordings: Int, val notes: Int)

private data class BackupManifest(val format: String?, val schemaVersion: Int?, val app: String?)

/**
 * Module M6 Backup (Phase 4, sections 4.6.7 and 4.8.4).
 *
 * File format (.fnrbak):
 *   "FNRBAK1" | salt (16 bytes) | base IV (8 bytes) | chunks...
 *   chunk = lastFlag (1 byte) | length (4 bytes) | AES-256-GCM ciphertext of up to 1 MiB
 * Each chunk has its own IV (base IV + counter) and the last-chunk flag is authenticated,
 * so a changed, reordered or cut-off file is always detected. The key comes from the
 * user's password through PBKDF2, so a backup can be restored on a new phone.
 */
class BackupRepository(
    private val context: Context,
    private val db: FnrDatabase,
    private val session: SessionManager,
    private val settings: AppSettings,
    private val recordings: RecordingRepository,
) {
    private val gson = Gson()
    private val random = SecureRandom()

    fun observeHistory(): Flow<List<BackupLogEntity>> = db.backupDao().observeForUser(session.userId)

    fun autoBackupEnabled(): Boolean = settings.autoBackupEnabled(session.userId)

    // ------------------------------------------------------------ backup ---

    suspend fun createManualBackup(target: Uri, password: String): Long {
        val userId = session.requireUserId()
        checkPassword(userId, password)
        val salt = newBytes(16)
        val key = withContext(Dispatchers.Default) { PasswordHasher.derive(password, salt) }
        val name = displayName(target) ?: "backup.fnrbak"
        return try {
            val (size, checksum) = withContext(Dispatchers.IO) {
                val out = context.contentResolver.openOutputStream(target, "wt")
                    ?: throw UserFacingException("Could not open the backup location.")
                out.use { writeBackup(userId, key, salt, it) }
            }
            log(userId, BackupType.MANUAL, name, size, checksum, BackupStatus.SUCCESS)
            size
        } catch (e: Exception) {
            log(userId, BackupType.MANUAL, name, 0, "", BackupStatus.FAILED)
            throw e
        }
    }

    suspend fun createAutoBackup(userId: Long): File {
        val (salt, key) = settings.autoBackupKey(userId)
            ?: throw UserFacingException("Automatic backup is not switched on.")
        val dir = autoDir()
        val file = File(dir, "auto_u${userId}_${Format.fileStamp()}.fnrbak")
        try {
            val (size, checksum) = withContext(Dispatchers.IO) { file.outputStream().use { writeBackup(userId, key, salt, it) } }
            log(userId, BackupType.AUTO, file.name, size, checksum, BackupStatus.SUCCESS)
        } catch (e: Exception) {
            file.delete()
            log(userId, BackupType.AUTO, file.name, 0, "", BackupStatus.FAILED)
            throw e
        }
        // Keep only the newest three automatic backups
        autoBackups(userId).drop(3).forEach { it.delete() }
        return file
    }

    suspend fun enableAutoBackup(password: String) {
        val userId = session.requireUserId()
        checkPassword(userId, password)
        val salt = newBytes(16)
        val key = withContext(Dispatchers.Default) { PasswordHasher.derive(password, salt) }
        settings.saveAutoBackupKey(userId, salt, key)
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build())
            .setInputData(workDataOf(AutoBackupWorker.KEY_USER_ID to userId))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("auto_backup_$userId", ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun disableAutoBackup() {
        val userId = session.requireUserId()
        settings.clearAutoBackup(userId)
        WorkManager.getInstance(context).cancelUniqueWork("auto_backup_$userId")
    }

    fun latestAutoBackup(): File? = autoBackups(session.userId).firstOrNull()

    private fun autoDir(): File = (context.getExternalFilesDir("backups") ?: File(context.filesDir, "backups")).apply { mkdirs() }

    private fun autoBackups(userId: Long): List<File> =
        autoDir().listFiles { f -> f.name.startsWith("auto_u${userId}_") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Builds the zip, encrypts it to [out], returns (size in bytes, SHA-256 hex). */
    private suspend fun writeBackup(userId: Long, key: ByteArray, salt: ByteArray, out: OutputStream): Pair<Long, String> {
        val data = BackupData(
            format = FORMAT,
            schemaVersion = SCHEMA_VERSION,
            exportedAt = System.currentTimeMillis(),
            subjects = db.subjectDao().listForUser(userId),
            recordings = db.recordingDao().listForUser(userId),
            transcriptions = db.transcriptionDao().listForUser(userId),
            jobs = db.aiProcessingDao().listForUser(userId),
            notes = db.flashNoteDao().listForUser(userId),
            voiceNotes = db.voiceNoteDao().listForUser(userId),
            playbacks = db.playbackDao().listForUser(userId),
        )
        val zip = File.createTempFile("fnr_backup", ".zip", context.cacheDir)
        try {
            ZipOutputStream(zip.outputStream().buffered()).use { z ->
                z.putNextEntry(ZipEntry("manifest.json"))
                z.write(gson.toJson(BackupManifest(FORMAT, SCHEMA_VERSION, "FNR 1.0")).toByteArray())
                z.closeEntry()
                z.putNextEntry(ZipEntry("data.json"))
                z.write(gson.toJson(data).toByteArray())
                z.closeEntry()
                data.recordings.forEach { rec ->
                    val f = File(rec.filePath)
                    if (f.exists()) {
                        z.putNextEntry(ZipEntry("recordings/${rec.recordingId}_${f.name}"))
                        f.inputStream().use { it.copyTo(z) }
                        z.closeEntry()
                    }
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val counting = CountingOutputStream(DigestOutputStream(out, digest))
            zip.inputStream().buffered().use { encrypt(it, counting, key, salt) }
            counting.flush()
            return counting.count to digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            zip.delete()
        }
    }

    // ----------------------------------------------------------- restore ---

    suspend fun restore(source: Uri, password: String): RestoreSummary {
        val input = context.contentResolver.openInputStream(source) ?: throw UserFacingException("Could not open the backup file.")
        return input.use { restoreFrom(it, password) }
    }

    suspend fun restoreFile(file: File, password: String): RestoreSummary =
        file.inputStream().use { restoreFrom(it, password) }

    private suspend fun restoreFrom(input: InputStream, password: String): RestoreSummary {
        val userId = session.requireUserId()
        checkPassword(userId, password)
        val zip = File.createTempFile("fnr_restore", ".zip", context.cacheDir)
        val copied = mutableListOf<File>()
        try {
            withContext(Dispatchers.IO) { zip.outputStream().buffered().use { decrypt(input, it, password) } }
            val data = withContext(Dispatchers.IO) {
                ZipFile(zip).use { zf ->
                    val manifest = zf.getEntry("manifest.json") ?: throw UserFacingException("This is not an FNR backup.")
                    val m = zf.getInputStream(manifest).reader().use { gson.fromJson(it, BackupManifest::class.java) }
                    if (m?.format != FORMAT) throw UserFacingException("This is not an FNR backup.")
                    if ((m.schemaVersion ?: 0) > SCHEMA_VERSION) throw UserFacingException("This backup is from a newer version of the app.")
                    val dataEntry = zf.getEntry("data.json") ?: throw UserFacingException("The backup is damaged.")
                    val parsed = zf.getInputStream(dataEntry).reader().use { gson.fromJson(it, BackupData::class.java) }
                        ?: throw UserFacingException("The backup is damaged.")
                    // Copy recordings first, outside the database transaction
                    val dir = recordings.recordingsDir(userId)
                    val paths = mutableMapOf<Long, File>()
                    parsed.recordings.forEach { rec ->
                        val entry = zf.getEntry("recordings/${rec.recordingId}_${File(rec.filePath).name}") ?: return@forEach
                        val target = File(dir, "rst_${System.currentTimeMillis()}_${rec.recordingId}.${File(rec.filePath).extension}")
                        zf.getInputStream(entry).use { inp -> target.outputStream().use { inp.copyTo(it) } }
                        copied += target
                        paths[rec.recordingId] = target
                    }
                    parsed to paths
                }
            }
            val (backup, files) = data
            val oldFiles = db.recordingDao().listForUser(userId).map { File(it.filePath) }

            val summary = db.withTransaction {
                // Replace this user's data only. Deleting recordings cascades to notes, transcripts, logs.
                db.recordingDao().deleteForUser(userId)
                db.subjectDao().deleteForUser(userId)

                val subjectIds = mutableMapOf<Long, Long>()
                backup.subjects.forEach { s -> subjectIds[s.subjectId] = db.subjectDao().insert(s.copy(subjectId = 0, userId = userId)) }

                val recordingIds = mutableMapOf<Long, Long>()
                backup.recordings.forEach { r ->
                    val file = files[r.recordingId] ?: return@forEach
                    val subject = subjectIds[r.subjectId] ?: return@forEach
                    val status = if (r.status == RecordingStatus.DONE) RecordingStatus.DONE
                    else if (r.status == RecordingStatus.FAILED) RecordingStatus.FAILED else RecordingStatus.SAVED
                    recordingIds[r.recordingId] = db.recordingDao().insert(
                        r.copy(
                            recordingId = 0, userId = userId, subjectId = subject, status = status,
                            fileName = file.name, filePath = file.absolutePath, fileSizeBytes = file.length(),
                        )
                    )
                }
                backup.transcriptions.forEach { t ->
                    recordingIds[t.recordingId]?.let { db.transcriptionDao().insert(t.copy(transcriptionId = 0, recordingId = it)) }
                }
                backup.jobs.forEach { j ->
                    recordingIds[j.recordingId]?.let {
                        val status = if (j.status == JobStatus.RUNNING || j.status == JobStatus.QUEUED) JobStatus.FAILED else j.status
                        db.aiProcessingDao().insert(j.copy(processingId = 0, recordingId = it, status = status))
                    }
                }
                val noteIds = mutableMapOf<Long, Long>()
                backup.notes.forEach { n ->
                    val rec = recordingIds[n.recordingId] ?: return@forEach
                    val subject = subjectIds[n.subjectId] ?: return@forEach
                    noteIds[n.flashNoteId] = db.flashNoteDao().insert(
                        n.copy(flashNoteId = 0, userId = userId, subjectId = subject, recordingId = rec)
                    )
                }
                val voiceIds = mutableMapOf<Long, Long>()
                backup.voiceNotes.forEach { v ->
                    noteIds[v.flashNoteId]?.let { voiceIds[v.voiceNoteId] = db.voiceNoteDao().insert(v.copy(voiceNoteId = 0, flashNoteId = it)) }
                }
                backup.playbacks.forEach { p ->
                    voiceIds[p.voiceNoteId]?.let { db.playbackDao().insert(p.copy(playbackId = 0, voiceNoteId = it, userId = userId)) }
                }
                RestoreSummary(subjectIds.size, recordingIds.size, noteIds.size)
            }
            // Only after a successful commit: remove the replaced audio files
            withContext(Dispatchers.IO) { oldFiles.filter { it !in copied }.forEach { it.delete() } }
            copied.clear()
            return summary
        } finally {
            copied.forEach { it.delete() } // restore failed: remove half-copied files, data unchanged
            zip.delete()
        }
    }

    // ------------------------------------------------------------ crypto ---

    private fun encrypt(input: InputStream, output: OutputStream, key: ByteArray, salt: ByteArray) {
        val out = DataOutputStream(output)
        val baseIv = newBytes(8)
        out.write(MAGIC)
        out.write(salt)
        out.write(baseIv)
        val secret = SecretKeySpec(key, "AES")
        var current = ByteArray(CHUNK)
        var currentLen = readFully(input, current)
        var counter = 0
        while (true) {
            val next = ByteArray(CHUNK)
            val nextLen = readFully(input, next)
            val last = nextLen == 0
            val flag = if (last) 1 else 0
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, iv(baseIv, counter)))
            cipher.updateAAD(byteArrayOf(flag.toByte()))
            val ct = cipher.doFinal(current, 0, currentLen)
            out.writeByte(flag)
            out.writeInt(ct.size)
            out.write(ct)
            counter++
            if (last) break
            current = next
            currentLen = nextLen
        }
        out.flush()
    }

    private fun decrypt(input: InputStream, output: OutputStream, password: String) {
        val din = DataInputStream(BufferedInputStream(input))
        try {
            val magic = ByteArray(MAGIC.size).also { din.readFully(it) }
            if (!magic.contentEquals(MAGIC)) throw UserFacingException("This is not an FNR backup file.")
            val salt = ByteArray(16).also { din.readFully(it) }
            val baseIv = ByteArray(8).also { din.readFully(it) }
            val secret = SecretKeySpec(PasswordHasher.derive(password, salt), "AES")
            var counter = 0
            var last = false
            while (!last) {
                val flag = din.read()
                if (flag == -1) throw UserFacingException("The backup file is incomplete.")
                val len = din.readInt()
                if (len < 16 || len > CHUNK + 16) throw UserFacingException("The backup file is damaged.")
                val ct = ByteArray(len).also { din.readFully(it) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv(baseIv, counter)))
                cipher.updateAAD(byteArrayOf(flag.toByte()))
                output.write(cipher.doFinal(ct))
                counter++
                last = flag == 1
            }
        } catch (e: AEADBadTagException) {
            throw UserFacingException("Wrong password or the backup was changed. Nothing was restored.")
        } catch (e: EOFException) {
            throw UserFacingException("The backup file is incomplete. Nothing was restored.")
        }
    }

    // ----------------------------------------------------------- helpers ---

    private suspend fun checkPassword(userId: Long, password: String) {
        if (password.isEmpty()) throw UserFacingException("Enter your password")
        val user = db.userDao().findById(userId) ?: throw UserFacingException("Not logged in")
        val ok = withContext(Dispatchers.Default) { PasswordHasher.verify(password, user.passwordSalt, user.passwordHash) }
        if (!ok) throw UserFacingException("Incorrect password")
    }

    private suspend fun log(userId: Long, type: BackupType, name: String, size: Long, checksum: String, status: BackupStatus) {
        db.backupDao().insert(
            BackupLogEntity(userId = userId, type = type, fileName = name, sizeBytes = size, checksum = checksum, status = status)
        )
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun newBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    private fun iv(base: ByteArray, counter: Int): ByteArray =
        base + byteArrayOf((counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte())

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private class CountingOutputStream(private val inner: OutputStream) : OutputStream() {
        var count = 0L
            private set
        override fun write(b: Int) { inner.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); count += len }
        override fun flush() = inner.flush()
    }

    companion object {
        private val MAGIC = "FNRBAK1".toByteArray()
        private const val CHUNK = 1024 * 1024
        private const val FORMAT = "FNR-BACKUP"
        private const val SCHEMA_VERSION = 1

        fun suggestedFileName(name: String): String =
            "FNR_backup_${name.replace(Regex("[^A-Za-z0-9]"), "")}_${Format.fileStamp()}.fnrbak"
    }
}
