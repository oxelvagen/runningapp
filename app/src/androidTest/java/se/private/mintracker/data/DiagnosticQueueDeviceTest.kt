package se.oxelv.mintracker.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DiagnosticQueueDeviceTest {
    @Test fun offlineQueueSurvivesReopenAndCannotAcknowledgeOtherAccount() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "diagnostic-test-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, DiagnosticDatabase::class.java, name).build()
        var db = open()
        try {
            val a = UUID.randomUUID().toString(); val b = UUID.randomUUID().toString()
            db.queue().insert(DiagnosticRow("a", a, 1000, "GPS", "{}"))
            db.queue().insert(DiagnosticRow("b", b, 1000, "AUTH", "{}"))
            db.close(); db = open()
            assertEquals(listOf("a"), db.queue().batch(a).map { it.id })
            db.queue().acknowledge(b, listOf("a"))
            assertEquals(1, db.queue().batch(a).size)
            repeat(6) { db.queue().attempted(a, listOf("a")) }
            assertTrue(db.queue().batch(a).isEmpty())
            assertEquals(2, db.queue().count()) // exhausted rows do not trigger infinite retries
            db.queue().clearOwner(a)
            assertEquals(listOf("b"), db.queue().batch(b).map { it.id })
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun diagnosticStormIsBoundedAndOldReportsExpire() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, DiagnosticDatabase::class.java).build()
        try {
            repeat(250) { db.queue().insert(DiagnosticRow("$it", "owner", it.toLong(), "key", "{}")) }
            db.queue().bound(); assertEquals(200, db.queue().count())
            assertTrue(db.queue().duplicate("owner", "key", 200))
            db.queue().expire(249); assertEquals(1, db.queue().count())
        } finally { db.close() }
    }
}
