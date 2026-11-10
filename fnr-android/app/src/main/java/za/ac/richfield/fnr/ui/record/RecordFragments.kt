package za.ac.richfield.fnr.ui.record

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModel
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.combine
import za.ac.richfield.fnr.R
import za.ac.richfield.fnr.audio.AudioValidator
import za.ac.richfield.fnr.audio.RecPhase
import za.ac.richfield.fnr.audio.Recorder
import za.ac.richfield.fnr.data.db.JobStatus
import za.ac.richfield.fnr.data.db.RecordingStatus
import za.ac.richfield.fnr.data.repository.RecordingRepository
import za.ac.richfield.fnr.data.repository.SubjectRepository
import za.ac.richfield.fnr.databinding.FragmentProcessingBinding
import za.ac.richfield.fnr.databinding.FragmentRecordBinding
import za.ac.richfield.fnr.databinding.FragmentRecordingsBinding
import za.ac.richfield.fnr.databinding.FragmentUploadBinding
import za.ac.richfield.fnr.security.Validators
import za.ac.richfield.fnr.ui.BaseFragment
import za.ac.richfield.fnr.ui.RecordingAdapter
import za.ac.richfield.fnr.ui.SubjectPicker
import za.ac.richfield.fnr.ui.appViewModels
import za.ac.richfield.fnr.util.Format

class RecordViewModel(val recordings: RecordingRepository, val subjects: SubjectRepository) : ViewModel() {
    val subjectList = subjects.observe()
    suspend fun process(recordingId: Long) = recordings.enqueueProcessing(recordingId)
}

private fun BaseFragment<*>.openProcessing(recordingId: Long) {
    findNavController().navigate(R.id.processingFragment, bundleOf("recordingId" to recordingId))
}

/** Record screen (FR-REC-01 to FR-REC-03). The RecordingService does the actual recording. */
class RecordFragment : BaseFragment<FragmentRecordBinding>(R.layout.fragment_record, FragmentRecordBinding::bind) {
    private val vm: RecordViewModel by appViewModels { RecordViewModel(it.recordings, it.subjects) }
    private lateinit var picker: SubjectPicker
    private var pendingStart = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            if (pendingStart) start()
        } else {
            toast("FNR needs the microphone to record. Allow it in Settings > Apps > Flash Note Recorder > Permissions.")
        }
        pendingStart = false
    }

    override fun setup(savedInstanceState: Bundle?) {
        picker = SubjectPicker(b.subjectInput) { showError(b.subjectLayout, null) }
        observe(vm.subjectList) { list ->
            picker.submit(list)
            b.noSubjects.show(list.isEmpty())
        }
        b.addSubject.setOnClickListener { findNavController().navigate(R.id.subjectsFragment) }
        b.upload.setOnClickListener { findNavController().navigate(R.id.uploadFragment) }
        b.titleInput.doAfterTextChanged { showError(b.titleLayout, Validators.recordingTitle(it.toString())) }

        b.start.setOnClickListener { start() }
        b.pause.setOnClickListener {
            if (Recorder.state.value.phase == RecPhase.PAUSED) Recorder.resume(requireContext()) else Recorder.pause(requireContext())
        }
        b.stop.setOnClickListener { Recorder.stop(requireContext()) }
        b.generate.setOnClickListener {
            val id = Recorder.state.value.savedRecordingId ?: return@setOnClickListener
            launchSafely {
                vm.process(id)
                Recorder.acknowledge()
                openProcessing(id)
            }
        }
        b.recordAgain.setOnClickListener { Recorder.acknowledge() }

        observe(Recorder.state) { s ->
            b.timer.text = Format.duration(s.elapsedMs)
            b.level.setProgressCompat(s.level, true)
            val idle = s.phase == RecPhase.IDLE
            val active = s.phase == RecPhase.RECORDING || s.phase == RecPhase.PAUSED
            b.setupGroup.show(idle)
            b.start.show(idle)
            b.pause.show(active)
            b.stop.show(active)
            b.pause.text = if (s.phase == RecPhase.PAUSED) "Resume" else "Pause"
            b.pause.setIconResource(if (s.phase == RecPhase.PAUSED) R.drawable.ic_mic else R.drawable.ic_pause)
            b.status.text = when (s.phase) {
                RecPhase.IDLE -> "Choose a subject, then press Start and explain the topic out loud."
                RecPhase.RECORDING -> "Recording… keep explaining in your own words."
                RecPhase.PAUSED -> "Paused"
                RecPhase.SAVING -> "Saving…"
                RecPhase.SAVED -> s.message ?: "Saved"
                RecPhase.ERROR -> s.message ?: "Recording failed"
            }
            b.resultCard.show(s.phase == RecPhase.SAVED || s.phase == RecPhase.ERROR)
            b.generate.show(s.phase == RecPhase.SAVED)
        }
    }

    private fun start() {
        val ctx = requireContext()
        if (!picker.hasSelection) {
            showError(b.subjectLayout, "Choose a subject first")
            return
        }
        val titleError = Validators.recordingTitle(b.titleInput.text.toString())
        if (titleError != null) { showError(b.titleLayout, titleError); return }
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Manifest.permission.RECORD_AUDIO in needed) {
            pendingStart = true
            permissions.launch(needed.toTypedArray())
            return
        }
        Recorder.start(ctx, container.session.requireUserId(), picker.selectedId, b.titleInput.text.toString())
        b.titleInput.text = null
    }
}

