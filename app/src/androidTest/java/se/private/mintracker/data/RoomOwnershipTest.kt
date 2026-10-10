package se.oxelv.mintracker.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomOwnershipTest {
    private val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        TrackerDatabase::class.java
    ).build()

    @After fun close() = db.close()

    @Test fun pendingActivitiesStayWithTheirOwner() = runBlocking {
        val dao = db.dao()
        val aId = dao.insert(ActivityEntity(kind = "Löpning", startedAt = 1, activeMs = 1000, distanceM = 10.0, userId = "A"))
        val bId = dao.insert(ActivityEntity(kind = "Löpning", startedAt = 2, activeMs = 1000, distanceM = 20.0, userId = "B"))
        val legacyId = dao.insert(ActivityEntity(kind = "Löpning", startedAt = 3, activeMs = 1000, distanceM = 30.0))

        dao.claimLegacyActivities("A")
        dao.assignCloudId(aId, "B", "wrong-account")

        assertEquals(setOf(aId, legacyId), dao.pending("A").map { it.id }.toSet())
        assertEquals(listOf(bId), dao.pending("B").map { it.id })
        assertNull(dao.activity(aId)?.cloudId)
        assertEquals("B", dao.activity(bId)?.userId)
    }

    @Test fun restoredSplitsKeepOrderAndDoNotReplaceLocalCoordinates() = runBlocking {
        val dao = db.dao()
        val id = dao.insert(ActivityEntity(kind = "Löpning", startedAt = 1, activeMs = 700_000, distanceM = 2_000.0, userId = "A"))
        dao.insertSplits(listOf(SplitEntity(id, 1, 350_000, 59.3, 18.1)))
        dao.insertMissingSplits(listOf(
            SplitEntity(id, 1, 999_000, null, null),
            SplitEntity(id, 2, 355_000, null, null)
        ))
        val splits = dao.splits(id)
        assertEquals(listOf(1, 2), splits.map { it.kilometer })
        assertEquals(350_000, splits[0].durationMs)
        assertEquals(59.3, splits[0].latitude)
        assertNull(splits[1].latitude)
    }

    @Test fun incompleteDownloadIsNotUploadedAsPending() = runBlocking {
        val dao = db.dao()
        val id = dao.insert(ActivityEntity(kind = "Löpning", startedAt = 1, activeMs = 1000,
            distanceM = 10.0, userId = "A", cloudId = "remote-id", syncStatus = SyncStatus.RESTORING))
        assertEquals(emptyList<ActivityEntity>(), dao.pending("A"))
        dao.setSyncStatus(id, "A", SyncStatus.SYNCED)
        assertEquals(emptyList<ActivityEntity>(), dao.pending("A"))
    }

    @Test fun migrationKeepsOldSplitsAndQueuesPreviouslySyncedActivities() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "split-migration-test.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE ActivityEntity (id INTEGER NOT NULL PRIMARY KEY, syncStatus TEXT NOT NULL)")
                        db.execSQL("""CREATE TABLE SplitEntity (
                            activityId INTEGER NOT NULL, kilometer INTEGER NOT NULL, durationMs INTEGER NOT NULL,
                            latitude REAL NOT NULL, longitude REAL NOT NULL,
                            PRIMARY KEY(activityId, kilometer),
                            FOREIGN KEY(activityId) REFERENCES ActivityEntity(id) ON UPDATE NO ACTION ON DELETE CASCADE
                        )""".trimIndent())
                        db.execSQL("INSERT INTO ActivityEntity VALUES (1, 'SYNCED')")
                        db.execSQL("INSERT INTO SplitEntity VALUES (1, 1, 360000, 59.3, 18.1)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        )
        try {
            val sql = helper.writableDatabase
            MIGRATION_4_5.migrate(sql)
            sql.query("SELECT syncStatus FROM ActivityEntity WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals(SyncStatus.PENDING, cursor.getString(0))
            }
            sql.query("SELECT durationMs, latitude, longitude FROM SplitEntity WHERE activityId = 1 AND kilometer = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals(360_000L, cursor.getLong(0))
                assertEquals(59.3, cursor.getDouble(1), 0.0)
                assertEquals(18.1, cursor.getDouble(2), 0.0)
            }
            sql.execSQL("INSERT INTO SplitEntity VALUES (1, 2, 355000, NULL, NULL)")
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }
}
