package za.ac.richfield.fnr.ui.library

import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import za.ac.richfield.fnr.R
import za.ac.richfield.fnr.data.db.FlashNoteView
import za.ac.richfield.fnr.data.db.SubjectWithCount
import za.ac.richfield.fnr.data.repository.FlashNoteRepository
import za.ac.richfield.fnr.data.repository.SubjectRepository
import za.ac.richfield.fnr.databinding.DialogSubjectBinding
import za.ac.richfield.fnr.databinding.FragmentEditNoteBinding
import za.ac.richfield.fnr.databinding.FragmentNotesBinding
import za.ac.richfield.fnr.databinding.FragmentPlayerBinding
import za.ac.richfield.fnr.databinding.FragmentSubjectsBinding
import za.ac.richfield.fnr.databinding.ItemSubjectBinding
import za.ac.richfield.fnr.security.Validators
import za.ac.richfield.fnr.ui.BaseFragment
import za.ac.richfield.fnr.ui.NoteAdapter
import za.ac.richfield.fnr.ui.SubjectPicker
import za.ac.richfield.fnr.ui.appViewModels
import za.ac.richfield.fnr.util.Format
import java.io.File
import java.util.Locale

class LibraryViewModel(val notes: FlashNoteRepository, val subjects: SubjectRepository) : ViewModel() {
    val subjectId = MutableStateFlow(-1L)
    val query = MutableStateFlow("")
    val favouritesOnly = MutableStateFlow(false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val filteredNotes = combine(subjectId, query, favouritesOnly) { s, q, f -> Triple(s, q, f) }
        .flatMapLatest { (s, q, f) -> notes.observeNotes(s, f, q) }
}

// ---------------------------------------------------------------- Subjects ---

class SubjectAdapter(
    private val onOpen: (SubjectWithCount) -> Unit,
    private val onEdit: (SubjectWithCount) -> Unit,
    private val onDelete: (SubjectWithCount) -> Unit,
) : ListAdapter<SubjectWithCount, SubjectAdapter.Holder>(object : DiffUtil.ItemCallback<SubjectWithCount>() {
    override fun areItemsTheSame(a: SubjectWithCount, b: SubjectWithCount) = a.subjectId == b.subjectId
    override fun areContentsTheSame(a: SubjectWithCount, b: SubjectWithCount) = a == b
}) {
    class Holder(val b: ItemSubjectBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemSubjectBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val s = getItem(position)
        with(holder.b) {
            name.text = s.subjectName
            counts.text = "${s.noteCount} flash notes · ${s.recordingCount} recordings"
            description.text = s.description.orEmpty()
            description.visibility = if (s.description.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
            edit.setOnClickListener { onEdit(s) }
            delete.setOnClickListener { onDelete(s) }
            root.setOnClickListener { onOpen(s) }
        }
    }
}

/** Library home: subjects with counts; add, rename, delete (FR-LIB-01). */
class SubjectsFragment : BaseFragment<FragmentSubjectsBinding>(R.layout.fragment_subjects, FragmentSubjectsBinding::bind) {
    private val vm: LibraryViewModel by appViewModels { LibraryViewModel(it.notes, it.subjects) }

    override fun setup(savedInstanceState: Bundle?) {
        val adapter = SubjectAdapter(
            onOpen = { findNavController().navigate(R.id.notesFragment, bundleOf("subjectId" to it.subjectId, "subjectName" to it.subjectName)) },
            onEdit = { showDialog(it) },
            onDelete = { s ->
                confirm("Delete subject?", "\"${s.subjectName}\" will be deleted.", "Delete") {
                    launchSafely { vm.subjects.delete(s.subjectId); toast("Subject deleted") }
                }
            },
        )
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter
        b.add.setOnClickListener { showDialog(null) }
        b.allNotes.setOnClickListener { findNavController().navigate(R.id.notesFragment) }
        observe(vm.subjects.observeWithCounts()) {
            adapter.submitList(it)
            b.empty.show(it.isEmpty())
        }
    }

    /** Add or edit dialog; it stays open until the input is valid. */
    private fun showDialog(existing: SubjectWithCount?) {
        val d = DialogSubjectBinding.inflate(layoutInflater)
        existing?.let { d.nameInput.setText(it.subjectName); d.descriptionInput.setText(it.description.orEmpty()) }
        d.nameInput.doAfterTextChanged { d.nameLayout.error = Validators.subjectName(it.toString()) }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) "New subject" else "Edit subject")
            .setView(d.root)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = d.nameInput.text.toString()
            val desc = d.descriptionInput.text.toString()
            Validators.subjectName(name)?.let { d.nameLayout.error = it; return@setOnClickListener }
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    if (existing == null) vm.subjects.create(name, desc) else vm.subjects.update(existing.subjectId, name, desc)
                    dialog.dismiss()
                } catch (e: Exception) {
                    d.nameLayout.error = e.message
                }
            }
        }
    }
}

