package se.oxelv.mintracker

import org.junit.Assert.*
import org.junit.Test
import se.oxelv.mintracker.data.RouteDisplayBuilder
import se.oxelv.mintracker.tracking.TrackPoint
import kotlin.math.sin

class RouteDisplayTest {
    @Test fun savedRoutesPreserveEndpointsAndBoundsAtTypicalSizes() {
        listOf(100, 1_000, 5_000, 10_000).forEach { count ->
            val points = (0 until count).map { index ->
                TrackPoint(59.0 + index * .000001 + sin(index / 17.0) * .00003,
                    18.0 + index * .000001, index.toLong(), 5f, null)
            }
            val display = RouteDisplayBuilder.build(points)!!
            assertEquals(count, display.originalCount)
            assertTrue(display.coordinates.size <= 2500)
            assertEquals(points.first().latitude, display.coordinates.first().latitude, 0.0)
            assertEquals(points.last().longitude, display.coordinates.last().longitude, 0.0)
            assertEquals(points.minOf { it.latitude }, display.minLatitude, 0.0)
            assertEquals(points.maxOf { it.longitude }, display.maxLongitude, 0.0)
        }
    }
}
