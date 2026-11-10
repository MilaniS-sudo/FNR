package za.ac.richfield.fnr.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        UserEntity::class,
        SubjectEntity::class,
        AudioRecordingEntity::class,
        TranscriptionEntity::class,
        AiProcessingEntity::class,
        FlashNoteEntity::class,
        VoiceFlashNoteEntity::class,
        PlaybackLogEntity::class,
        BackupLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class FnrDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun subjectDao(): SubjectDao
    abstract fun recordingDao(): RecordingDao
    abstract fun transcriptionDao(): TranscriptionDao
    abstract fun aiProcessingDao(): AiProcessingDao
    abstract fun flashNoteDao(): FlashNoteDao
    abstract fun voiceNoteDao(): VoiceNoteDao
    abstract fun playbackDao(): PlaybackDao
    abstract fun backupDao(): BackupDao
    abstract fun reportDao(): ReportDao

    companion object {
        const val NAME = "fnr.db"

        /**
         * Opens the database encrypted with SQLCipher (AES-256). The passphrase is a random
         * 32-byte key kept wrapped by the Android Keystore (see DatabaseKeyProvider), so copying
         * the fnr.db file off the phone gives an unreadable file (NFR-SEC-02).
         */
        fun build(context: Context, passphrase: ByteArray): FnrDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context.applicationContext, FnrDatabase::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
        }
    }
}
