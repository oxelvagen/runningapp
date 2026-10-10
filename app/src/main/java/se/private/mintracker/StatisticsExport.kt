package se.oxelv.mintracker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import se.oxelv.mintracker.data.*
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class StatisticsExportKind(val label: String) { IMAGE("Dela statistikbild"), PDF("Skapa PDF-rapport"), EXCEL("Exportera Excel") }
data class StatisticsExportOptions(
    val summary: Boolean = true, val distanceChart: Boolean = true, val paceChart: Boolean = true,
    val weekComparison: Boolean = true, val records: Boolean = true, val activityTable: Boolean = true,
    val dogStats: Boolean = false, val notes: Boolean = false,
    val averageDistance: Boolean = false, val climb: Boolean = false
)

/** Canvas rendering shares Statistics.calculate's data with the on-screen charts. */
object StatisticsExport {
    private val navy = Color.rgb(6, 42, 67)
    private val blue = Color.rgb(22, 138, 251)
    private val ink = Color.rgb(53, 70, 87)
    private val sv = Locale("sv", "SE")
    private val date = DateTimeFormatter.ofPattern("d MMM yyyy", sv)
    private fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(date)
    private fun km(m: Double) = "%.1f km".format(sv, m / 1000)
    private fun duration(ms: Long) = "${ms / 3_600_000} h ${(ms / 60_000) % 60} min"
    private fun periodRange(period: StatisticsPeriod, stats: StatisticsResult): String {
        val today = LocalDate.now()
        val start = when (period) {
            StatisticsPeriod.SEVEN_DAYS -> today.minusDays(6)
            StatisticsPeriod.THIRTY_DAYS -> today.minusDays(29)
            StatisticsPeriod.THREE_MONTHS -> today.minusMonths(3).plusDays(1)
            StatisticsPeriod.YEAR -> today.withDayOfYear(1)
            StatisticsPeriod.ALL -> stats.activities.minOfOrNull { Instant.ofEpochMilli(it.startedAt).atZone(ZoneId.systemDefault()).toLocalDate() } ?: today
        }
        return "${start.format(date)} – ${today.format(date)}"
    }

