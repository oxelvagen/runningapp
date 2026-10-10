package se.oxelv.mintracker

import se.oxelv.mintracker.data.*
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Minimal OOXML writer: numeric/date cells remain typed, with no extra runtime dependency. */
object StatisticsXlsx {
    private sealed interface Cell { data class Text(val value: String): Cell; data class Number(val value: Double): Cell; data class Date(val epochMs: Long): Cell }
    private fun t(value: Any?): Cell = Cell.Text(value?.toString().orEmpty())
    private fun n(value: Number?): Cell = if (value == null) Cell.Text("") else Cell.Number(value.toDouble())
    private fun d(value: Long): Cell = Cell.Date(value)
    private data class Sheet(val name: String, val rows: List<List<Cell>>, val widths: List<Int>)
    private val sv = Locale("sv", "SE")

    fun write(stats: StatisticsResult, period: StatisticsPeriod, filter: StatisticsFilter, dogs: List<DogEntity>, splits: Map<Long, List<SplitEntity>>): ByteArray {
        val items = stats.activities.sortedBy { it.startedAt }
        val overview = Sheet("Översikt", listOf(
            listOf(t("Mått"), t("Värde")), listOf(t("Period"), t(period.label)),
            listOf(t("Filter"), t(filter.kind?.label ?: "Alla")),
            listOf(t("Hund"), t(dogs.firstOrNull { it.id == filter.dogId }?.name ?: "Alla")),
            listOf(t("Total distans km"), n(stats.totals.distanceMeters / 1000)),
            listOf(t("Antal pass"), n(stats.totals.count)),
            listOf(t("Total tid sekunder"), n(stats.totals.durationMillis / 1000.0)),
            listOf(t("Snittempo sek/km"), n(stats.paceSecondsPerKm)),
            listOf(t("Stigning m"), n(stats.elevationGainMeters)),
            listOf(t("Längsta pass km"), n(stats.longest?.distanceM?.div(1000)))
        ), listOf(27, 25))
        val activities = Sheet("Aktiviteter", listOf(listOf("Activity ID", "Datum", "Starttid", "Typ", "Hund", "Distans km", "Tid sekunder", "Tid formaterad", "Tempo sek/km", "Tempo min/km", "Hastighet km/h", "Stigning m", "Nedför m", "Steg", "Anteckning").map(::t)) + items.map { a ->
            val secs = a.activeMs / 1000.0
            val pace = if (a.distanceM >= 100 && a.activeMs > 0) a.activeMs / a.distanceM else null
            listOf(t(a.cloudId ?: "lokal-${a.id}"), d(a.startedAt), d(a.startedAt), t(ActivityKind.label(a.kind)), t(dogs.firstOrNull { it.id == a.dogId }?.name),
                n(a.distanceM / 1000), n(secs), t("%d:%02d:%02d".format((secs / 3600).toInt(), (secs / 60).toInt() % 60, secs.toInt() % 60)),
                n(pace), t(Statistics.paceText(pace)), n(if (a.activeMs > 0) a.distanceM / a.activeMs * 3600 else null),
                n(a.ascentM), n(a.descentM), n(a.steps), t(a.note))
        }, listOf(40, 16, 20, 24, 20, 16, 18, 20, 18, 20, 18, 16, 16, 12, 50))
        val splitRows = items.flatMap { a -> splits[a.id].orEmpty().map { split ->
            val distanceM = if (split.kilometer * 1000 <= a.distanceM) 1000.0 else (a.distanceM - (split.kilometer - 1) * 1000).coerceAtLeast(0.0)
            listOf(t(a.cloudId ?: "lokal-${a.id}"), d(a.startedAt), n(split.kilometer), n(distanceM / 1000), n(split.durationMs / 1000.0), n(if (distanceM > 0) split.durationMs / distanceM else null))
        } }
        val splitSheet = Sheet("Splits", listOf(listOf("Activity ID", "Datum", "Split", "Distans km", "Tid sekunder", "Tempo sek/km").map(::t)) + splitRows, listOf(40, 18, 12, 18, 20, 20))
        val dogSheet = Sheet("Hundar", listOf(listOf("Hund", "Antal pass", "Distans km", "Tid sekunder", "Löpning", "Promenad").map(::t)) + stats.dogs.map { dog ->
            listOf(t(dog.dog.name), n(dog.count), n(dog.distanceMeters / 1000), n(items.filter { it.dogId == dog.dog.id }.sumOf { it.activeMs } / 1000.0), n(dog.runCount), n(dog.walkCount))
        }, listOf(25, 16, 18, 20, 16, 16))
        val periodSheet = Sheet("Perioddata", listOf(listOf("Period", "Distans km", "Pass", "Tid sekunder", "Tempo sek/km").map(::t)) + stats.buckets.map { bucket ->
            listOf(t(bucket.label), n(bucket.distanceMeters / 1000), n(bucket.count), n(bucket.durationMs / 1000.0), n(if (bucket.distanceMeters >= 100) bucket.durationMs / bucket.distanceMeters else null))
        }, listOf(22, 18, 12, 20, 20))
        val sheets = listOf(overview, activities, splitSheet, dogSheet, periodSheet)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, content: String) { zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" }}</Types>""")
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            entry("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>${sheets.mapIndexed { i, s -> "<sheet name=\"${escape(s.name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("")}</sheets></workbook>""")
            entry("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" }}<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            entry("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF062A43"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0"/></cellStyleXfs><cellXfs count="4"><xf fontId="0" fillId="0" borderId="0" xfId="0"/><xf fontId="1" fillId="1" borderId="0" xfId="0" applyFill="1"/><xf fontId="0" fillId="0" borderId="0" xfId="0" numFmtId="14" applyNumberFormat="1"/><xf fontId="0" fillId="0" borderId="0" xfId="0" numFmtId="22" applyNumberFormat="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
            sheets.forEachIndexed { index, sheet -> entry("xl/worksheets/sheet${index + 1}.xml", xml(sheet)) }
        }
        return out.toByteArray()
    }

    private fun escape(s: String) = buildString { s.forEach { c -> append(when (c) { '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&apos;"; else -> c.toString() }) } }
    private fun col(index: Int): String { var n = index + 1; var s = ""; while (n > 0) { n--; s = ('A' + n % 26) + s; n /= 26 }; return s }
    private fun xml(sheet: Sheet): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>""")
        sheet.widths.forEachIndexed { i, w -> append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>") }
        append("</cols><sheetData>")
        sheet.rows.forEachIndexed { ri, row ->
            append("<row r=\"${ri + 1}\">")
            row.forEachIndexed { ci, cell ->
                val ref = "${col(ci)}${ri + 1}"
                when (cell) {
                    is Cell.Text -> append("<c r=\"$ref\" s=\"${if (ri == 0) 1 else 0}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(cell.value)}</t></is></c>")
                    is Cell.Number -> if (cell.value.isFinite()) append("<c r=\"$ref\"><v>${cell.value}</v></c>")
                    is Cell.Date -> {
                        val offsetMs = ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(cell.epochMs)).totalSeconds * 1000L
                        val days = ((cell.epochMs + offsetMs) / 86_400_000.0) + 25569.0
                        append("<c r=\"$ref\" s=\"${if (sheet.name == "Aktiviteter" && ci == 2) 3 else 2}\"><v>$days</v></c>")
                    }
                }
            }
            append("</row>")
        }
        append("</sheetData><autoFilter ref=\"A1:${col(sheet.widths.lastIndex)}${sheet.rows.size}\"/></worksheet>")
    }
}
