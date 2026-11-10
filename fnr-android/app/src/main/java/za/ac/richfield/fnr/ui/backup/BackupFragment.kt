package za.ac.richfield.fnr.ui.backup

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.ac.richfield.fnr.R
import za.ac.richfield.fnr.data.db.BackupLogEntity
import za.ac.richfield.fnr.data.db.BackupStatus
import za.ac.richfield.fnr.data.repository.BackupRepository
import za.ac.richfield.fnr.data.repository.RestoreSummary
import za.ac.richfield.fnr.databinding.FragmentBackupBinding
import za.ac.richfield.fnr.databinding.ItemBackupBinding
import za.ac.richfield.fnr.ui.BaseFragment
import za.ac.richfield.fnr.util.Format

class BackupLogAdapter : ListAdapter<BackupLogEntity, BackupLogAdapter.Holder>(object : DiffUtil.ItemCallback<BackupLogEntity>() {
    override fun areItemsTheSame(a: BackupLogEntity, b: BackupLogEntity) = a.backupId == b.backupId
    override fun areContentsTheSame(a: BackupLogEntity, b: BackupLogEntity) = a == b
}) {
    class Holder(val b: ItemBackupBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemBackupBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val log = getItem(position)
        holder.b.line1.text = "${log.type.name.lowercase().replaceFirstChar { it.uppercase() }} backup · ${Format.dateTime(log.createdAt)}"
        holder.b.line2.text = if (log.status == BackupStatus.SUCCESS)
            "${Format.bytes(log.sizeBytes)} · ${log.fileName} · SHA-256 ${log.checksum.take(12)}…"
        else "Failed · ${log.fileName}"
    }
}

/** Module M6 Backup: manual, automatic, restore (FR-BAK-01 to FR-BAK-03). */
class BackupFragment : BaseFragment<FragmentBackupBinding>(R.layout.fragment_backup, FragmentBackupBinding::bind) {
    private val backups get() = container.backups
    private var settingSwitch = false

    private val createFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) runBackup(uri)
    }
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            confirm(
                "Restore this backup?",
                "Your current subjects, recordings and flash notes will be replaced by the ones in the backup.",
                "Restore",
            ) { runRestore { backups.restore(uri, password()) } }
        }
    }

    override fun setup(savedInstanceState: Bundle?) {
        b.passwordInput.doAfterTextChanged { showError(b.passwordLayout, null) }
        b.backupNow.setOnClickListener {
            if (!requirePassword()) return@setOnClickListener
            launchSafely {
                val name = container.users.current()?.firstName ?: "user"
                createFile.launch(BackupRepository.suggestedFileName(name))
            }
        }
        b.restore.setOnClickListener {
            if (requirePassword()) openFile.launch(arrayOf("*/*"))
        }
        b.restoreAuto.setOnClickListener {
            if (!requirePassword()) return@setOnClickListener
            val file = backups.latestAutoBackup() ?: return@setOnClickListener
            confirm(
                "Restore automatic backup?",
                "Restore the automatic backup from ${Format.dateTime(file.lastModified())}? Your current data will be replaced.",
                "Restore",
            ) { runRestore { backups.restoreFile(file, password()) } }
        }
        b.runAuto.setOnClickListener {
            busy(true)
            launchSafely({ busy(false); toast(it) }) {
                val file = backups.createAutoBackup(container.session.requireUserId())
                busy(false)
                toast("Automatic backup saved (${Format.bytes(file.length())})")
                refreshAuto()
            }
        }
        b.autoSwitch.setOnCheckedChangeListener { _, checked ->
            if (settingSwitch) return@setOnCheckedChangeListener
            if (checked) {
                if (!requirePassword()) { setSwitch(false); return@setOnCheckedChangeListener }
                busy(true)
                launchSafely({ busy(false); setSwitch(false); toast(it) }) {
                    backups.enableAutoBackup(password())
                    busy(false)
                    toast("Automatic weekly backup is on")
                    refreshAuto()
                }
            } else {
                backups.disableAutoBackup()
                refreshAuto()
            }
        }

        val adapter = BackupLogAdapter()
        b.history.layoutManager = LinearLayoutManager(requireContext())
        b.history.adapter = adapter
        observe(backups.observeHistory()) {
            adapter.submitList(it)
            b.noHistory.show(it.isEmpty())
        }
        refreshAuto()
    }

    private fun refreshAuto() {
        val enabled = backups.autoBackupEnabled()
        setSwitch(enabled)
        b.runAuto.show(enabled)
        val latest = backups.latestAutoBackup()
        b.restoreAuto.show(latest != null)
        b.autoInfo.text = when {
            !enabled -> "Off. Turn on to back up weekly while the phone is charging and idle. The newest 3 are kept."
            latest != null -> "On. Latest automatic backup: ${Format.dateTime(latest.lastModified())}."
            else -> "On. The first automatic backup runs the next time the phone is charging and idle."
        }
    }

    private fun setSwitch(on: Boolean) {
        settingSwitch = true
        b.autoSwitch.isChecked = on
        settingSwitch = false
    }

    private fun password() = b.passwordInput.text.toString()

    private fun requirePassword(): Boolean {
        if (password().isEmpty()) {
            showError(b.passwordLayout, "Enter your account password first")
            return false
        }
        return true
    }

    private fun busy(on: Boolean) {
        b.progress.show(on)
        b.backupNow.isEnabled = !on
        b.restore.isEnabled = !on
        b.restoreAuto.isEnabled = !on
    }

    private fun runBackup(uri: Uri) {
        busy(true)
        launchSafely({ busy(false); showError(b.passwordLayout, it.takeIf { m -> m.contains("password", true) }); toast(it) }) {
            val size = backups.createManualBackup(uri, password())
            busy(false)
            toast("Backup saved (${Format.bytes(size)}). Keep a copy on Google Drive or a computer.")
        }
    }

    private fun runRestore(action: suspend () -> RestoreSummary) {
        busy(true)
        launchSafely({ busy(false); toast(it) }) {
            val summary = withContext(Dispatchers.Default) { action() }
            busy(false)
            toast("Restored ${summary.subjects} subjects, ${summary.recordings} recordings and ${summary.notes} flash notes.")
        }
    }
}
