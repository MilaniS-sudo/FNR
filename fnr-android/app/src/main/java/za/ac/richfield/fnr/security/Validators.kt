package za.ac.richfield.fnr.security

/**
 * Every input rule from Phase 4, section 4.7.3, in one place.
 * Each function returns an error message, or null when the value is valid.
 */
object Validators {
    private val NAME = Regex("^[\\p{L}' \\-]{2,50}$")

    fun name(value: String): String? = when {
        value.isBlank() -> "Required"
        !NAME.matches(value.trim()) -> "Enter 2 to 50 letters"
        else -> null
    }

    // ---- Email (FR-USR-02): the full shape of a real address, not just "has an @ and a dot" ----
    // Before the @ (RFC 5322 "dot-atom"): letters, digits and ! # $ % & ' * + / = ? ^ _ ` { | } ~ -
    // in runs separated by single dots; no dot at the start or end, never two dots in a row.
    private val LOCAL = Regex("^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*$")
    // Each part of the domain: 1 to 63 letters, digits or hyphens, not starting or ending with a hyphen.
    private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    // The last part (top-level domain) is letters only, 2 to 24 long: .com, .za, .africa
    private val TLD = Regex("^[a-z]{2,24}$")

    /** Domains reserved for examples and testing (RFC 2606): they can never receive email. */
    private val RESERVED = setOf("example.com", "example.org", "example.net")
    private val RESERVED_TLDS = setOf("test", "example", "invalid", "localhost", "local")

    /** Throw-away inbox services: an account must belong to a real, lasting address. */
    private val DISPOSABLE = setOf(
        "mailinator.com", "10minutemail.com", "guerrillamail.com", "guerrillamail.net", "sharklasers.com",
        "tempmail.com", "temp-mail.org", "yopmail.com", "trashmail.com", "getnada.com", "dispostable.com",
        "maildrop.cc", "throwawaymail.com", "fakeinbox.com", "mintemail.com", "emailondeck.com",
    )

    /** Common misspellings of popular domains, so the user can fix them before registering. */
    private val TYPOS = mapOf(
        "gmial.com" to "gmail.com", "gmai.com" to "gmail.com", "gmal.com" to "gmail.com", "gamil.com" to "gmail.com",
        "gnail.com" to "gmail.com", "gmaill.com" to "gmail.com", "gmail.co" to "gmail.com", "gmail.cm" to "gmail.com",
        "gmail.con" to "gmail.com", "gmail.om" to "gmail.com", "gmail.cmo" to "gmail.com", "gmali.com" to "gmail.com",
        "yaho.com" to "yahoo.com", "yahooo.com" to "yahoo.com", "yahoo.co" to "yahoo.com", "yahoo.con" to "yahoo.com",
        "hotmial.com" to "hotmail.com", "hotmai.com" to "hotmail.com", "hotmal.com" to "hotmail.com",
        "hotmail.co" to "hotmail.com", "hotmail.con" to "hotmail.com", "outlok.com" to "outlook.com",
        "outllok.com" to "outlook.com", "outlook.co" to "outlook.com", "outlook.con" to "outlook.com",
        "iclod.com" to "icloud.com", "icoud.com" to "icloud.com", "icloud.co" to "icloud.com", "icloud.con" to "icloud.com",
    )

    /**
     * Checks that [value] is shaped like a real email address and returns the reason when it is not.
     * Whether the domain can actually receive email is checked online by [EmailDomainChecker].
     */
    fun email(value: String): String? {
        val v = value.trim()
        if (v.isEmpty()) return "Enter your email"
        if (v.any { it.isWhitespace() }) return "Email can't contain spaces"
        if (v.length > 254) return "Email is too long"
        if (v.count { it == '@' } != 1) return "Email needs exactly one @"
        val local = v.substringBefore('@')
        val domain = v.substringAfter('@').lowercase()
        if (local.isEmpty()) return "Enter the part before the @"
        if (local.length > 64) return "The part before the @ is too long"
        if (!LOCAL.matches(local)) return "The part before the @ has a character or dot that isn't allowed"
        if (domain.isEmpty()) return "Enter the part after the @, e.g. gmail.com"
        val labels = domain.split('.')
        if (labels.size < 2) return "The domain needs an ending, e.g. gmail.com"
        if (labels.any { it.isEmpty() }) return "The domain has an extra or missing dot"
        if (labels.any { !LABEL.matches(it) }) return "The domain has a character that isn't allowed"
        if (!TLD.matches(labels.last())) return "The domain ending isn't valid, e.g. .com or .co.za"
        TYPOS[domain]?.let { return "Did you mean ${local}@$it?" }
        if (domain in RESERVED || labels.last() in RESERVED_TLDS) return "Use a real email address"
        if (domain in DISPOSABLE) return "Temporary email addresses aren't allowed"
        return null
    }

    fun password(value: String): String? {
        val missing = buildList {
            if (value.length < 8) add("8+ characters")
            if (value.none { it.isUpperCase() }) add("an upper case letter")
            if (value.none { it.isDigit() }) add("a number")
            if (value.all { it.isLetterOrDigit() }) add("a symbol")
        }
        return if (missing.isEmpty()) null else "Password needs " + missing.joinToString(", ")
    }

    fun confirm(password: String, confirm: String): String? =
        if (confirm.isEmpty()) "Confirm your password" else if (password != confirm) "Passwords do not match" else null

    fun required(value: String, label: String): String? = if (value.isBlank()) "Enter your $label" else null

    fun subjectName(value: String): String? = when {
        value.trim().length !in 2..60 -> "Subject name must be 2 to 60 characters"
        else -> null
    }

    fun description(value: String): String? = if (value.length > 250) "At most 250 characters" else null

    fun noteTitle(value: String): String? =
        if (value.trim().length !in 3..100) "Title must be 3 to 100 characters" else null

    fun noteSummary(value: String): String? = if (value.length > 500) "At most 500 characters" else null

    fun recordingTitle(value: String): String? =
        if (value.isNotBlank() && value.trim().length > 80) "At most 80 characters" else null

    /** Clip rules: inside the recording, start before end, 5 to 120 seconds. */
    fun clip(startMs: Long, endMs: Long, recordingMs: Long): String? = when {
        startMs < 0 || endMs > recordingMs -> "Clip must be inside the recording"
        endMs <= startMs -> "End must be after start"
        endMs - startMs < 5_000 || endMs - startMs > 120_000 -> "Clip must be 5 to 120 seconds"
        else -> null
    }

    fun dateRange(from: Long, to: Long): String? = when {
        from > to -> "Start date must be before end date"
        from > System.currentTimeMillis() -> "Start date cannot be in the future"
        else -> null
    }

    fun gatewayUrl(value: String): String? {
        val v = value.trim()
        val host = runCatching { java.net.URL(v).host }.getOrNull()
        return if (!(v.startsWith("http://") || v.startsWith("https://")) || host.isNullOrBlank())
            "Enter a URL such as http://192.168.1.50:8000/" else null
    }
}
