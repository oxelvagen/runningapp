package se.oxelv.mintracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import se.oxelv.mintracker.data.ActivityEntity
import se.oxelv.mintracker.data.ActivityKind
import se.oxelv.mintracker.data.DogEntity
import se.oxelv.mintracker.data.StatisticsPeriod
import se.oxelv.mintracker.tracking.KmSplit
import se.oxelv.mintracker.tracking.TrackPoint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import java.time.LocalDate
import java.time.ZoneId

/** Internal debug-only screen for visual verification; never writes preview data to Room. */
class StatisticsPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val today = LocalDate.now()
        org.maplibre.android.MapLibre.getInstance(this)
        val zone = ZoneId.systemDefault()
        val withData = intent.getBooleanExtra("withData", false)
        val activities = if (withData) listOf(0L to 5200.0, 2L to 8100.0, 3L to 3400.0).mapIndexed { index, (daysAgo, distance) ->
            ActivityEntity(kind = if (index == 2) "DOG_RUN" else "RUN",
                startedAt = today.minusDays(daysAgo).atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                activeMs = (distance / 1000 * 6 * 60_000).toLong(), distanceM = distance,
                dogId = if (index == 2) "frasse" else null, userId = "preview")
        } else emptyList()
        val dogs = if (withData) listOf(DogEntity("frasse", "preview", "Frasse", createdAt = 1, updatedAt = 1, archivedAt = 1)) else emptyList()
        val period = StatisticsPeriod.entries.getOrElse(intent.getIntExtra("period", 0)) { StatisticsPeriod.SEVEN_DAYS }
        val kind = intent.getStringExtra("kind")?.let(ActivityKind::fromStored)
        val dogId = intent.getStringExtra("dogId")
        val detail = intent.getBooleanExtra("detail", false)
        setContent { TrackerTheme {
            Surface(Modifier.fillMaxSize(), color = AppColors.Background) {
                if (detail) {
                    val item = activities.firstOrNull() ?: ActivityEntity(kind = "RUN", startedAt = System.currentTimeMillis(), activeMs = 1_740_000, distanceM = 5200.0, userId = "preview", ascentM = 42.0, steps = 6200, note = "En fin runda.")
                    ActivityDetailScreen(item, null,
                        listOf(TrackPoint(59.0, 18.0, 0, 5f, null), TrackPoint(59.01, 18.01, 1, 5f, null)),
                        listOf(KmSplit(1, 340_000, null, null)), "Synkroniserad", null, false, null, false, null,
                        {}, {}, { _, _ -> }, { _, _ -> }, {})
                } else StatisticsScreen(activities, dogs, {}, initialPeriod = period, initialKind = kind, initialDogId = dogId)
            }
        } }
    }
}
