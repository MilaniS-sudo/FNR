package za.ac.richfield.fnr.ui.reports

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.TableRow
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModel
import androidx.navigation.fragment.findNavController
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.ac.richfield.fnr.R
import za.ac.richfield.fnr.data.repository.ReportData
import za.ac.richfield.fnr.data.repository.ReportRepository
import za.ac.richfield.fnr.data.repository.ReportType
import za.ac.richfield.fnr.databinding.FragmentReportViewBinding
import za.ac.richfield.fnr.databinding.FragmentReportsMenuBinding
import za.ac.richfield.fnr.databinding.ItemReportBinding
import za.ac.richfield.fnr.security.Validators
import za.ac.richfield.fnr.ui.BaseFragment
import za.ac.richfield.fnr.ui.SubjectPicker
import za.ac.richfield.fnr.ui.appViewModels
import za.ac.richfield.fnr.util.Dates
import za.ac.richfield.fnr.util.Format
import za.ac.richfield.fnr.util.ReportPdfExporter
import java.util.Calendar
import java.util.TimeZone

class ReportViewModel(val reports: ReportRepository) : ViewModel() {
    var from: Long = Dates.daysAgo(30)
    var to: Long = Dates.endOfDay(System.currentTimeMillis())
    var subjectId: Long = -1L
    var last: ReportData? = null

    suspend fun generate(type: ReportType): ReportData =
        reports.generate(type, from, to, subjectId).also { last = it }
}

/** Lists R1-R3 for everyone and R4-R5 for administrators (FR-RPT-01). */
class ReportsMenuFragment : BaseFragment<FragmentReportsMenuBinding>(R.layout.fragment_reports_menu, FragmentReportsMenuBinding::bind) {
    override fun setup(savedInstanceState: Bundle?) {
        val admin = container.session.isAdmin
        b.list.removeAllViews()
        ReportType.entries.filter { admin || !it.adminOnly }.forEach { type ->
            val item = ItemReportBinding.inflate(LayoutInflater.from(requireContext()), b.list, false)
            item.code.text = type.name
            item.name.text = type.title
            item.description.text = if (type.adminOnly) "${type.description} (administrator)" else type.description
            item.root.setOnClickListener {
                findNavController().navigate(R.id.reportViewFragment, bundleOf("reportType" to type.name))
            }
            b.list.addView(item.root)
        }
    }
}

/** Shows one report with filters, a chart and a table, and exports it to PDF (FR-RPT-02). */
class ReportViewFragment : BaseFragment<FragmentReportViewBinding>(R.layout.fragment_report_view, FragmentReportViewBinding::bind) {
    private val vm: ReportViewModel by appViewModels { ReportViewModel(it.reports) }
    private lateinit var type: ReportType