// ------------------------------------------------------------------- Notes ---

/** Flash note list with search and filters (FR-LIB-02, FR-LIB-04). */
class NotesFragment : BaseFragment<FragmentNotesBinding>(R.layout.fragment_notes, FragmentNotesBinding::bind) {
    private val vm: LibraryViewModel by appViewModels { LibraryViewModel(it.notes, it.subjects) }

    override fun setup(savedInstanceState: Bundle?) {
        val startSubject = arguments?.getLong("subjectId", -1L) ?: -1L
        if (savedInstanceState == null) vm.subjectId.value = startSubject
        val picker = SubjectPicker(b.subjectInput, allOption = true) { vm.subjectId.value = it }
        observe(vm.subjects.observe()) { picker.submit(it, vm.subjectId.value) }

        b.searchInput.setText(vm.query.value)
        b.searchInput.doAfterTextChanged { vm.query.value = it.toString() }
        b.favourites.isChecked = vm.favouritesOnly.value
        b.favourites.setOnCheckedChangeListener { _, checked -> vm.favouritesOnly.value = checked }

        val adapter = NoteAdapter(
            onOpen = { findNavController().navigate(R.id.playerFragment, bundleOf("flashNoteId" to it.flashNoteId)) },
            onStar = { n -> launchSafely { vm.notes.setFavourite(n.flashNoteId, !n.isFavourite) } },
        )
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter
        observe(vm.filteredNotes) { list ->
            adapter.submitList(list)
            b.count.text = "${list.size} flash notes · ${Format.duration(list.sumOf { it.lengthMs })} of listening"
            b.empty.show(list.isEmpty())
            b.empty.text = if (vm.query.value.isNotBlank() || vm.favouritesOnly.value) "No flash notes match your search."
            else "No flash notes yet. Record a study session and tap Generate flash notes."
        }
    }
}

// ------------------------------------------------------------------ Player ---

/**
 * Plays one flash note: the clip of the student's own voice (FR-LIB-05), or a spoken
 * version of the summary (FR-LIB-06). Every listening session is logged (FR-LIB-07).
 */
class PlayerFragment : BaseFragment<FragmentPlayerBinding>(R.layout.fragment_player, FragmentPlayerBinding::bind) {
    private var player: ExoPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var note: FlashNoteView? = null
    private var loadedClip: Triple<String, Long, Long>? = null
    private var progressJob: Job? = null
    private var playStartedAt = 0L
    private var listenedMs = 0L

