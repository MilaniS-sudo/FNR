package za.ac.richfield.fnr.ui.home

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.map
import za.ac.richfield.fnr.R
import za.ac.richfield.fnr.databinding.FragmentDashboardBinding
import za.ac.richfield.fnr.ui.BaseFragment
import za.ac.richfield.fnr.ui.NoteAdapter
import za.ac.richfield.fnr.util.Format

/** Student home: this week's study time, note counts, quick actions and recent notes. */
class DashboardFragment : BaseFragment<FragmentDashboardBinding>(R.layout.fragment_dashboard, FragmentDashboardBinding::bind) {

    override fun setup(savedInstanceState: Bundle?) {
        val nav = findNavController()
        b.record.setOnClickListener { nav.navigate(R.id.recordFragment) }
        b.upload.setOnClickListener { nav.navigate(R.id.uploadFragment) }
        b.recordings.setOnClickListener { nav.navigate(R.id.recordingsFragment) }
        b.allNotes.setOnClickListener { nav.navigate(R.id.notesFragment) }

        val adapter = NoteAdapter(
            onOpen = { nav.navigate(R.id.playerFragment, bundleOf("flashNoteId" to it.flashNoteId)) },
            onStar = { n -> launchSafely { container.notes.setFavourite(n.flashNoteId, !n.isFavourite) } },
        )
        b.recentList.layoutManager = LinearLayoutManager(requireContext())
        b.recentList.adapter = adapter
        b.recentList.isNestedScrollingEnabled = false

        observe(container.users.observeCurrent()) { user ->
            b.greeting.text = if (user != null) "Hello, ${user.firstName}" else "Hello"
        }
        observe(container.notes.observeDashboard()) { s ->
            b.statMinutes.text = Format.minutes(s.minutesThisWeekMs)
            b.statNotes.text = s.totalNotes.toString()
            b.statNeverPlayed.text = s.neverPlayed.toString()
        }
        observe(container.notes.observeNotes(-1L, false, "").map { it.take(5) }) {
            adapter.submitList(it)
            b.empty.show(it.isEmpty())
        }
    }
}
