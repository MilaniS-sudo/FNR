package za.ac.richfield.fnr.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import za.ac.richfield.fnr.data.db.FnrDatabase
import za.ac.richfield.fnr.data.db.Role
import za.ac.richfield.fnr.data.db.SubjectEntity
import za.ac.richfield.fnr.data.db.UserEntity
import za.ac.richfield.fnr.security.AppSettings
import za.ac.richfield.fnr.security.EmailDomainChecker
import za.ac.richfield.fnr.security.PasswordHasher
import za.ac.richfield.fnr.security.SessionManager
import za.ac.richfield.fnr.security.Validators
import za.ac.richfield.fnr.util.UserFacingException
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date

sealed interface LoginResult {
    data class Success(val user: UserEntity) : LoginResult
    data class Failure(val message: String) : LoginResult
}

sealed interface RegisterResult {
    /** [emailChecked] is false when the phone was offline and the email domain could not be checked. */
    data class Success(val isAdmin: Boolean, val emailChecked: Boolean = true) : RegisterResult
    data class Invalid(val errors: Map<String, String>) : RegisterResult
}

/** Module M1 User and Security. */
class UserRepository(
    private val db: FnrDatabase,
    private val session: SessionManager,
    private val settings: AppSettings,
    private val emailChecker: EmailDomainChecker = EmailDomainChecker(),
) {
    private val dao = db.userDao()

    suspend fun register(
        firstName: String, lastName: String, email: String, password: String, confirm: String,
    ): RegisterResult {
        val errors = mutableMapOf<String, String>()
        Validators.name(firstName)?.let { errors[FIELD_FIRST] = it }
        Validators.name(lastName)?.let { errors[FIELD_LAST] = it }
        val cleanEmail = email.trim().lowercase()
        val emailError = Validators.email(cleanEmail)
        if (emailError != null) errors[FIELD_EMAIL] = emailError
        else if (dao.findByEmail(cleanEmail) != null) errors[FIELD_EMAIL] = "Email already registered"
        Validators.password(password)?.let { errors[FIELD_PASSWORD] = it }
        Validators.confirm(password, confirm)?.let { errors[FIELD_CONFIRM] = it }
        if (errors.isNotEmpty()) return RegisterResult.Invalid(errors)

        // The address is well formed; now check online that its domain really receives email.
        val domainCheck = emailChecker.check(cleanEmail)
        when (domainCheck) {
            EmailDomainChecker.Result.NO_SUCH_DOMAIN ->
                return RegisterResult.Invalid(mapOf(FIELD_EMAIL to "That email domain doesn't exist. Check the part after the @"))
            EmailDomainChecker.Result.NO_MAIL_SERVER ->
                return RegisterResult.Invalid(mapOf(FIELD_EMAIL to "That domain can't receive email. Use your real address"))
            else -> Unit
        }

        val salt = PasswordHasher.newSalt()
        val hash = withContext(Dispatchers.Default) { PasswordHasher.hash(password, salt) }
        // The first account on a device becomes the administrator (Phase 4, 4.8.2).
        val role = if (dao.count() == 0) Role.ADMIN else Role.STUDENT
        dao.insert(
            UserEntity(
                firstName = firstName.trim(),
                lastName = lastName.trim(),
                email = cleanEmail,
                passwordHash = hash,
                passwordSalt = PasswordHasher.b64(salt),
                role = role,
            )
        )
        return RegisterResult.Success(
            isAdmin = role == Role.ADMIN,
            emailChecked = domainCheck != EmailDomainChecker.Result.UNKNOWN,
        )
    }

    suspend fun login(email: String, password: String): LoginResult {
        Validators.required(email, "email")?.let { return LoginResult.Failure(it) }
        Validators.required(password, "password")?.let { return LoginResult.Failure(it) }
        val user = dao.findByEmail(email.trim().lowercase())
            ?: return LoginResult.Failure(WRONG) // same message: never reveal which emails exist
        if (!user.isActive) return LoginResult.Failure("Account deactivated. Contact the administrator.")
        val now = System.currentTimeMillis()
        if (user.isLocked(now)) {
            val at = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(user.lockedUntil!!))
            return LoginResult.Failure("Account locked after 5 failed attempts. Try again at $at.")
        }
        val ok = withContext(Dispatchers.Default) {
            PasswordHasher.verify(password, user.passwordSalt, user.passwordHash)
        }
        if (!ok) {
            val failures = user.failedLogins + 1
            val locked = if (failures >= MAX_FAILURES) now + LOCK_MS else null
            dao.update(user.copy(failedLogins = if (locked != null) 0 else failures, lockedUntil = locked))
            return LoginResult.Failure(
                if (locked != null) "Account locked for 15 minutes after 5 failed attempts."
                else "$WRONG (${MAX_FAILURES - failures} attempts left)"
            )
        }
        val updated = user.copy(failedLogins = 0, lockedUntil = null, lastLoginAt = now)
        dao.update(updated)
        session.start(updated.userId, updated.role)
        return LoginResult.Success(updated)
    }

    fun logout() = session.clear()

    suspend fun current(): UserEntity? = if (session.isLoggedIn) dao.findById(session.userId) else null

    fun observeCurrent(): Flow<UserEntity?> = dao.observe(session.userId)

    /** First-login personalisation (FR-USR-05): saves the purpose and adds a starter subject. */
    suspend fun setPurpose(purpose: String) {
        val user = current() ?: return
        dao.update(user.copy(purpose = purpose))
        val subjects = db.subjectDao()
        if (subjects.listForUser(user.userId).isEmpty()) {
            val starter = when (purpose) {
                PURPOSE_RESEARCH -> "Research notes"
                PURPOSE_REVISION -> "Exam revision"
                else -> "General studies"
            }
            subjects.insert(SubjectEntity(userId = user.userId, subjectName = starter))
        }
    }

    suspend fun updateNames(firstName: String, lastName: String): String? {
        Validators.name(firstName)?.let { return "First name: $it" }
        Validators.name(lastName)?.let { return "Last name: $it" }
        val user = current() ?: return "Not logged in"
        dao.update(user.copy(firstName = firstName.trim(), lastName = lastName.trim()))
        return null
    }

    suspend fun verifyPassword(password: String): Boolean {
        val user = current() ?: return false
        return withContext(Dispatchers.Default) { PasswordHasher.verify(password, user.passwordSalt, user.passwordHash) }
    }

    /** Returns an error message, or null on success. */
    suspend fun changePassword(old: String, new: String, confirm: String): String? {
        val user = current() ?: return "Not logged in"
        if (!verifyPassword(old)) return "Current password is incorrect"
        Validators.password(new)?.let { return it }
        Validators.confirm(new, confirm)?.let { return it }
        if (old == new) return "New password must be different from the current one"
        val salt = PasswordHasher.newSalt()
        val hash = withContext(Dispatchers.Default) { PasswordHasher.hash(new, salt) }
        dao.update(user.copy(passwordHash = hash, passwordSalt = PasswordHasher.b64(salt)))
        // Automatic backups were keyed to the old password; ask the user to switch them on again.
        settings.clearAutoBackup(user.userId)
        return null
    }

    // ---- Administrator functions (FR-ADM-01). Each one checks the role itself. ----

    fun observeAllUsers(): Flow<List<UserEntity>> {
        session.requireAdmin()
        return dao.observeAll()
    }

    suspend fun setActive(targetId: Long, active: Boolean): String? {
        session.requireAdmin()
        if (targetId == session.userId) return "You cannot deactivate your own account"
        val target = dao.findById(targetId) ?: return "User not found"
        dao.update(target.copy(isActive = active))
        return null
    }

    suspend fun unlock(targetId: Long): String? {
        session.requireAdmin()
        val target = dao.findById(targetId) ?: return "User not found"
        dao.update(target.copy(failedLogins = 0, lockedUntil = null))
        return null
    }

    /**
     * Forgot password (FR-USR-09): FNR is offline with no email server, so the administrator
     * resets the password. Returns a one-time temporary password that meets the password policy;
     * the student logs in with it and changes it in Profile. Clears any lockout.
     */
    suspend fun resetPassword(targetId: Long): Result<String> {
        session.requireAdmin()
        if (targetId == session.userId) return Result.failure(UserFacingException("Change your own password in Profile"))
        val target = dao.findById(targetId) ?: return Result.failure(UserFacingException("User not found"))
        val temp = temporaryPassword()
        val salt = PasswordHasher.newSalt()
        val hash = withContext(Dispatchers.Default) { PasswordHasher.hash(temp, salt) }
        dao.update(target.copy(passwordHash = hash, passwordSalt = PasswordHasher.b64(salt), failedLogins = 0, lockedUntil = null))
        // Automatic backups were keyed to the old password; the student switches them on again.
        settings.clearAutoBackup(target.userId)
        return Result.success(temp)
    }

    /** e.g. "Kmt-4827-Qa!" : upper, lower, digits and a symbol, without look-alike characters. */
    private fun temporaryPassword(): String {
        val r = SecureRandom()
        fun pick(chars: String, n: Int) = (1..n).map { chars[r.nextInt(chars.length)] }.joinToString("")
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"; val lower = "abcdefghijkmnpqrstuvwxyz"; val digits = "23456789"
        return pick(upper, 1) + pick(lower, 2) + "-" + pick(digits, 4) + "-" + pick(upper, 1) + pick(lower, 1) + "!"
    }

    companion object {
        const val FIELD_FIRST = "first"
        const val FIELD_LAST = "last"
        const val FIELD_EMAIL = "email"
        const val FIELD_PASSWORD = "password"
        const val FIELD_CONFIRM = "confirm"
        const val PURPOSE_STUDYING = "STUDYING"
        const val PURPOSE_RESEARCH = "RESEARCH"
        const val PURPOSE_REVISION = "REVISION"
        private const val WRONG = "Incorrect email or password"
        private const val MAX_FAILURES = 5
        private const val LOCK_MS = 15 * 60 * 1000L
    }
}