/** Upload an existing audio file (FR-REC-04, FR-REC-05). */
class UploadFragment : BaseFragment<FragmentUploadBinding>(R.layout.fragment_upload, FragmentUploadBinding::bind) {
    private val vm: RecordViewModel by appViewModels { RecordViewModel(it.recordings, it.subjects) }
    private lateinit var picker: SubjectPicker
    private var chosen: Uri? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            chosen = uri
            b.fileName.text = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected file"
            b.fileError.show(false)
            refresh()
        }
    }

    override fun setup(savedInstanceState: Bundle?) {
        picker = SubjectPicker(b.subjectInput) { showError(b.subjectLayout, null); refresh() }
        observe(vm.subjectList) { picker.submit(it); refresh() }
        b.rules.text = "MP3, M4A, AAC or WAV · at most ${AudioValidator.MAX_BYTES / (1024 * 1024)} MB · 10 seconds to 100 minutes"
        b.choose.setOnClickListener { pickFile.launch(arrayOf("audio/*")) }
        b.titleInput.doAfterTextChanged { showError(b.titleLayout, Validators.recordingTitle(it.toString())); refresh() }
        b.uploadButton.setOnClickListener { upload(processAfter = false) }
        b.uploadProcess.setOnClickListener { upload(processAfter = true) }
        refresh()
    }

    private fun refresh() {
        val ok = chosen != null && picker.hasSelection && Validators.recordingTitle(b.titleInput.text.toString()) == null
        b.uploadButton.isEnabled = ok
        b.uploadProcess.isEnabled = ok
    }

    private fun upload(processAfter: Boolean) {
        val uri = chosen ?: return
        b.progress.show(true)
        b.uploadButton.isEnabled = false
        b.uploadProcess.isEnabled = false
        launchSafely({ msg ->
            b.progress.show(false)
            b.fileError.text = msg
            b.fileError.show(true)
            refresh()
        }) {
            val id = vm.recordings.importUpload(picker.selectedId, b.titleInput.text.toString(), uri)
            b.progress.show(false)
            if (processAfter) {
                vm.process(id)
                openProcessing(id)
            } else {
                toast("File imported")
                findNavController().navigate(R.id.recordingsFragment)
            }
        }
    }
}

/** All recordings with their processing status; process, retry or delete (FR-AI-06, FR-LIB-08). */
class RecordingsFragment : BaseFragment<FragmentRecordingsBinding>(R.layout.fragment_recordings, FragmentRecordingsBinding::bind) {
    private val vm: RecordViewModel by appViewModels { RecordViewModel(it.recordings, it.subjects) }