    fun image(stats: StatisticsResult, period: StatisticsPeriod, options: StatisticsExportOptions): ByteArray {
        val extra = (if (options.distanceChart) 250 else 0) + (if (options.paceChart) 250 else 0) +
            (if (options.summary) 220 else 0) + (if (options.weekComparison) 100 else 0) +
            (if (options.records) 100 else 0) + (if (options.dogStats) 120 else 0) +
            (if (options.activityTable) 60 else 0) + (if (options.notes) 60 else 0) +
            (if (options.averageDistance) 45 else 0) + (if (options.climb) 45 else 0)
        val bitmap = Bitmap.createBitmap(1080, maxOf(1350, 330 + extra), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(243, 247, 253))
            val draw = Page(canvas, 1080f)
            draw.title("MIN TRACKER", "MIN TRÄNING · ${period.label.uppercase(sv)}")
            draw.line(periodRange(period, stats), small = true)
            draw.sections(stats, options, compact = true)
            return ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                out.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    fun pdf(stats: StatisticsResult, period: StatisticsPeriod, options: StatisticsExportOptions, dogs: List<DogEntity>): ByteArray {
        val doc = PdfDocument()
        try {
            var pageNumber = 0
            fun newPage(): Pair<PdfDocument.Page, Page> {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNumber).create())
                page.canvas.drawColor(Color.WHITE)
                val draw = Page(page.canvas, 595f)
                draw.title("MIN TRACKER", "Träningsrapport · ${period.label}")
                draw.line(periodRange(period, stats), small = true)
                return page to draw
            }
            var (page, draw) = newPage()
            draw.sections(stats, options.copy(activityTable = false, dogStats = false, notes = false), compact = false)
            doc.finishPage(page)
            if (options.activityTable) {
                val rows = stats.activities.sortedByDescending { it.startedAt }
                var index = 0
                do {
                    val next = newPage(); page = next.first; draw = next.second
                    draw.line("AKTIVITETER", strong = true)
                    repeat(16) {
                        if (index >= rows.size) return@repeat
                        val a = rows[index++]
                        val dog = dogs.firstOrNull { it.id == a.dogId }?.name.orEmpty()
                        draw.line("${day(a.startedAt)}  ·  ${ActivityKind.label(a.kind)}  ·  ${km(a.distanceM)}  ·  ${duration(a.activeMs)}", small = true)
                        draw.line("Tempo ${Statistics.paceText(if (a.distanceM >= 100) a.activeMs / a.distanceM else null)}    Stigning ${a.ascentM?.toInt() ?: "–"} m    ${dog.take(24)}", small = true)
                        draw.gap(12)
                    }
                    doc.finishPage(page)
                } while (index < rows.size)
            }
            if (options.dogStats) {
                val next = newPage(); page = next.first; draw = next.second
                draw.line("HUNDSTATISTIK", strong = true)
                stats.dogs.forEach { draw.line("${it.dog.name}: ${km(it.distanceMeters)}, ${it.count} pass (${it.runCount} löpning, ${it.walkCount} promenad)") }
                doc.finishPage(page)
            }
            if (options.notes) {
                val notes = stats.activities.filter { !it.note.isNullOrBlank() }
                var index = 0
                do {
                    val next = newPage(); page = next.first; draw = next.second
                    draw.line("ANTECKNINGAR", strong = true)
                    repeat(18) {
                        if (index >= notes.size) return@repeat
                        val note = notes[index++]
                        draw.line("${day(note.startedAt)}: ${note.note!!.replace('\n', ' ').take(75)}", small = true)
                    }
                    doc.finishPage(page)
                } while (index < notes.size)
            }
            return ByteArrayOutputStream().use { out -> doc.writeTo(out); out.toByteArray() }
        } finally { doc.close() }
    }

    private class Page(val canvas: Canvas, val width: Float) {
        val scale = width / 1080f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var y = 85f
        fun text(s: String, x: Float = 64f, size: Float = 30f, color: Int = ink, bold: Boolean = false) {
            paint.color = color; paint.textSize = size * scale
            paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            val max = (1080f - x - 58f) * scale
            if (paint.measureText(s) > max) paint.textSize *= (max / paint.measureText(s)).coerceAtLeast(.5f)
            canvas.drawText(s, x * scale, y * scale, paint)
        }
        fun title(brand: String, subtitle: String) { text(brand, size = 27f, color = blue, bold = true); y += 76; text(subtitle, size = 45f, color = navy, bold = true); y += 95 }
        fun line(value: String, strong: Boolean = false, small: Boolean = false) { text(value, size = if (small) 21f else if (strong) 32f else 27f, color = if (strong) navy else ink, bold = strong); y += if (small) 27 else 45 }
        fun gap(amount: Int) { y += amount }
        fun sections(s: StatisticsResult, o: StatisticsExportOptions, compact: Boolean) {
            if (o.summary) {
                line("ÖVERSIKT", true); line("${km(s.totals.distanceMeters)}  ·  ${s.totals.count} pass")
                line("${duration(s.totals.durationMillis)}  ·  ${Statistics.paceText(s.paceSecondsPerKm)}")
                if (o.averageDistance) s.averageDistanceMeters?.let { line("Distans/pass ${km(it)}") }
                if (o.climb) s.elevationGainMeters?.let { line("Stigning ${it.toInt()} m") }
                y += 20
            }
            if (o.distanceChart) chart("DISTANS ÖVER TID", s.buckets.map { it.distanceMeters }, s.buckets.map { it.label }, false)
            if (o.paceChart) chart("TEMPOUTVECKLING", s.buckets.map { if (it.distanceMeters >= 100) it.durationMs / it.distanceMeters else 0.0 }, s.buckets.map { it.label }, true)
            if (o.weekComparison) { line("DEN HÄR VECKAN", true); line("${km(s.thisWeek.distanceMeters)} mot ${km(s.lastWeek.distanceMeters)} förra veckan") }
            if (o.records) { line("INOM VALD PERIOD", true); line("Längsta pass: ${s.longest?.let { km(it.distanceM) } ?: "–"}") }
            if (compact && o.dogStats) s.dogs.take(2).forEach { line("${it.dog.name}: ${km(it.distanceMeters)} · ${it.count} pass") }
            if (compact && o.activityTable) line("${s.activities.size} ${if (s.activities.size == 1) "aktivitet" else "aktiviteter"} under perioden")
            if (compact && o.notes) s.activities.firstOrNull { !it.note.isNullOrBlank() }?.note?.let { line(it.replace('\n', ' ').take(48)) }
        }
        private fun chart(title: String, values: List<Double>, labels: List<String>, pace: Boolean) {
            line(title, true)
            val top = y + 8
            val bottom = top + 145
            val max = values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
            val count = values.size.coerceAtLeast(1)
            paint.color = blue; paint.style = Paint.Style.FILL
            values.forEachIndexed { index, value ->
                val x = 64f + index * 950f / count
                val h = (value / max * 135).toFloat().coerceAtLeast(2f)
                if (!pace || value > 0) canvas.drawRect(x * scale, (bottom - h) * scale, (x + (750f / count).coerceAtMost(45f)) * scale, bottom * scale, paint)
            }
            y = bottom + 36
            line("${labels.firstOrNull().orEmpty()} – ${labels.lastOrNull().orEmpty()}", small = true)
            y += 42
        }
    }
}
