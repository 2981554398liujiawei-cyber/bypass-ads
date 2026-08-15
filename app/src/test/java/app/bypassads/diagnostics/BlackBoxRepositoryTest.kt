package app.bypassads.diagnostics

import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlackBoxRepositoryTest {
    @Test
    fun `concurrent producers write complete parseable records`() {
        val repository = repository()
        val producers = Executors.newFixedThreadPool(8)
        val writes = (0 until 100).map { index -> producers.submit(Callable { repository.append(record(index)); Unit }) }
        writes.forEach { it.get() }
        producers.shutdown()

        val records = awaitRecords(repository)
        assertEquals(100, records.size)
        assertEquals(100, records.map { it.scanIndex }.toSet().size)
    }

    @Test
    fun `queue order makes clear remove prior appends but retain following append`() {
        val repository = repository()
        repository.append(record(1))
        repository.append(record(2))
        repository.clear()
        repository.append(record(3))

        assertEquals(listOf(3), awaitRecords(repository).map { it.scanIndex })
    }

    @Test
    fun `read queued after append sees the append`() {
        val repository = repository()
        repository.append(record(7))
        assertEquals(listOf(7), awaitRecords(repository).map { it.scanIndex })
    }

    @Test
    fun `repository remains usable after an earlier client operation completes`() {
        val repository = repository()
        repository.append(record(11))
        assertEquals(listOf(11), awaitRecords(repository).map { it.scanIndex })
    }

    @Test
    fun `health reports zero pending jobs when the queue is idle`() {
        val repository = repository()
        repeat(10) { repository.append(record(it)) }
        val latch = CountDownLatch(1)
        var health: BlackBoxIoHealth? = null
        repository.health { value -> health = value; latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(0, health!!.pendingIoJobs)
        assertTrue(health.maxPendingIoJobs >= 1)
        assertEquals(10L, health.recordsWritten)
    }

    @Test
    fun `health includes queued writes ahead of the health request`() {
        val repository = repository()
        repeat(50) { repository.append(record(it)) }
        val latch = CountDownLatch(1)
        var health: BlackBoxIoHealth? = null
        repository.health { value -> health = value; latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(0, health!!.pendingIoJobs)
        assertEquals(50L, health.recordsWritten)
        assertTrue(health.maxPendingIoJobs >= 50)
    }

    private fun repository(): BlackBoxRepository = BlackBoxRepository(Files.createTempDirectory("blackbox-test").toFile())

    private fun record(scan: Int) = BlackBoxRecord(
        epochMs = 1_786_800_000_000L,
        sessionId = 1L,
        scanIndex = scan,
        packageName = "com.demo",
        trigger = BlackBoxTrigger.SCAN,
    )

    private fun awaitRecords(repository: BlackBoxRepository): List<DiagnosticRecord> {
        val latch = CountDownLatch(1)
        var records = emptyList<DiagnosticRecord>()
        repository.recentRecords(200) { value -> records = value; latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return records
    }
}