    override fun setup(savedInstanceState: Bundle?) {
        val id = requireArguments().getLong("flashNoteId")
        b.play.setOnClickListener { togglePlay() }
        b.replay.setOnClickListener { endSession(); player?.seekTo(0); player?.play() }
        b.repeat.addOnCheckedChangeListener { _, checked ->
            player?.repeatMode = if (checked) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }
        b.speak.setOnClickListener { speakSummary() }
        b.edit.setOnClickListener { findNavController().navigate(R.id.editNoteFragment, bundleOf("flashNoteId" to id)) }
        b.favourite.setOnClickListener {
            val n = note ?: return@setOnClickListener
            launchSafely { container.notes.setFavourite(n.flashNoteId, !n.isFavourite) }
        }
        b.delete.setOnClickListener {
            confirm("Delete flash note?", "It will be removed from your library.", "Delete") {
                launchSafely { container.notes.delete(id); findNavController().navigateUp() }
            }
        }

        observe(container.notes.observeNote(id)) { n ->
            if (n == null) { b.title.text = "This flash note was deleted"; return@observe }
            note = n
            b.title.text = n.title
            b.concept.text = n.keyConcept.ifBlank { "Key point" }
            b.subject.text = "${n.subjectName} · from \"${n.recordingTitle}\""
            b.summary.text = n.content.ifBlank { "No summary." }
            b.clipInfo.text = "Clip ${Format.duration(n.startMs)} – ${Format.duration(n.endMs)} of the recording · played ${n.timesPlayed}×"
            b.favourite.setIconResource(if (n.isFavourite) R.drawable.ic_star else R.drawable.ic_star_outline)
            b.favourite.text = if (n.isFavourite) "Favourite" else "Add to favourites"
            val clip = Triple(n.filePath, n.startMs, n.endMs)
            if (clip != loadedClip) {
                loadedClip = clip
                prepare(n)
                b.transcript.text = container.notes.transcriptFor(n.recordingId, n.startMs, n.endMs).ifBlank { "No transcript stored." }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val p = ExoPlayer.Builder(requireContext()).build()
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (_isAlive()) {
                    b.play.text = if (isPlaying) "Pause" else "Play"
                    b.play.setIconResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                }
                if (isPlaying) playStartedAt = System.currentTimeMillis()
                else if (playStartedAt > 0) { listenedMs += System.currentTimeMillis() - playStartedAt; playStartedAt = 0 }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) endSession()
            }
        })
        player = p
        p.repeatMode = if (b.repeat.isChecked) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        note?.let { prepare(it) }
        tts = TextToSpeech(requireContext()) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.setLanguage(Locale.getDefault())
        }
        progressJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val pl = player
                val n = note
                if (pl != null && n != null && n.lengthMs > 0) {
                    val pos = pl.currentPosition.coerceIn(0, n.lengthMs)
                    b.progress.setProgressCompat((pos * 1000 / n.lengthMs).toInt(), false)
                    b.position.text = "${Format.duration(pos)} / ${Format.duration(n.lengthMs)}"
                }
                delay(250)
            }
        }
    }

    override fun onStop() {
        endSession()
        progressJob?.cancel()
        player?.release()
        player = null
        loadedClip = null
        tts?.shutdown()
        tts = null
        super.onStop()
    }

    private fun _isAlive() = view != null

    private fun prepare(n: FlashNoteView) {
        val p = player ?: return
        val file = File(n.filePath)
        if (!file.exists()) {
            b.play.isEnabled = false
            toast("The audio file for this note is missing.")
            return
        }
        // Plays only StartMs..EndMs of the original recording: no audio is copied (NFR-RES-01).
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(n.startMs)
                    .setEndPositionMs(n.endMs)
                    .build()
            )
            .build()
        p.setMediaItem(item)
        p.prepare()
        loadedClip = Triple(n.filePath, n.startMs, n.endMs)
    }

    private fun togglePlay() {
        val p = player ?: return
        tts?.stop()
        if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.play()
        }
    }

    private fun speakSummary() {
        val n = note ?: return
        if (!ttsReady) { toast("Text-to-speech is not ready on this device yet."); return }
        player?.pause()
        val text = n.content.ifBlank { n.title }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "note_${n.flashNoteId}")
        // Spoken summaries count as revision too (about 15 characters per second of speech)
        val seconds = (text.length / 15).coerceAtLeast(1)
        container.appScope.launch { container.notes.logPlayback(n.voiceNoteId, seconds) }
    }

    /** Logs one listening session if the student listened for at least a second. */
    private fun endSession() {
        if (playStartedAt > 0) { listenedMs += System.currentTimeMillis() - playStartedAt; playStartedAt = System.currentTimeMillis() }
        val seconds = (listenedMs / 1000).toInt()
        val n = note
        listenedMs = 0
        if (seconds >= 1 && n != null) container.appScope.launch { container.notes.logPlayback(n.voiceNoteId, seconds) }
    }
}

