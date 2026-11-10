package za.ac.richfield.fnr.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** An error whose message is written for the user and can be shown as is. */
class UserFacingException(message: String) : Exception(message)

object Format {
    // A new SimpleDateFormat per call: the class is not thread-safe and these run on several threads.
    fun dateTime(ms: Long?): String =
        if (ms == null || ms <= 0) "—" else SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

    fun date(ms: Long?): String =
        if (ms == null || ms <= 0) "—" else SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))

    fun fileStamp(ms: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(ms))

    /** 83000 -> "1:23", 3723000 -> "1:02:03" */
    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun minutes(ms: Long): String = String.format(Locale.getDefault(), "%.1f min", ms / 60000.0)

    fun bytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

object Dates {
    fun startOfWeek(now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun endOfDay(ms: Long): Long = startOfDay(ms) + 24 * 60 * 60 * 1000L - 1

    fun daysAgo(days: Int): Long = startOfDay(System.currentTimeMillis() - days * 24L * 60 * 60 * 1000)
}
