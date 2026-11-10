package za.ac.richfield.fnr.ui.profile

import android.os.Bundle
import androidx.core.widget.doAfterTextChanged
import za.ac.richfield.fnr.R
import za.ac.richfield.fnr.data.remote.GatewayClient
import za.ac.richfield.fnr.databinding.FragmentProfileBinding
import za.ac.richfield.fnr.security.Validators
import za.ac.richfield.fnr.ui.BaseFragment
import za.ac.richfield.fnr.ui.MainActivity
import za.ac.richfield.fnr.util.Format

/** Profile, password change, storage used, AI Gateway address, log out (FR-USR-06). */
class ProfileFragment : BaseFragment<FragmentProfileBinding>(R.layout.fragment_profile, FragmentProfileBinding::bind) {

    override fun setup(savedInstanceState: Bundle?) {
        var filled = false
        observe(container.users.observeCurrent()) { user ->
            if (user == null) return@observe
            b.name.text = user.fullName
            b.email.text = user.email
            b.role.text = "${user.role.name.lowercase().replaceFirstChar { it.uppercase() }} · member since ${Format.date(user.createdAt)}" +
                (user.purpose?.let { " · ${it.lowercase()}" } ?: "")
            if (!filled) {
                filled = true
                b.firstInput.setText(user.firstName)
                b.lastInput.setText(user.lastName)
            }
        }
        observe(container.recordings.observeStorage()) { b.storage.text = "Recordings use ${Format.bytes(it)} on this phone" }

        b.firstInput.doAfterTextChanged { showError(b.firstLayout, Validators.name(it.toString())) }
        b.lastInput.doAfterTextChanged { showError(b.lastLayout, Validators.name(it.toString())) }
        b.saveNames.setOnClickListener {
            launchSafely {
                val error = container.users.updateNames(b.firstInput.text.toString(), b.lastInput.text.toString())
                toast(error ?: "Name updated")
            }
        }

        b.newInput.doAfterTextChanged { showError(b.newLayout, Validators.password(it.toString())) }
        b.changePassword.setOnClickListener {
            launchSafely {
                val error = container.users.changePassword(
                    b.oldInput.text.toString(), b.newInput.text.toString(), b.confirmInput.text.toString(),
                )
                if (error == null) {
                    b.oldInput.text = null; b.newInput.text = null; b.confirmInput.text = null
                    showError(b.newLayout, null)
                    toast("Password changed. If automatic backup was on, switch it on again in Backup.")
                } else toast(error)
            }
        }

        b.gatewayInput.setText(container.settings.gatewayUrl)
        b.gatewayInput.doAfterTextChanged { showError(b.gatewayLayout, Validators.gatewayUrl(it.toString())) }
        b.saveGateway.setOnClickListener {
            val url = b.gatewayInput.text.toString()
            Validators.gatewayUrl(url)?.let { showError(b.gatewayLayout, it); return@setOnClickListener }
            container.settings.gatewayUrl = url
            toast("Gateway address saved")
        }
        b.testGateway.setOnClickListener {
            val url = b.gatewayInput.text.toString()
            Validators.gatewayUrl(url)?.let { showError(b.gatewayLayout, it); return@setOnClickListener }
            b.gatewayStatus.text = "Testing…"
            launchSafely({ b.gatewayStatus.text = it }) {
                try {
                    val health = GatewayClient.create(url, za.ac.richfield.fnr.BuildConfig.APP_TOKEN, container.settings.deviceId).health()
                    b.gatewayStatus.text = "Connected. Speech-to-text: ${health.stt}, key points: ${health.segmenter}."
                } catch (e: Exception) {
                    b.gatewayStatus.text = GatewayClient.friendlyError(e)
                }
            }
        }

        b.logout.setOnClickListener { (requireActivity() as MainActivity).logout(null) }
    }
}