    private val createPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val data = vm.last ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        launchSafely {
            val name = container.users.current()?.fullName ?: "FNR user"
            withContext(Dispatchers.IO) {
                requireContext().contentResolver.openOutputStream(uri)?.use { ReportPdfExporter.write(data, name, it) }
            }
            toast("PDF saved")
        }
    }

    override fun setup(savedInstanceState: Bundle?) {
        type = runCatching { ReportType.valueOf(requireArguments().getString("reportType").orEmpty()) }.getOrDefault(ReportType.R1)
        if (type.adminOnly && !container.session.isAdmin) {
            toast("Only administrators can open this report.")
            findNavController().navigateUp()
            return
        }
        b.title.text = "${type.name} ${type.title}"
        b.description.text = type.description
        b.dates.show(type.usesDates)
        b.subjectLayout.show(type.usesSubject)
        updateDateLabel()

        val picker = SubjectPicker(b.subjectInput, allOption = true) { vm.subjectId = it; load() }
        if (type.usesSubject) observe(container.subjects.observe()) { picker.submit(it, vm.subjectId) }

        b.dates.setOnClickListener {
            val dialog = MaterialDatePicker.Builder.dateRangePicker()
                .setTitleText("Report period")
                .setSelection(androidx.core.util.Pair(localToUtc(vm.from), localToUtc(vm.to)))
                .build()
            dialog.addOnPositiveButtonClickListener { sel ->
                val from = Dates.startOfDay(utcToLocal(sel.first))
                val to = Dates.endOfDay(utcToLocal(sel.second))
                val error = Validators.dateRange(from, to)
                if (error != null) { toast(error); return@addOnPositiveButtonClickListener }
                vm.from = from
                vm.to = to
                updateDateLabel()
                load()
            }
            dialog.show(childFragmentManager, "range")
        }
        b.export.setOnClickListener {
            if (vm.last?.isEmpty != false) { toast("There is no data to export for this period."); return@setOnClickListener }
            createPdf.launch("FNR_${type.name}_${Format.fileStamp()}.pdf")
        }
        load()
    }

    private fun updateDateLabel() {
        b.dates.text = "${Format.date(vm.from)} – ${Format.date(vm.to)}"
    }

    private fun load() {
        b.progress.show(true)
        launchSafely({ b.progress.show(false); toast(it) }) {
            val data = vm.generate(type)
            b.progress.show(false)
            render(data)
        }
    }

    private fun render(data: ReportData) {
        b.summary.text = data.summary.joinToString("\n") { (k, v) -> "$k: $v" }
        b.chartTitle.text = data.chartTitle
        b.chartTitle.show(data.chart.isNotEmpty())
        b.chart.show(data.chart.isNotEmpty())
        b.chart.setData(data.chart)
        b.empty.show(data.isEmpty)
        b.table.removeAllViews()
        if (data.isEmpty) return
        b.table.addView(row(data.columns, header = true))
        data.rows.forEachIndexed { i, cells -> b.table.addView(row(cells, header = false, shaded = i % 2 == 1)) }
    }

    private fun row(cells: List<String>, header: Boolean, shaded: Boolean = false): TableRow {
        val ctx = requireContext()
        val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()
        return TableRow(ctx).apply {
            if (header) setBackgroundColor(ContextCompat.getColor(ctx, R.color.fnr_primary_container))
            else if (shaded) setBackgroundColor(0x0F000000)
            cells.forEach { value ->
                addView(TextView(ctx).apply {
                    text = value
                    setPadding(pad, pad, pad, pad)
                    maxWidth = pad * 28
                    if (header) setTypeface(typeface, Typeface.BOLD)
                })
            }
        }
    }

    // The date picker works in UTC midnight; the database stores local time.
    private fun utcToLocal(utcMs: Long): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMs }
        return Calendar.getInstance().apply {
            clear()
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    private fun localToUtc(localMs: Long): Long {
        val local = Calendar.getInstance().apply { timeInMillis = localMs }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }
}

/** A small bar chart drawn directly on a canvas, so no chart library is needed. */
class BarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var data: List<Pair<String, Float>> = emptyList()
    private val density = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.fnr_primary) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11 * resources.displayMetrics.scaledDensity
        color = ContextCompat.getColor(context, R.color.fnr_muted)
        textAlign = Paint.Align.CENTER
    }

    fun setData(values: List<Pair<String, Float>>) {
        data = values
        contentDescription = values.joinToString { "${it.first}: ${"%.1f".format(it.second)}" }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return
        val bottom = height - 20 * density
        val top = 18 * density
        val max = data.maxOf { it.second }.coerceAtLeast(1f)
        val slot = width / data.size.toFloat()
        data.forEachIndexed { i, (name, value) ->
            val h = (bottom - top) * value / max
            val left = i * slot + slot * 0.2f
            val right = i * slot + slot * 0.8f
            canvas.drawRoundRect(left, bottom - h, right, bottom, 4 * density, 4 * density, bar)
            val cx = i * slot + slot / 2
            canvas.drawText(name.take(8), cx, height - 4 * density, label)
            canvas.drawText("%.1f".format(value), cx, bottom - h - 4 * density, label)
        }
    }
}
