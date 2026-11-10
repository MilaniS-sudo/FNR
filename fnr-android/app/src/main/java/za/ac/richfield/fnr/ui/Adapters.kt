package za.ac.richfield.fnr.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import za.ac.richfield.fnr.R
import za.ac.richfield.fnr.data.db.FlashNoteView
import za.ac.richfield.fnr.data.db.RecordingListItem
import za.ac.richfield.fnr.data.db.RecordingStatus
import za.ac.richfield.fnr.data.db.SubjectEntity
import za.ac.richfield.fnr.databinding.ItemNoteBinding
import za.ac.richfield.fnr.databinding.ItemRecordingBinding
import za.ac.richfield.fnr.util.Format

/** Flash note cards (Phase 4, 4.7.4): title, key concept, subject, clip length, times played, star. */
class NoteAdapter(
    private val onOpen: (FlashNoteView) -> Unit,
    private val onStar: (FlashNoteView) -> Unit,
) : ListAdapter<FlashNoteView, NoteAdapter.Holder>(Diff) {

    class Holder(val b: ItemNoteBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val n = getItem(position)
        with(holder.b) {
            title.text = n.title
            concept.text = n.keyConcept.ifBlank { "Key point" }
            subject.text = n.subjectName
            meta.text = "${Format.duration(n.lengthMs)} · played ${n.timesPlayed}×"
            star.setImageResource(if (n.isFavourite) R.drawable.ic_star else R.drawable.ic_star_outline)
            star.contentDescription = if (n.isFavourite) "Remove from favourites" else "Add to favourites"
            star.setOnClickListener { onStar(n) }
            root.setOnClickListener { onOpen(n) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<FlashNoteView>() {
        override fun areItemsTheSame(a: FlashNoteView, b: FlashNoteView) = a.flashNoteId == b.flashNoteId
        override fun areContentsTheSame(a: FlashNoteView, b: FlashNoteView) = a == b
    }
}

class RecordingAdapter(
    private val onOpen: (RecordingListItem) -> Unit,
    private val onProcess: (RecordingListItem) -> Unit,
    private val onDelete: (RecordingListItem) -> Unit,
) : ListAdapter<RecordingListItem, RecordingAdapter.Holder>(Diff) {

    class Holder(val b: ItemRecordingBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val r = getItem(position)
        with(holder.b) {
            title.text = r.title
            meta.text = "${r.subjectName} · ${Format.duration(r.durationMs)} · ${Format.dateTime(r.recordingDate)}"
            status.text = statusLabel(r.status, r.noteCount)
            val canProcess = r.status == RecordingStatus.SAVED || r.status == RecordingStatus.FAILED
            process.isVisible = canProcess
            process.text = if (r.status == RecordingStatus.FAILED) "Retry" else "Generate notes"
            process.setOnClickListener { onProcess(r) }
            delete.setOnClickListener { onDelete(r) }
            root.setOnClickListener { onOpen(r) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<RecordingListItem>() {
        override fun areItemsTheSame(a: RecordingListItem, b: RecordingListItem) = a.recordingId == b.recordingId
        override fun areContentsTheSame(a: RecordingListItem, b: RecordingListItem) = a == b
    }
}

fun statusLabel(status: RecordingStatus, notes: Int = 0): String = when (status) {
    RecordingStatus.SAVED -> "Saved, not processed"
    RecordingStatus.QUEUED -> "Waiting for network"
    RecordingStatus.UPLOADING -> "Uploading"
    RecordingStatus.PROCESSING -> "Transcribing and analysing"
    RecordingStatus.DONE -> "$notes flash notes"
    RecordingStatus.FAILED -> "Failed, tap Retry"
}

/**
 * Fills a subject dropdown. Keeps the current choice when the list refreshes.
 * [allOption] adds "All subjects" (id -1) at the top, used for filters.
 */
class SubjectPicker(
    private val view: MaterialAutoCompleteTextView,
    private val allOption: Boolean = false,
    private val onSelected: (Long) -> Unit = {},
) {
    var selectedId: Long = if (allOption) -1L else 0L
        private set
    private var items: List<Pair<Long, String>> = emptyList()

    init {
        view.setOnItemClickListener { _, _, position, _ ->
            selectedId = items[position].first
            onSelected(selectedId)
        }
    }

    fun submit(subjects: List<SubjectEntity>, preferredId: Long? = null) {
        items = (if (allOption) listOf(-1L to "All subjects") else emptyList()) +
            subjects.map { it.subjectId to it.subjectName }
        view.setAdapter(ArrayAdapter(view.context, android.R.layout.simple_list_item_1, items.map { it.second }))
        val wanted = preferredId ?: selectedId
        val match = items.firstOrNull { it.first == wanted } ?: items.firstOrNull { !allOption || it.first == -1L }
        if (match != null) {
            selectedId = match.first
            view.setText(match.second, false)
        } else {
            selectedId = 0L
            view.setText("", false)
        }
    }

    val hasSelection: Boolean get() = selectedId > 0 || (allOption && selectedId == -1L)
}