    override fun setup(savedInstanceState: Bundle?) {
        val adapter = RecordingAdapter(
            onOpen = { openProcessing(it.recordingId) },
            onProcess = { r -> launchSafely { vm.process(r.recordingId); openProcessing(r.recordingId) } },
            onDelete = { r ->
                confirm(
                    "Delete recording?",
                    "\"${r.title}\" and its ${r.noteCount} flash notes will be deleted. This cannot be undone.",
                    "Delete",
                ) { launchSafely { vm.recordings.delete(r.recordingId); toast("Recording deleted") } }
            },
        )
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter
        b.record.setOnClickListener { findNavController().navigate(R.id.recordFragment) }
        b.upload.setOnClickListener { findNavController().navigate(R.id.uploadFragment) }
        observe(vm.recordings.observeForUser()) {
            adapter.submitList(it)
            b.empty.show(it.isEmpty())
        }
    }
}

/** Progress steps for one recording (FR-AI-04) with Retry on failure (FR-AI-06). */
class ProcessingFragment : BaseFragment<FragmentProcessingBinding>(R.layout.fragment_processing, FragmentProcessingBinding::bind) {
    private val vm: RecordViewModel by appViewModels { RecordViewModel(it.recordings, it.subjects) }

    override fun setup(savedInstanceState: Bundle?) {
        val id = requireArguments().getLong("recordingId")
        b.retry.setOnClickListener { launchSafely { vm.process(id) } }
        b.process.setOnClickListener { launchSafely { vm.process(id) } }

        observe(vm.recordings.observe(id).combine(vm.recordings.observeLatestJob(id)) { r, j -> r to j }) { (rec, job) ->
            if (rec == null) { b.title.text = "This recording was deleted"; return@observe }
            b.title.text = rec.title
            b.meta.text = "${Format.duration(rec.durationMs)} · ${Format.bytes(rec.fileSizeBytes)} · ${Format.dateTime(rec.recordingDate)}"
            val order = listOf(RecordingStatus.QUEUED, RecordingStatus.UPLOADING, RecordingStatus.PROCESSING, RecordingStatus.DONE)
            val reached = order.indexOf(rec.status)
            fun mark(step: Int, label: String) = when {
                rec.status == RecordingStatus.DONE || reached > step -> "✓  $label"
                reached == step -> "▶  $label"
                else -> "○  $label"
            }
            b.step1.text = "✓  Recording saved on this phone"
            b.step2.text = mark(0, "Waiting for a network connection")
            b.step3.text = mark(1, "Uploading to the AI Gateway")
            b.step4.text = mark(2, "Transcribing and finding key points")
            b.step5.text = if (rec.status == RecordingStatus.DONE) "✓  Flash notes ready" else "○  Flash notes ready"

            val running = rec.status in setOf(RecordingStatus.QUEUED, RecordingStatus.UPLOADING, RecordingStatus.PROCESSING)
            b.progress.show(running)
            b.process.show(rec.status == RecordingStatus.SAVED)
            b.errorCard.show(rec.status == RecordingStatus.FAILED)
            b.error.text = job?.errorMessage ?: "Processing failed."
            b.doneCard.show(rec.status == RecordingStatus.DONE)
            if (rec.status == RecordingStatus.DONE) {
                val noteMessage = if (job != null && job.status == JobStatus.SUCCESS) job.errorMessage else null
                val finished = job?.processedAt
                b.doneText.text = if (noteMessage != null) {
                    "$noteMessage. Try explaining definitions clearly, for example \"X is defined as…\"."
                } else if (job != null && finished != null) {
                    "Done in ${Format.duration(finished - job.startedAt)} using ${job.modelUsed}."
                } else {
                    "Your flash notes are ready."
                }
            }
            b.viewNotes.setOnClickListener {
                findNavController().navigate(R.id.notesFragment, bundleOf("subjectId" to rec.subjectId, "subjectName" to "Flash notes"))
            }
        }
    }
}
