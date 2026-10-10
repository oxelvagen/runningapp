package se.oxelv.mintracker

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import se.oxelv.mintracker.data.ActivityEntity
import se.oxelv.mintracker.data.ActivityKind
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

enum class ShareFormat(val label: String, val width: Int, val height: Int) {
    STORY("Story 9:16", 1080, 1920),
    SQUARE("Kvadrat 1:1", 1080, 1080),
    POST("Inlägg 4:5", 1080, 1350)
}
data class ShareOptions(
    val map: Boolean = true, val distance: Boolean = true, val duration: Boolean = true,
    val pace: Boolean = true, val speed: Boolean = false, val climb: Boolean = false,
    val steps: Boolean = false, val dog: Boolean = false, val note: Boolean = false
)
data class ShareImageText(val title: String, val distance: String, val duration: String, val pace: String, val date: String)

object ShareImage {
    private val swedish = Locale("sv", "SE")
    private val navy = 0xFF062A43.toInt()
    private val blue = 0xFF168AFB.toInt()
    private val background = 0xFFF3F7FD.toInt()

    fun mapSize(activity: ActivityEntity, format: ShareFormat, options: ShareOptions): Pair<Int, Int> {
        val metricCount = listOf(
            options.duration, options.pace, options.speed && activity.activeMs > 0,
            options.climb && activity.ascentM != null, options.steps && activity.steps != null
        ).count { it }
        val metricY = (if (options.distance) 430 else 318) + ((metricCount + 1) / 2) * if (format == ShareFormat.SQUARE) 82 else 112
        val footerTop = format.height - if (options.note && !activity.note.isNullOrBlank()) 190 else 132
        val top = metricY + 16
        val bottom = footerTop - 28
        return 896 to (bottom - top)
    }

    fun text(activity: ActivityEntity, zone: ZoneId = ZoneId.systemDefault()): ShareImageText {
        val km = NumberFormat.getNumberInstance(swedish).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
        val seconds = activity.activeMs / 1000
        val paceSeconds = if (activity.distanceM >= 100 && activity.activeMs > 0) (activity.activeMs / activity.distanceM).roundToLong() else null
        return ShareImageText(
            ActivityKind.label(activity.kind).uppercase(swedish),
            "${km.format(activity.distanceM / 1000)} KM",
            if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%02d:%02d".format(seconds / 60, seconds % 60),
            paceSeconds?.let { "%d:%02d /KM".format(it / 60, it % 60) } ?: "– /KM",
            Instant.ofEpochMilli(activity.startedAt).atZone(zone).format(DateTimeFormatter.ofPattern("d MMMM yyyy", swedish))
        )
    }

    /** Layout is composed directly at the target resolution; it is not a UI screenshot. */
    fun render(activity: ActivityEntity, format: ShareFormat, options: ShareOptions, dogName: String?, mapBitmap: Bitmap?): Bitmap {
        val bitmap = Bitmap.createBitmap(format.width, format.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float, color: Int) {
            paint.color = color; paint.style = Paint.Style.FILL
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
        }
        fun label(value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, maxWidth: Float = 880f) {
            paint.color = color; paint.textSize = size
            paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            val width = paint.measureText(value)
            if (width > maxWidth) paint.textSize = (size * maxWidth / width).coerceAtLeast(24f)
            canvas.drawText(value, x, y, paint)
        }
        val height = format.height.toFloat()
        roundRect(44f, 44f, 1036f, height - 44f, 42f, Color.WHITE)
        label("MIN TRACKER", 92f, 135f, 40f, blue, true)
        val values = text(activity)
        val title = if (options.dog && dogName != null) "${ActivityKind.label(activity.kind)} med $dogName" else ActivityKind.label(activity.kind)
        label(title.uppercase(swedish), 92f, 218f, 52f, navy, true)
        if (options.distance) label(values.distance, 92f, 358f, 100f, navy, true)
        var metricY = if (options.distance) 430f else 318f
        val metrics = buildList {
            if (options.duration) add("TID" to values.duration)
            if (options.pace) add("TEMPO" to values.pace)
            if (options.speed && activity.activeMs > 0) add("HASTIGHET" to "%.1f km/h".format(swedish, activity.distanceM / activity.activeMs * 3600))
            if (options.climb && activity.ascentM != null) add("STIGNING" to "${activity.ascentM.toInt()} m")
            if (options.steps && activity.steps != null) add("STEG" to activity.steps.toString())
        }
        metrics.chunked(2).forEach { row ->
            row.forEachIndexed { index, (heading, value) ->
                val x = if (index == 0) 92f else 540f
                label(value, x, metricY, 55f, navy, true, 430f)
                label(heading, x, metricY + 38f, 28f, Color.rgb(96, 114, 133))
            }
            metricY += if (format == ShareFormat.SQUARE) 82f else 112f
        }
        val footerTop = height - if (options.note && !activity.note.isNullOrBlank()) 190f else 132f
        if (options.map && mapBitmap != null) {
            val mapTop = metricY + 16f
            val mapBottom = footerTop - 28f
            val dst = RectF(92f, mapTop, 988f, mapBottom)
            canvas.save()
            val clip = android.graphics.Path().apply { addRoundRect(dst, 26f, 26f, android.graphics.Path.Direction.CW) }
            canvas.clipPath(clip)
            paint.color = Color.WHITE
            canvas.drawBitmap(mapBitmap, null, dst, paint)
            canvas.restore()
        } else {
            roundRect(92f, metricY + 12f, 988f, footerTop - 24f, 28f, Color.rgb(228, 241, 255))
            label("DIN TRÄNING. DIN VÄG.", 145f, metricY + 110f, 49f, blue, true)
        }
        label(values.date, 92f, height - 92f, 40f, navy)
        if (options.note && !activity.note.isNullOrBlank()) {
            label(activity.note.replace("\n", " ").take(85), 92f, height - 145f, 30f, Color.rgb(96, 114, 133))
        }
        return bitmap
    }

    fun png(bitmap: Bitmap): ByteArray = java.io.ByteArrayOutputStream().use { stream ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        stream.toByteArray()
    }

    fun shareIntent(context: Context, bytes: ByteArray, filename: String = "min-tracker-pass.png", title: String = "Dela pass"): Intent {
        val directory = File(context.cacheDir, "shares").apply { mkdirs() }
        val file = File(directory, filename)
        FileOutputStream(file).use { it.write(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title)
    }

    fun saveModern(context: Context, bytes: ByteArray): Uri {
        require(Build.VERSION.SDK_INT >= 29)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "min-tracker-pass-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Min Tracker")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Bilden kunde inte skapas.")
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Bilden kunde inte skrivas.")
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            return uri
        } catch (error: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }
}
