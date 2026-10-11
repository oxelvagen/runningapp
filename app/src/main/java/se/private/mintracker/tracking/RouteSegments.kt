package se.oxelv.mintracker.tracking

/** Never draw a line through a recording interruption or a pause. */
fun routeSegments(points: List<TrackPoint>): List<List<TrackPoint>> {
    val segments = mutableListOf<MutableList<TrackPoint>>()
    points.forEachIndexed { index, point ->
        val previous = points.getOrNull(index - 1)
        if (previous == null || point.segment != previous.segment || point.timestamp - previous.timestamp > 30_000)
            segments += mutableListOf<TrackPoint>()
        segments.last() += point
    }
    return segments
}