// --------------------------------------------------------------- Edit note ---

/** Lets the student correct the AI: title, summary and clip boundaries (FR-LIB-03). */
class EditNoteFragment : BaseFragment<FragmentEditNoteBinding>(R.layout.fragment_edit_note, FragmentEditNoteBinding::bind) {
    private var note: FlashNoteView? = null
    private var preview: ExoPlayer? = null

    override fun setup(savedInstanceState: Bundle?) {
        val id = requireArguments().getLong("flashNoteId")
        b.titleInput.doAfterTextChanged { showError(b.titleLayout, Validators.noteTitle(it.toString())) }
        b.summaryInput.doAfterTextChanged { showError(b.summaryLayout, Validators.noteSummary(it.toString())) }
        b.range.addOnChangeListener { slider, _, _ -> showRange(slider.values) }

        launchSafely {
            val n = container.notes.observeNote(id).first() ?: run { findNavController().navigateUp(); return@launchSafely }
            note = n
            if (savedInstanceState == null) {
                b.titleInput.setText(n.title)
                b.summaryInput.setText(n.content)
            }
            val maxSec = (n.recordingDurationMs / 1000).toFloat().coerceAtLeast(1f)
            b.range.valueFrom = 0f
            b.range.valueTo = maxSec
            b.range.stepSize = 1f
            b.range.values = listOf(
                (n.startMs / 1000).toFloat().coerceIn(0f, maxSec - 1),
                (n.endMs / 1000).toFloat().coerceIn(1f, maxSec),
            )
            showRange(b.range.values)
        }

        b.preview.setOnClickListener { playPreview() }
        b.save.setOnClickListener { save() }
    }

    private fun showRange(values: List<Float>) {
        if (values.size < 2) return // before the note has loaded the slider has one thumb
        val start = (values[0] * 1000).toLong()
        val end = (values[1] * 1000).toLong()
        val n = note ?: return
        val err = Validators.clip(start, end, n.recordingDurationMs)
        b.rangeLabel.text = "${Format.duration(start)} – ${Format.duration(end)} (${Format.duration(end - start)})"
        b.rangeError.text = err.orEmpty()
        b.rangeError.show(err != null)
    }

    private fun playPreview() {
        val n = note ?: return
        if (b.range.values.size < 2) return
        val start = (b.range.values[0] * 1000).toLong()
        val end = (b.range.values[1] * 1000).toLong()
        preview?.release()
        preview = ExoPlayer.Builder(requireContext()).build().also {
            it.setMediaItem(
                MediaItem.Builder().setUri(Uri.fromFile(File(n.filePath)))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start).setEndPositionMs(end).build()
                    ).build()
            )
            it.prepare()
            it.play()
        }
    }

    private fun save() {
        val n = note ?: return
        if (b.range.values.size < 2) return
        val title = b.titleInput.text.toString()
        val summary = b.summaryInput.text.toString()
        val start = (b.range.values[0] * 1000).toLong()
        val end = (b.range.values[1] * 1000).toLong()
        val clipError = Validators.clip(start, end, n.recordingDurationMs)
        showError(b.titleLayout, Validators.noteTitle(title))
        if (Validators.noteTitle(title) != null || Validators.noteSummary(summary) != null || clipError != null) {
            toast(clipError ?: "Fix the highlighted fields")
            return
        }
        launchSafely {
            container.notes.updateText(n.flashNoteId, title, summary)
            container.notes.updateClip(n, start, end)
            toast("Flash note saved")
            findNavController().navigateUp()
        }
    }

    override fun onStop() {
        preview?.release()
        preview = null
        super.onStop()
    }
}
