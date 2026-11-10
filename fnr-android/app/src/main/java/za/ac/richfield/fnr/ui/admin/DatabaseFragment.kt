package za.ac.richfield.fnr.ui.admin

import android.graphics.Typeface
import android.os.Bundle
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import za.ac.richfield.fnr.R
import za.ac.richfield.fnr.data.repository.DatabaseBrowser
import za.ac.richfield.fnr.databinding.FragmentDatabaseBinding
import za.ac.richfield.fnr.ui.BaseFragment

/**
 * Administrator-only Database Viewer: shows each table of the encrypted database, its columns,
 * primary and foreign keys, and its newest 100 records. Read-only; passwords and study content stay hidden.
 */
class DatabaseFragment : BaseFragment<FragmentDatabaseBinding>(R.layout.fragment_database, FragmentDatabaseBinding::bind) {

    private val browser by lazy { DatabaseBrowser(container.db, container.session) }
    private var tables: List<DatabaseBrowser.Table> = emptyList()

    override fun setup(savedInstanceState: Bundle?) {
        if (!container.session.isAdmin) {
            toast("Administrator access only")
            findNavController().navigateUp()
            return
        }
        launchSafely {
            tables = browser.tables()
            b.summary.text = "Encrypted SQLite database (Room + SQLCipher AES-256) · ${tables.size} tables · " +
                "${tables.sumOf { it.rowCount }} records. Read-only."
            b.chips.removeAllViews()
            tables.forEach { t ->
                b.chips.addView(Chip(requireContext()).apply {
                    id = android.view.View.generateViewId()
                    text = "${t.name} (${t.rowCount})"
                    isCheckable = true
                    setOnClickListener { showTable(t) }
                })
            }
            tables.firstOrNull { it.name == "users" }?.let { first ->
                (b.chips.getChildAt(tables.indexOf(first)) as Chip).isChecked = true
                showTable(first)
            }
        }
    }

    private fun showTable(table: DatabaseBrowser.Table) {
        b.structure.text = buildString {
            append("TABLE ${table.name}\n")
            table.columns.forEach { c ->
                append("  ${c.name}  ${c.type}")
                if (c.primaryKey) append("  PRIMARY KEY")
                if (c.notNull && !c.primaryKey) append("  NOT NULL")
                table.foreignKeys.firstOrNull { it.column == c.name }?.let { fk ->
                    append("  → ${fk.table}.${fk.target} (on delete ${fk.onDelete})")
                }
                append('\n')
            }
        }.trimEnd()
        launchSafely {
            val data = browser.rows(table.name)
            b.info.text = if (data.total == 0) "No records yet."
            else "Showing the newest ${data.shown} of ${data.total} records."
            fill(b.table, data)
        }
    }

    private fun fill(layout: TableLayout, data: DatabaseBrowser.Rows) {
        layout.removeAllViews()
        if (data.rows.isEmpty()) return
        val ctx = requireContext()
        fun cell(text: String, header: Boolean) = TextView(ctx).apply {
            this.text = text
            setPadding(24, 16, 24, 16)
            textSize = 13f
            maxLines = 3
            if (header) {
                setTypeface(typeface, Typeface.BOLD)
                setBackgroundColor(ContextCompat.getColor(ctx, R.color.fnr_primary_container))
                setTextColor(ContextCompat.getColor(ctx, R.color.fnr_on_primary_container))
            }
        }
        layout.addView(TableRow(ctx).apply { data.columns.forEach { addView(cell(it, true)) } })
        data.rows.forEachIndexed { i, row ->
            layout.addView(TableRow(ctx).apply {
                if (i % 2 == 1) setBackgroundColor(0x0F3949AB)
                row.forEach { addView(cell(it, false)) }
            })
        }
    }
}
