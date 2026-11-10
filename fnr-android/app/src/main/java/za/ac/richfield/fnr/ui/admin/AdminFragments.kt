package za.ac.richfield.fnr.ui.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.ac.richfield.fnr.R
import za.ac.richfield.fnr.data.db.UserEntity
import za.ac.richfield.fnr.databinding.FragmentAdminDashboardBinding
import za.ac.richfield.fnr.databinding.FragmentUsersBinding
import za.ac.richfield.fnr.databinding.ItemUserBinding
import za.ac.richfield.fnr.ui.BaseFragment
import za.ac.richfield.fnr.util.Format

/** Administrator home (Phase 4, 4.8.2). Students are sent back if they ever reach it. */
class AdminDashboardFragment : BaseFragment<FragmentAdminDashboardBinding>(R.layout.fragment_admin_dashboard, FragmentAdminDashboardBinding::bind) {
    override fun setup(savedInstanceState: Bundle?) {
        if (!container.session.isAdmin) {
            toast("Administrator access only")
            findNavController().navigateUp()
            return
        }
        val nav = findNavController()
        b.users.setOnClickListener { nav.navigate(R.id.usersFragment) }
        b.database.setOnClickListener { nav.navigate(R.id.databaseFragment) }
        b.r4.setOnClickListener { nav.navigate(R.id.reportViewFragment, bundleOf("reportType" to "R4")) }
        b.r5.setOnClickListener { nav.navigate(R.id.reportViewFragment, bundleOf("reportType" to "R5")) }
        b.allReports.setOnClickListener { nav.navigate(R.id.reportsMenuFragment) }
        b.studentView.setOnClickListener { nav.navigate(R.id.dashboardFragment) }
        observe(container.users.observeAllUsers()) { users ->
            val now = System.currentTimeMillis()
            b.stats.text = "${users.size} accounts · ${users.count { it.isActive }} active · " +
                "${users.count { it.isLocked(now) }} locked · ${users.count { !it.isActive }} deactivated"
        }
    }
}

class UserAdapter(
    private val selfId: Long,
    private val onToggleActive: (UserEntity) -> Unit,
    private val onUnlock: (UserEntity) -> Unit,
    private val onReset: (UserEntity) -> Unit,
) : ListAdapter<UserEntity, UserAdapter.Holder>(object : DiffUtil.ItemCallback<UserEntity>() {
    override fun areItemsTheSame(a: UserEntity, b: UserEntity) = a.userId == b.userId
    override fun areContentsTheSame(a: UserEntity, b: UserEntity) = a == b
}) {
    class Holder(val b: ItemUserBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemUserBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val u = getItem(position)
        val locked = u.isLocked()
        with(holder.b) {
            name.text = if (u.userId == selfId) "${u.fullName} (you)" else u.fullName
            email.text = u.email
            status.text = "${u.role.name} · " + when {
                !u.isActive -> "Deactivated"
                locked -> "Locked until ${Format.dateTime(u.lockedUntil)}"
                else -> "Active"
            } + " · last login ${Format.dateTime(u.lastLoginAt)}"
            toggle.text = if (u.isActive) "Deactivate" else "Reactivate"
            toggle.isVisible = u.userId != selfId
            toggle.setOnClickListener { onToggleActive(u) }
            unlock.isVisible = locked
            unlock.setOnClickListener { onUnlock(u) }
            reset.isVisible = u.userId != selfId
            reset.setOnClickListener { onReset(u) }
        }
    }
}

/** View, deactivate, reactivate, unlock and reset the password of accounts (FR-ADM-01). */
class UsersFragment : BaseFragment<FragmentUsersBinding>(R.layout.fragment_users, FragmentUsersBinding::bind) {
    override fun setup(savedInstanceState: Bundle?) {
        if (!container.session.isAdmin) { findNavController().navigateUp(); return }
        val users = container.users
        val adapter = UserAdapter(
            selfId = container.session.userId,
            onToggleActive = { u ->
                val action = if (u.isActive) "Deactivate" else "Reactivate"
                confirm("$action account?", "${u.fullName} (${u.email})", action) {
                    launchSafely { users.setActive(u.userId, !u.isActive)?.let { toast(it) } }
                }
            },
            onUnlock = { u -> launchSafely { users.unlock(u.userId); toast("${u.firstName} can log in again") } },
            onReset = { u ->
                confirm("Reset password?", "${u.fullName} (${u.email}) will get a temporary password.", "Reset") {
                    launchSafely {
                        users.resetPassword(u.userId)
                            .onSuccess { temp -> showTemporaryPassword(u, temp) }
                            .onFailure { toast(it.message ?: "Could not reset the password") }
                    }
                }
            },
        )
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter
        observe(users.observeAllUsers()) { adapter.submitList(it) }
    }

    /** Shown once: the password is stored only as a hash, so it cannot be looked up again later. */
    private fun showTemporaryPassword(u: UserEntity, temp: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Temporary password")
            .setMessage(
                "Give this to ${u.firstName}:\n\n$temp\n\n" +
                    "They should log in with it and change it under Profile. It is shown only once."
            )
            .setPositiveButton("Done", null)
            .show()
    }
}
