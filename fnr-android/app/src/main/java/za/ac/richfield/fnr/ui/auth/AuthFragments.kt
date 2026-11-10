package za.ac.richfield.fnr.ui.auth

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModel
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.ac.richfield.fnr.R
import za.ac.richfield.fnr.data.db.Role
import za.ac.richfield.fnr.data.repository.LoginResult
import za.ac.richfield.fnr.data.repository.RegisterResult
import za.ac.richfield.fnr.data.repository.UserRepository
import za.ac.richfield.fnr.databinding.FragmentLoginBinding
import za.ac.richfield.fnr.databinding.FragmentPurposeBinding
import za.ac.richfield.fnr.databinding.FragmentRegisterBinding
import za.ac.richfield.fnr.databinding.FragmentSplashBinding
import za.ac.richfield.fnr.security.Validators
import za.ac.richfield.fnr.ui.BaseFragment
import za.ac.richfield.fnr.ui.appViewModels

class AuthViewModel(private val users: UserRepository) : ViewModel() {
    suspend fun login(email: String, password: String) = users.login(email, password)
    suspend fun register(first: String, last: String, email: String, password: String, confirm: String) =
        users.register(first, last, email, password, confirm)
    suspend fun setPurpose(purpose: String) = users.setPurpose(purpose)
}

/** Decides where to start: login, first-use purpose screen, student or admin dashboard. */
class SplashFragment : BaseFragment<FragmentSplashBinding>(R.layout.fragment_splash, FragmentSplashBinding::bind) {
    override fun setup(savedInstanceState: Bundle?) {
        launchSafely({ navigateClearing(R.id.loginFragment) }) {
            val session = container.session
            val user = withContext(Dispatchers.IO) {
                container.notes.purgeOldDeleted()
                if (session.isLoggedIn && !session.isExpired) container.users.current() else null
            }
            when {
                user == null || !user.isActive -> { session.clear(); navigateClearing(R.id.loginFragment) }
                user.role == Role.ADMIN -> navigateClearing(R.id.adminDashboardFragment)
                user.purpose == null -> navigateClearing(R.id.purposeFragment)
                else -> navigateClearing(R.id.dashboardFragment)
            }
        }
    }
}

class LoginFragment : BaseFragment<FragmentLoginBinding>(R.layout.fragment_login, FragmentLoginBinding::bind) {
    private val vm: AuthViewModel by appViewModels { AuthViewModel(it.users) }

    override fun setup(savedInstanceState: Bundle?) {
        arguments?.getString(ARG_MESSAGE)?.let { b.message.text = it; b.message.show(true) }
        b.emailInput.doAfterTextChanged { showError(b.emailLayout, null) }
        b.passwordInput.doAfterTextChanged { showError(b.passwordLayout, null) }
        b.registerLink.setOnClickListener { findNavController().navigate(R.id.registerFragment) }
        b.loginButton.setOnClickListener { login() }
        b.forgotLink.setOnClickListener { forgotPassword() }
    }

    /** FNR keeps accounts only on this device and has no email server, so the administrator resets passwords. */
    private fun forgotPassword() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Forgot your password?")
            .setMessage(
                "Your account is stored only on this device, so FNR cannot email you a reset link.\n\n" +
                    "Ask your administrator to reset your password. They will give you a temporary password: " +
                    "log in with it, then choose a new password under Profile, Change password."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun login() {
        val email = b.emailInput.text.toString()
        val password = b.passwordInput.text.toString()
        showError(b.emailLayout, Validators.required(email, "email"))
        showError(b.passwordLayout, Validators.required(password, "password"))
        if (email.isBlank() || password.isBlank()) return
        b.loginButton.isEnabled = false
        b.progress.show(true)
        b.error.show(false)
        launchSafely({ showFailure(it) }) {
            when (val result = vm.login(email, password)) {
                is LoginResult.Failure -> showFailure(result.message)
                is LoginResult.Success -> when {
                    result.user.role == Role.ADMIN -> navigateClearing(R.id.adminDashboardFragment)
                    result.user.purpose == null -> navigateClearing(R.id.purposeFragment)
                    else -> navigateClearing(R.id.dashboardFragment)
                }
            }
        }
    }

    private fun showFailure(message: String) {
        if (view == null) return
        b.loginButton.isEnabled = true
        b.progress.show(false)
        b.error.text = message
        b.error.show(true)
        b.passwordInput.text = null
    }

    companion object {
        const val ARG_MESSAGE = "message"
    }
}

/** Registration with live, field-by-field validation (FR-USR-01, FR-USR-02). */
class RegisterFragment : BaseFragment<FragmentRegisterBinding>(R.layout.fragment_register, FragmentRegisterBinding::bind) {
    private val vm: AuthViewModel by appViewModels { AuthViewModel(it.users) }

