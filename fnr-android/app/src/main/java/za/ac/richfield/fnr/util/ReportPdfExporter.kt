package za.ac.richfield.fnr.util

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import za.ac.richfield.fnr.data.repository.ReportData
import java.io.OutputStream

/** Writes a report as an A4 PDF: header, summary, optional bar chart, table, page numbers (FR-RPT-02). */
object ReportPdfExporter {
    private const val W = 595
    private const val H = 842
    private const val M = 40f
    private const val ROW_H = 18f

    fun write(data: ReportData, preparedFor: String, out: OutputStream) {
        val doc = PdfDocument()
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.BLACK }
        val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
        val grey = Paint(text).apply { color = Color.DKGRAY; textSize = 9f }
        val fill = Paint().apply { color = Color.rgb(232, 234, 246) }
        val bar = Paint().apply { color = Color.rgb(63, 81, 181) }
        val line = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f }

        var pageNo = 0
        lateinit var page: PdfDocument.Page
        lateinit var c: Canvas
        var y = 0f
        val colW = (W - 2 * M) / data.columns.size

        fun footer() {
            c.drawText("Flash Note Recorder · ${data.type.name} ${data.type.title}", M, H - 24f, grey)
            c.drawText("Page $pageNo", W - M - 40f, H - 24f, grey)
        }

        fun tableHeader() {
            c.drawRect(M, y - 12f, W - M, y + 6f, fill)
            data.columns.forEachIndexed { i, col -> c.drawText(fit(col, bold, colW - 6), M + i * colW + 3, y, bold) }
            y += ROW_H
        }

        fun newPage(withHeader: Boolean) {
            if (pageNo > 0) { footer(); doc.finishPage(page) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            c = page.canvas
            y = M + 10f
            if (withHeader) tableHeader()
        }

        newPage(false)
        c.drawText("${data.type.name} ${data.type.title}", M, y, title); y += 20f
        c.drawText("Prepared for $preparedFor · Period: ${data.periodLabel} · Generated ${Format.dateTime(System.currentTimeMillis())}", M, y, grey)
        y += 22f
        data.summary.forEach { (k, v) -> c.drawText("$k: ", M, y, bold); c.drawText(v, M + 140f, y, text); y += 15f }
        y += 10f

        if (data.chart.isNotEmpty()) {
            c.drawText(data.chartTitle, M, y, bold); y += 10f
            val chartH = 110f
            val max = data.chart.maxOf { it.second }.coerceAtLeast(1f)
            val slot = (W - 2 * M) / data.chart.size
            data.chart.forEachIndexed { i, (label, value) ->
                val h = value / max * chartH
                val x = M + i * slot + slot * 0.2f
                c.drawRect(x, y + chartH - h, x + slot * 0.6f, y + chartH, bar)
                c.drawText(fit(label, grey, slot - 2), M + i * slot + 2, y + chartH + 12f, grey)
                c.drawText(String.format(java.util.Locale.getDefault(), "%.1f", value), x, y + chartH - h - 3f, grey)
            }
            y += chartH + 30f
        }

        if (data.rows.isEmpty()) {
            c.drawText("No data for this period.", M, y, text)
        } else {
            y += 6f
            tableHeader()
            data.rows.forEach { row ->
                if (y > H - 60f) newPage(true)
                row.forEachIndexed { i, cell -> c.drawText(fit(cell, text, colW - 6), M + i * colW + 3, y, text) }
                c.drawLine(M, y + 5f, W - M, y + 5f, line)
                y += ROW_H
            }
        }
        footer()
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }

    private fun fit(value: String, paint: Paint, width: Float): String {
        if (paint.measureText(value) <= width) return value
        var s = value
        while (s.isNotEmpty() && paint.measureText("$s…") > width) s = s.dropLast(1)
        return "$s…"
    }
}
