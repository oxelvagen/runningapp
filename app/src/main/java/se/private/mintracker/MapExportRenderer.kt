package se.oxelv.mintracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.snapshotter.MapSnapshotter
import se.oxelv.mintracker.data.RouteDisplay
import se.oxelv.mintracker.data.RouteDisplayBuilder
import se.oxelv.mintracker.tracking.TrackPoint
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/** Creates an export-sized offscreen map. No interactive MapView or UI screenshot is used. */
object MapExportRenderer {
    suspend fun capture(context: Context, points: List<TrackPoint>, width: Int, height: Int): Bitmap {
        val route = withContext(Dispatchers.Default) { RouteDisplayBuilder.build(points) }
            ?: error("Det här passet har ingen sparad GPS-rutt.")
        require(width in 100..1600 && height in 100..1600)
        return withContext(Dispatchers.Main) {
            org.maplibre.android.MapLibre.getInstance(context.applicationContext)
            withTimeout(25_000) {
                suspendCancellableCoroutine { continuation ->
                    val bounds = paddedBounds(route)
                    val snapshotter = MapSnapshotter(context.applicationContext,
                        MapSnapshotter.Options(width, height)
                            .withStyle("https://tiles.openfreemap.org/styles/liberty")
                            .withRegion(bounds))
                    continuation.invokeOnCancellation {
                        android.os.Handler(android.os.Looper.getMainLooper()).post { snapshotter.cancel() }
                    }
                    snapshotter.start({ snapshot ->
                        try {
                            val bitmap = snapshot.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                                ?: error("Kartbilden kunde inte kopieras.")
                            val path = Path()
                            se.oxelv.mintracker.tracking.routeSegments(points).forEach { part ->
                              RouteDisplayBuilder.build(part)?.coordinates.orEmpty().forEachIndexed { index, coordinate ->
                                val pixel = snapshot.pixelForLatLng(LatLng(coordinate.latitude, coordinate.longitude))
                                if (index == 0) path.moveTo(pixel.x, pixel.y) else path.lineTo(pixel.x, pixel.y)
                              }
                            }
                            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.rgb(22, 138, 251)
                                style = Paint.Style.STROKE
                                strokeWidth = max(7f, width / 105f)
                                strokeCap = Paint.Cap.ROUND
                                strokeJoin = Paint.Join.ROUND
                            }
                            Canvas(bitmap).drawPath(path, paint)
                            if (continuation.isActive) continuation.resume(bitmap) else bitmap.recycle()
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }, { message ->
                        if (continuation.isActive) continuation.resumeWithException(
                            IllegalStateException("Kartbilden kunde inte laddas. Kontrollera internet och försök igen."))
                    })
                }
            }
        }
    }

    private fun paddedBounds(route: RouteDisplay): LatLngBounds {
        val latitudePadding = max((route.maxLatitude - route.minLatitude) * .20, .001)
        val longitudePadding = max((route.maxLongitude - route.minLongitude) * .20, .001)
        return LatLngBounds.Builder()
            .include(LatLng(route.minLatitude - latitudePadding, route.minLongitude - longitudePadding))
            .include(LatLng(route.maxLatitude + latitudePadding, route.maxLongitude + longitudePadding))
            .build()
    }
}
