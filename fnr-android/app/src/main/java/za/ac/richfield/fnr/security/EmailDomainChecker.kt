package za.ac.richfield.fnr.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Checks online that an email address's domain really exists and accepts email (FR-USR-02).
 *
 * It looks up the domain's mail servers (its MX records) with DNS over HTTPS. Only the domain
 * (the part after the @) is sent, never the full address, so no personal data leaves the phone.
 * A domain with no MX record can still take email at its plain address (RFC 5321, section 5.1),
 * so that is checked next; a "null MX" (RFC 7505) means the domain refuses all email.
 *
 * Offline, or if every resolver fails, the result is [Result.UNKNOWN] and registration is allowed,
 * because FNR must keep working without internet.
 */
open class EmailDomainChecker(
    private val resolvers: List<String> = listOf(
        "https://dns.google/resolve",
        "https://cloudflare-dns.com/dns-query",
    ),
    private val timeoutMs: Int = 4_000,
) {
    enum class Result { VALID, NO_SUCH_DOMAIN, NO_MAIL_SERVER, UNKNOWN }

    open suspend fun check(email: String): Result = withContext(Dispatchers.IO) {
        val domain = email.trim().substringAfterLast('@').lowercase()
        if (domain.isEmpty()) return@withContext Result.NO_SUCH_DOMAIN
        val mx = query(domain, TYPE_MX) ?: return@withContext Result.UNKNOWN
        when {
            mx.status == NXDOMAIN -> Result.NO_SUCH_DOMAIN
            mx.status != NOERROR -> Result.UNKNOWN
            mx.records.isNotEmpty() ->
                if (mx.records.all { isNullMx(it) }) Result.NO_MAIL_SERVER else Result.VALID
            else -> {
                // No MX: email goes to the domain's own address, if it has one.
                val a = query(domain, TYPE_A) ?: return@withContext Result.UNKNOWN
                val aaaa = if (a.records.isEmpty()) query(domain, TYPE_AAAA) else null
                if (a.records.isNotEmpty() || aaaa?.records?.isNotEmpty() == true) Result.VALID
                else Result.NO_MAIL_SERVER
            }
        }
    }

    /** One DNS answer: the status code and the data of every record of the type asked for. */
    data class Answer(val status: Int, val records: List<String>)

    /** Tries each resolver in turn; null when none could be reached. */
    private fun query(domain: String, type: Int): Answer? {
        for (base in resolvers) {
            try {
                val url = URL("$base?name=${URLEncoder.encode(domain, "UTF-8")}&type=$type")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.setRequestProperty("Accept", "application/dns-json")
                try {
                    if (conn.responseCode != 200) continue
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    return parse(body, type) ?: continue
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                // try the next resolver
            }
        }
        return null
    }

    companion object {
        const val TYPE_A = 1
        const val TYPE_MX = 15
        const val TYPE_AAAA = 28
        const val NOERROR = 0
        const val NXDOMAIN = 3

        private val STATUS = Regex("\"Status\"\\s*:\\s*(\\d+)")
        private val RECORD = Regex("\\{[^{}]*\"type\"\\s*:\\s*(\\d+)[^{}]*\"data\"\\s*:\\s*\"([^\"]*)\"[^{}]*\\}")

        /** Reads a DNS-over-HTTPS JSON reply; null if it isn't one. Kept pure so it can be unit tested. */
        fun parse(json: String, type: Int): Answer? {
            val status = STATUS.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val answerPart = json.substringAfter("\"Answer\"", "")
            val records = RECORD.findAll(answerPart)
                .filter { it.groupValues[1].toInt() == type }
                .map { it.groupValues[2] }
                .toList()
            return Answer(status, records)
        }

        /** "0 ." is a null MX: the domain publishes that it never accepts email. */
        fun isNullMx(record: String): Boolean = record.trim().split(Regex("\\s+")).getOrNull(1) == "."
    }
}
