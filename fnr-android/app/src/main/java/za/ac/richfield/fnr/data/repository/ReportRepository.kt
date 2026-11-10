package za.ac.richfield.fnr.data.repository

import za.ac.richfield.fnr.data.db.FnrDatabase
import za.ac.richfield.fnr.data.db.JobStatus
import za.ac.richfield.fnr.security.SessionManager
import za.ac.richfield.fnr.util.Format

/** The five reports from Phase 3, section 3.6.3. */
enum class ReportType(
    val title: String,
    val description: String,
    val adminOnly: Boolean,
    val usesDates: Boolean,
    val usesSubject: Boolean,
) {
    R1("Study Activity Report", "Recording sessions and minutes per week and subject", false, true, true),
    R2("Flash Note Summary by Subject", "Notes, listening time and last revision per subject", false, false, true),
    R3("Revision Frequency Report", "Most and least replayed flash notes", false, true, true),
    R4("AI Processing Report", "Processing jobs, success rate and time taken", true, true, false),
    R5("User Accounts and Usage Report", "Users, roles, status, last login and storage", true, false, false),
}

data class ReportData(
    val type: ReportType,
    val periodLabel: String,
    val summary: List<Pair<String, String>>,
    val columns: List<String>,
    val rows: List<List<String>>,
    val chartTitle: String = "",
    val chart: List<Pair<String, Float>> = emptyList(),
) {
    val isEmpty: Boolean get() = rows.isEmpty()
}

/** Module M5 Reports. Students only ever see their own data; R4 and R5 need the ADMIN role. */
class ReportRepository(private val db: FnrDatabase, private val session: SessionManager) {
    private val dao = db.reportDao()

    suspend fun generate(type: ReportType, from: Long, to: Long, subjectId: Long): ReportData {
        if (type.adminOnly) session.requireAdmin()
        val userId = session.requireUserId()
        val period = if (type.usesDates) "${Format.date(from)} to ${Format.date(to)}" else "All time"
        return when (type) {
            ReportType.R1 -> {
                val rows = dao.studyActivity(userId, from, to, subjectId)
                val totalMs = rows.sumOf { it.totalMs }
                val top = rows.groupBy { it.subjectName }.maxByOrNull { (_, r) -> r.sumOf { it.totalMs } }?.key ?: "—"
                val perWeek = rows.groupBy { it.week }.toSortedMap()
                ReportData(
                    type, period,
                    summary = listOf(
                        "Sessions" to rows.sumOf { it.sessions }.toString(),
                        "Time recorded" to Format.minutes(totalMs),
                        "Most studied subject" to top,
                    ),
                    columns = listOf("Week", "Subject", "Sessions", "Minutes"),
                    rows = rows.map { listOf(it.week, it.subjectName, it.sessions.toString(), Format.minutes(it.totalMs)) },
                    chartTitle = "Minutes recorded per week",
                    chart = perWeek.map { (week, r) -> week.substringAfter("-") to r.sumOf { it.totalMs } / 60000f },
                )
            }
            ReportType.R2 -> {
                val rows = dao.notesBySubject(userId, subjectId)
                ReportData(
                    type, period,
                    summary = listOf(
                        "Flash notes" to rows.sumOf { it.notes }.toString(),
                        "Total listening time" to Format.duration(rows.sumOf { it.clipMs }),
                        "Subjects" to rows.size.toString(),
                    ),
                    columns = listOf("Subject", "Notes", "Clip time", "Last revised"),
                    rows = rows.map { listOf(it.subjectName, it.notes.toString(), Format.duration(it.clipMs), Format.date(it.lastRevised)) },
                    chartTitle = "Flash notes per subject",
                    chart = rows.take(8).map { it.subjectName.take(10) to it.notes.toFloat() },
                )
            }
            ReportType.R3 -> {
                val rows = dao.revisionFrequency(userId, from, to, subjectId)
                ReportData(
                    type, period,
                    summary = listOf(
                        "Plays in period" to rows.sumOf { it.timesPlayed }.toString(),
                        "Notes never played" to rows.count { it.timesPlayed == 0 }.toString(),
                        "Most replayed" to (rows.firstOrNull { it.timesPlayed > 0 }?.title ?: "—"),
                    ),
                    columns = listOf("Flash note", "Subject", "Times played", "Last played"),
                    rows = rows.map { listOf(it.title, it.subjectName, it.timesPlayed.toString(), Format.date(it.lastPlayed)) },
                    chartTitle = "Top replayed notes",
                    chart = rows.filter { it.timesPlayed > 0 }.take(8).map { it.title.take(10) to it.timesPlayed.toFloat() },
                )
            }
            ReportType.R4 -> {
                val rows = dao.aiProcessing(from, to)
                val done = rows.filter { it.status == JobStatus.SUCCESS && it.processedAt != null }
                val rate = if (rows.isEmpty()) 0 else done.size * 100 / rows.size
                val avg = if (done.isEmpty()) 0L else done.sumOf { it.processedAt!! - it.startedAt } / done.size
                ReportData(
                    type, period,
                    summary = listOf(
                        "Jobs" to rows.size.toString(),
                        "Success rate" to "$rate%",
                        "Average time" to Format.duration(avg),
                    ),
                    columns = listOf("Started", "Recording", "User", "Model", "Status", "Time", "Error"),
                    rows = rows.map {
                        listOf(
                            Format.dateTime(it.startedAt), it.recordingTitle, it.userEmail, it.modelUsed, it.status.name,
                            it.processedAt?.let { p -> Format.duration(p - it.startedAt) } ?: "—", it.errorMessage ?: "",
                        )
                    },
                )
            }
            ReportType.R5 -> {
                val rows = dao.userUsage()
                val now = System.currentTimeMillis()
                ReportData(
                    type, period,
                    summary = listOf(
                        "Users" to rows.size.toString(),
                        "Active" to rows.count { it.isActive }.toString(),
                        "Locked now" to rows.count { (it.lockedUntil ?: 0) > now }.toString(),
                        "Storage used" to Format.bytes(rows.sumOf { it.storageBytes }),
                    ),
                    columns = listOf("Name", "Email", "Role", "Status", "Last login", "Recordings", "Storage"),
                    rows = rows.map {
                        val status = when {
                            !it.isActive -> "Deactivated"
                            (it.lockedUntil ?: 0) > now -> "Locked"
                            else -> "Active"
                        }
                        listOf(
                            "${it.firstName} ${it.lastName}", it.email, it.role.name, status,
                            Format.dateTime(it.lastLoginAt), it.recordings.toString(), Format.bytes(it.storageBytes),
                        )
                    },
                )
            }
        }
    }
}
