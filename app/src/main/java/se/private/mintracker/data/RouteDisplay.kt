package se.oxelv.mintracker.data

import se.oxelv.mintracker.tracking.TrackPoint
import kotlin.math.abs
import kotlin.math.max

data class DisplayCoordinate(val latitude: Double, val longitude: Double)
data class RouteDisplay(
    val coordinates: List<DisplayCoordinate>,
    val minLatitude: Double, val maxLatitude: Double,
    val minLongitude: Double, val maxLongitude: Double,
    val originalCount: Int
)

/** Display-only simplification. Room, sync, splits and exports retain original GPS points. */
object RouteDisplayBuilder {
    fun build(points: List<TrackPoint>, maxPoints: Int = 2500): RouteDisplay? {
        if (points.isEmpty()) return null
        val input = points.map { DisplayCoordinate(it.latitude, it.longitude) }
        val minLat = input.minOf { it.latitude }; val maxLat = input.maxOf { it.latitude }
        val minLon = input.minOf { it.longitude }; val maxLon = input.maxOf { it.longitude }
        val span = max(maxLat - minLat, maxLon - minLon)
        var tolerance = max(span / 1200.0, 0.000001)
        var display = simplify(input, tolerance)
        repeat(32) {
            if (display.size <= maxPoints) return@repeat
            tolerance *= 1.8
            display = simplify(input, tolerance)
        }
        return RouteDisplay(display, minLat, maxLat, minLon, maxLon, input.size)
    }

    private fun simplify(input: List<DisplayCoordinate>, tolerance: Double): List<DisplayCoordinate> {
        if (input.size <= 2) return input
        val kept = BooleanArray(input.size)
        kept[0] = true; kept[input.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to input.lastIndex)
        val squaredLimit = tolerance * tolerance
        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            val a = input[start]; val b = input[end]
            val dx = b.longitude - a.longitude; val dy = b.latitude - a.latitude
            val lengthSquared = dx * dx + dy * dy
            var largest = squaredLimit
            var furthest = -1
            for (index in start + 1 until end) {
                val point = input[index]
                val fraction = if (lengthSquared == 0.0) 0.0 else
                    (((point.longitude - a.longitude) * dx + (point.latitude - a.latitude) * dy) / lengthSquared).coerceIn(0.0, 1.0)
                val diffX = point.longitude - (a.longitude + dx * fraction)
                val diffY = point.latitude - (a.latitude + dy * fraction)
                val distanceSquared = diffX * diffX + diffY * diffY
                if (distanceSquared > largest) { largest = distanceSquared; furthest = index }
            }
            if (furthest >= 0) {
                kept[furthest] = true
                stack.addLast(start to furthest)
                stack.addLast(furthest to end)
            }
        }
        return input.filterIndexed { index, _ -> kept[index] }
    }
}
