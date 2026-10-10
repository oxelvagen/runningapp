package se.oxelv.mintracker

import android.content.Intent
import android.os.Build
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import se.oxelv.mintracker.data.ActivityEntity
import se.oxelv.mintracker.tracking.TrackPoint
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class StatisticsVisualTest {
    @Test fun statisticsWithDataRenders() = renderPreview(true, "statistics-data.png", scroll = true)
    @Test fun statisticsWithoutDataRenders() = renderPreview(false, "statistics-empty.png")
    @Test fun passDetailRenders() = renderPreview(false, "pass-detail.png", detail = true, scroll = true)
    @Test fun longerPeriodsAndFiltersRender() {
        renderPreview(true, "statistics-30-days.png", period = 1)
        renderPreview(true, "statistics-year.png", period = 3)
        renderPreview(true, "statistics-run.png", kind = "RUN")
        renderPreview(true, "statistics-dog-run.png", kind = "DOG_RUN")
        renderPreview(true, "statistics-frasse.png", kind = "DOG_RUN", dogId = "frasse")
    }

    private fun renderPreview(withData: Boolean, name: String, period: Int = 0, kind: String? = null, dogId: String? = null, detail: Boolean = false, scroll: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, StatisticsPreviewActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("withData", withData)
            .putExtra("period", period)
            .putExtra("kind", kind)
            .putExtra("dogId", dogId)
            .putExtra("detail", detail)
        val activity = instrumentation.startActivitySync(intent)
        try {
            instrumentation.waitForIdleSync()
            val root = activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            instrumentation.runOnMainSync { root.draw(Canvas(bitmap)) }
            assertTrue(bitmap.width > 300)
            assertTrue(bitmap.height > 500)
            saveScreenshot(name, bitmap)
            bitmap.recycle()
            if (scroll) {
                val start = SystemClock.uptimeMillis()
                val x = root.width / 2f
                val top = root.height * .8f
                val bottom = root.height * .2f
                for (step in 0..8) {
                    val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 8 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                    val event = MotionEvent.obtain(start, start + step * 25, action, x, top + (bottom - top) * step / 8f, 0)
                    instrumentation.sendPointerSync(event)
                    event.recycle()
                }
                instrumentation.waitForIdleSync()
                val lower = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                instrumentation.runOnMainSync { root.draw(Canvas(lower)) }
                saveScreenshot(name.removeSuffix(".png") + "-lower.png", lower)
                lower.recycle()
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun shareImageIsOfflinePngAndHasRoute() {
        val activity = ActivityEntity(kind = "RUN", startedAt = System.currentTimeMillis(),
            activeMs = 29 * 60_000, distanceM = 5200.0, userId = "preview")
        val bitmap = ShareImage.render(activity, ShareFormat.POST, ShareOptions(map = false), null, null)
        try {
            assertEquals(1080, bitmap.width)
            assertEquals(1350, bitmap.height)
            val bytes = ShareImage.png(bitmap)
            assertTrue(bytes.size > 1000)
            assertEquals(0x89, bytes[0].toInt() and 0xff)
            assertEquals(0x50, bytes[1].toInt() and 0xff)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val share = ShareImage.shareIntent(context, bytes)
            val send = share.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            assertEquals("image/png", send?.type)
            val sharedUri = send?.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            assertEquals("content", sharedUri?.scheme)
            assertTrue(context.contentResolver.openInputStream(sharedUri!!)?.use { it.read() == 0x89 } == true)
            if (Build.VERSION.SDK_INT >= 29) {
                val savedUri = ShareImage.saveModern(context, bytes)
                try { assertTrue(context.contentResolver.openInputStream(savedUri)?.use { it.read() == 0x89 } == true) }
                finally { context.contentResolver.delete(savedUri, null, null) }
            }
            saveScreenshot("share-image.png", bitmap)
        } finally { bitmap.recycle() }
    }

    @Test fun exportFormatsAndWorkbookStructure() {
        val activity = ActivityEntity(kind = "DOG_RUN", startedAt = System.currentTimeMillis(), activeMs = 1_740_000,
            distanceM = 5_200.0, ascentM = 42.0, steps = 6200, userId = "preview", note = "Löpning med åäö")
        ShareFormat.entries.forEach { format ->
            val bitmap = ShareImage.render(activity, format, ShareOptions(map = false, note = true, steps = true), "Frasse", null)
            try { assertEquals(format.width, bitmap.width); assertEquals(format.height, bitmap.height) }
            finally { bitmap.recycle() }
        }
        val stats = se.oxelv.mintracker.data.Statistics.calculate(listOf(activity), emptyList(),
            se.oxelv.mintracker.data.StatisticsPeriod.SEVEN_DAYS)
        val options = StatisticsExportOptions()
        val png = StatisticsExport.image(stats, se.oxelv.mintracker.data.StatisticsPeriod.SEVEN_DAYS, options)
        assertEquals(0x89, png[0].toInt() and 0xff)
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "statistics-image.png").writeBytes(png)
        val pdf = StatisticsExport.pdf(stats, se.oxelv.mintracker.data.StatisticsPeriod.SEVEN_DAYS, options, emptyList())
        assertTrue(String(pdf.copyOfRange(0, 4)) == "%PDF")
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "statistics-report.pdf").writeBytes(pdf)
        val workbook = StatisticsXlsx.write(stats, se.oxelv.mintracker.data.StatisticsPeriod.SEVEN_DAYS,
            se.oxelv.mintracker.data.StatisticsFilter(), emptyList(), emptyMap())
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "statistics-workbook.xlsx").writeBytes(workbook)
        val entries = mutableMapOf<String, String>()
        java.util.zip.ZipInputStream(workbook.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(5, entries.keys.count { it.startsWith("xl/worksheets/sheet") })
        assertTrue(entries["xl/workbook.xml"]!!.contains("Perioddata"))
        assertTrue(entries["xl/worksheets/sheet2.xml"]!!.contains("Löpning med åäö"))
        assertTrue(entries["xl/worksheets/sheet2.xml"]!!.contains("<autoFilter"))
    }

    @Test fun longPdfHasPageBreaks() {
        val source = (0 until 55).map { index -> ActivityEntity(id = index + 1L, kind = "RUN",
            startedAt = System.currentTimeMillis() - index * 86_400_000L, activeMs = 2_400_000,
            distanceM = 6_000.0, userId = "preview", note = "Pass $index") }
        val stats = se.oxelv.mintracker.data.Statistics.calculate(source, emptyList(),
            se.oxelv.mintracker.data.StatisticsPeriod.ALL)
        val pdf = StatisticsExport.pdf(stats, se.oxelv.mintracker.data.StatisticsPeriod.ALL,
            StatisticsExportOptions(notes = true), emptyList())
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "long-report.pdf")
        file.writeBytes(pdf)
        val descriptor = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        android.graphics.pdf.PdfRenderer(descriptor).use { renderer -> assertTrue(renderer.pageCount >= 7) }
    }

    @Test fun mapSnapshotRendersRealTilesAndRoute() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val points = listOf(TrackPoint(59.0, 18.0, 0, 5f, null), TrackPoint(59.01, 18.01, 1, 5f, null))
        val bitmap = MapExportRenderer.capture(context, points, 896, 500)
        try {
            assertEquals(896, bitmap.width)
            assertEquals(500, bitmap.height)
            saveScreenshot("map-snapshot.png", bitmap)
            val activity = ActivityEntity(kind = "RUN", startedAt = System.currentTimeMillis(),
                activeMs = 1_740_000, distanceM = 5_200.0, userId = "preview")
            val share = ShareImage.render(activity, ShareFormat.POST, ShareOptions(), null, bitmap)
            try { saveScreenshot("share-with-map.png", share) } finally { share.recycle() }
        } finally { bitmap.recycle() }
    }

    @Test fun savedMapCanPanWithoutCameraReset() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, StatisticsPreviewActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("detail", true)
        val activity = instrumentation.startActivitySync(intent)
        try {
            instrumentation.waitForIdleSync()
            fun findMap(view: View): org.maplibre.android.maps.MapView? {
                if (view is org.maplibre.android.maps.MapView) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) findMap(view.getChildAt(i))?.let { return it }
                return null
            }
            val view = findMap(activity.window.decorView) ?: error("Kartan saknas")
            val latch = java.util.concurrent.CountDownLatch(1)
            var map: org.maplibre.android.maps.MapLibreMap? = null
            instrumentation.runOnMainSync { view.getMapAsync { map = it; latch.countDown() } }
            assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(800)
            val before = map!!.cameraPosition.target!!
            val location = IntArray(2)
            instrumentation.runOnMainSync { view.getLocationOnScreen(location) }
            val x = location[0] + view.width * .45f
            val fromY = location[1] + view.height * .65f
            val toY = location[1] + view.height * .30f
            val start = SystemClock.uptimeMillis()
            for (step in 0..12) {
                val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val event = MotionEvent.obtain(start, start + step * 35, action, x, fromY + (toY - fromY) * step / 12f, 0)
                instrumentation.sendPointerSync(event); event.recycle()
            }
            Thread.sleep(900)
            val after = map!!.cameraPosition.target!!
            assertTrue("Pan ska flytta kartans kamera", kotlin.math.abs(before.latitude - after.latitude) > .00001 || kotlin.math.abs(before.longitude - after.longitude) > .00001)
            Thread.sleep(700)
            assertTrue("Kameran ska inte återställas", kotlin.math.abs(map!!.cameraPosition.target!!.latitude - after.latitude) < .0005)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun saveScreenshot(name: String, bitmap: Bitmap) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        FileOutputStream(File(context.cacheDir, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