    override fun setup(savedInstanceState: Bundle?) {
        b.firstInput.doAfterTextChanged { showError(b.firstLayout, Validators.name(it.toString())); refresh() }
        b.lastInput.doAfterTextChanged { showError(b.lastLayout, Validators.name(it.toString())); refresh() }
        b.emailInput.doAfterTextChanged { showError(b.emailLayout, Validators.email(it.toString())); refresh() }
        b.passwordInput.doAfterTextChanged {
            showError(b.passwordLayout, Validators.password(it.toString()))
            if (b.confirmInput.text?.isNotEmpty() == true) {
                showError(b.confirmLayout, Validators.confirm(it.toString(), b.confirmInput.text.toString()))
            }
            refresh()
        }
        b.confirmInput.doAfterTextChanged {
            showError(b.confirmLayout, Validators.confirm(b.passwordInput.text.toString(), it.toString())); refresh()
        }
        b.registerButton.isEnabled = false
        b.registerButton.setOnClickListener { register() }
        b.loginLink.setOnClickListener { findNavController().navigateUp() }
    }

    /** The Register button stays disabled until every field is valid. */
    private fun refresh() {
        b.registerButton.isEnabled = Validators.name(b.firstInput.text.toString()) == null &&
            Validators.name(b.lastInput.text.toString()) == null &&
            Validators.email(b.emailInput.text.toString()) == null &&
            Validators.password(b.passwordInput.text.toString()) == null &&
            Validators.confirm(b.passwordInput.text.toString(), b.confirmInput.text.toString()) == null
    }

    private fun register() {
        b.registerButton.isEnabled = false
        b.progress.show(true)
        b.emailLayout.helperText = "Checking your email address…"
        launchSafely({ b.progress.show(false); b.emailLayout.helperText = null; refresh(); toast(it) }) {
            val result = vm.register(
                b.firstInput.text.toString(), b.lastInput.text.toString(), b.emailInput.text.toString(),
                b.passwordInput.text.toString(), b.confirmInput.text.toString(),
            )
            b.progress.show(false)
            b.emailLayout.helperText = null
            when (result) {
                is RegisterResult.Invalid -> {
                    showError(b.firstLayout, result.errors[UserRepository.FIELD_FIRST])
                    showError(b.lastLayout, result.errors[UserRepository.FIELD_LAST])
                    showError(b.emailLayout, result.errors[UserRepository.FIELD_EMAIL])
                    showError(b.passwordLayout, result.errors[UserRepository.FIELD_PASSWORD])
                    showError(b.confirmLayout, result.errors[UserRepository.FIELD_CONFIRM])
                    refresh()
                }
                is RegisterResult.Success -> {
                    var message = if (result.isAdmin)
                        "Account created. As the first account on this device, you are the administrator. Log in to continue."
                    else "Account created. Log in to continue."
                    if (!result.emailChecked) message += " (You were offline, so your email domain could not be checked.)"
                    navigateClearing(R.id.loginFragment, bundleOf(LoginFragment.ARG_MESSAGE to message))
                }
            }
        }
    }
}

/** First-use personalisation (FR-USR-05). */
class PurposeFragment : BaseFragment<FragmentPurposeBinding>(R.layout.fragment_purpose, FragmentPurposeBinding::bind) {
    private val vm: AuthViewModel by appViewModels { AuthViewModel(it.users) }

    override fun setup(savedInstanceState: Bundle?) {
        b.studying.setOnClickListener { choose(UserRepository.PURPOSE_STUDYING) }
        b.research.setOnClickListener { choose(UserRepository.PURPOSE_RESEARCH) }
        b.revision.setOnClickListener { choose(UserRepository.PURPOSE_REVISION) }
    }

    private fun choose(purpose: String) {
        launchSafely {
            vm.setPurpose(purpose)
            navigateClearing(R.id.dashboardFragment)
        }
    }
}
