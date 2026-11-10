package za.ac.richfield.fnr.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.ac.richfield.fnr.data.db.FnrDatabase
import za.ac.richfield.fnr.security.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only view of the encrypted database for the administrator (Database Viewer screen).
 *
 * The file is encrypted with SQLCipher, so desktop tools and Android Studio's Database Inspector
 * cannot open it; this screen reads it through the app's own unlocked connection instead.
 * Nothing can be changed here. Passwords stay hidden, and students' study content (transcripts and
 * note summaries) is masked, so the administrator sees the structure and records, not private notes.
 */
class DatabaseBrowser(private val db: FnrDatabase, private val session: SessionManager) {

    data class Column(val name: String, val type: String, val primaryKey: Boolean, val notNull: Boolean)
    data class ForeignKey(val column: String, val table: String, val target: String, val onDelete: String)
    data class Table(
        val name: String,
        val rowCount: Int,
        val columns: List<Column>,
        val foreignKeys: List<ForeignKey>,
    )
    data class Rows(val columns: List<String>, val rows: List<List<String>>, val total: Int, val shown: Int)

    private val sql get() = db.openHelper.readableDatabase

    /** Every app table with its row count, columns and foreign keys (Room's own tables left out). */
    suspend fun tables(): List<Table> = withContext(Dispatchers.IO) {
        session.requireAdmin()
        val names = mutableListOf<String>()
        sql.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%' ORDER BY name"
        ).use { c -> while (c.moveToNext()) names += c.getString(0) }
        names.map { name ->
            val count = sql.query("SELECT COUNT(*) FROM `$name`").use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            val columns = mutableListOf<Column>()
            sql.query("PRAGMA table_info(`$name`)").use { c ->
                val n = c.getColumnIndexOrThrow("name"); val t = c.getColumnIndexOrThrow("type")
                val pk = c.getColumnIndexOrThrow("pk"); val nn = c.getColumnIndexOrThrow("notnull")
                while (c.moveToNext()) columns += Column(c.getString(n), c.getString(t), c.getInt(pk) > 0, c.getInt(nn) == 1)
            }
            val keys = mutableListOf<ForeignKey>()
            sql.query("PRAGMA foreign_key_list(`$name`)").use { c ->
                val from = c.getColumnIndexOrThrow("from"); val table = c.getColumnIndexOrThrow("table")
                val to = c.getColumnIndexOrThrow("to"); val del = c.getColumnIndexOrThrow("on_delete")
                while (c.moveToNext()) keys += ForeignKey(c.getString(from), c.getString(table), c.getString(to), c.getString(del))
            }
            Table(name, count, columns, keys)
        }
    }

    /** The newest [limit] rows of one table, formatted for display. */
    suspend fun rows(table: String, limit: Int = 100): Rows = withContext(Dispatchers.IO) {
        session.requireAdmin()
        // Only names read from sqlite_master are allowed, so nothing typed can reach the SQL.
        val known = tables().firstOrNull { it.name == table } ?: throw IllegalArgumentException("Unknown table")
        val rows = mutableListOf<List<String>>()
        var columns = emptyList<String>()
        sql.query(SimpleSQLiteQuery("SELECT * FROM `${known.name}` ORDER BY rowid DESC LIMIT ?", arrayOf<Any>(limit))).use { c ->
            columns = c.columnNames.toList()
            while (c.moveToNext()) {
                rows += columns.indices.map { i ->
                    when {
                        c.isNull(i) -> "—"
                        c.getType(i) == android.database.Cursor.FIELD_TYPE_BLOB -> "[${c.getBlob(i).size} bytes]"
                        else -> display(columns[i], c.getString(i))
                    }
                }
            }
        }
        Rows(columns, rows, known.rowCount, rows.size)
    }

    private fun display(column: String, value: String): String = when {
        column in HIDDEN -> "•••• hidden"
        column in PRIVATE -> "•••• private (${value.length} chars)"
        column.endsWith("_path") -> value.substringAfterLast('/')
        column in TIMES -> value.toLongOrNull()?.let { DATE.format(Date(it)) } ?: value
        value.length > 40 -> value.take(40) + "…"
        else -> value
    }

    companion object {
        /** Security values: never shown, even to the administrator. */
        val HIDDEN = setOf("password_hash", "password_salt", "checksum")
        /** Students' own study content: the administrator manages accounts, not private notes (POPIA). */
        val PRIVATE = setOf("transcribed_text", "segments_json", "content")
        val TIMES = setOf(
            "created_at", "last_login_at", "locked_until", "recording_date", "started_at",
            "processed_at", "deleted_at", "played_at",
        )
        private val DATE = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
    }
}
