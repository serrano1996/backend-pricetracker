package com.pricetracker.refreshjob

import com.pricetracker.TestcontainersConfiguration
import com.pricetracker.search.application.port.LockAttempt
import com.pricetracker.search.application.port.LockToken
import com.pricetracker.search.application.port.RefreshLockPort
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class TopTermsRefreshJobTest(
    @Autowired private val locks: RefreshLockPort,
    @Autowired private val context: ApplicationContext,
) {
    private val runs = AtomicInteger()
    private val summary = RefreshSummary(pairs = 0, refreshed = 0, skipped = 0, failed = 0, duration = Duration.ZERO)

    @Test
    fun `is registered and scheduled when enabled`() {
        assertEquals(1, context.getBeansOfType(TopTermsRefreshJob::class.java).size)
    }

    @Test
    fun `only one of two concurrent runs does the work`() {
        val start = CountDownLatch(1)
        val job = TopTermsRefreshJob(locks, RefreshJobProperties()) {
            runs.incrementAndGet()
            Thread.sleep(300)
            summary
        }

        val threads = (1..2).map { Thread.ofVirtual().start { start.await(); job.run() } }
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(1, runs.get())
    }

    @Test
    fun `releases its lock so the next night can run`() {
        val job = TopTermsRefreshJob(locks, RefreshJobProperties()) { runs.incrementAndGet(); summary }

        job.run()
        job.run()

        assertEquals(2, runs.get())
    }

    @Test
    fun `does not run when redis is unavailable`() {
        val down = object : RefreshLockPort {
            override fun tryAcquire(key: String, ttl: Duration) = LockAttempt.Unavailable
            override fun release(token: LockToken) = error("nothing to release")
        }

        TopTermsRefreshJob(down, RefreshJobProperties()) { runs.incrementAndGet(); summary }.run()

        assertEquals(0, runs.get())
    }

    @Test
    fun `lock outlives the longest expected run`() {
        val props = RefreshJobProperties(topN = 50, maxDelay = Duration.ofSeconds(10), readTimeout = Duration.ofSeconds(90))

        assertTrue(props.lockTtl() >= Duration.ofSeconds(90 + 50 * (10 + 90)))
    }
}

@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["pricetracker.refresh-job.enabled=false"])
class TopTermsRefreshJobDisabledTest(@Autowired private val context: ApplicationContext) {

    @Test
    fun `is not registered when disabled`() {
        assertEquals(0, context.getBeansOfType(TopTermsRefreshJob::class.java).size)
    }
}
