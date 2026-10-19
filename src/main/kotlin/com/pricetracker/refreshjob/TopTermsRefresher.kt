package com.pricetracker.refreshjob

import com.pricetracker.search.application.AllStoresUnavailableException
import com.pricetracker.search.application.SearchService
import com.pricetracker.search.application.StoreStatus
import com.pricetracker.search.application.port.StoreCatalogPort
import com.pricetracker.search.domain.PostalCode
import com.pricetracker.search.domain.SearchTerm
import com.pricetracker.search.domain.StoreName
import com.pricetracker.search.infrastructure.persistence.SearchMetricRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Limit
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.random.Random

fun interface Sleeper {
    fun sleep(duration: Duration)
}

data class RefreshSummary(val pairs: Int, val refreshed: Int, val skipped: Int, val failed: Int, val duration: Duration)

/**
 * Refreshes the most searched (term, postal code) pairs one after another, with random pauses between them
 * (spec 003 RF-2..RF-6, RF-8, RF-10). Goes through [SearchService], so freshness and locks apply as for users,
 * and it never touches search metrics.
 */
class TopTermsRefresher(
    private val metrics: SearchMetricRepository,
    private val search: SearchService,
    private val catalog: StoreCatalogPort,
    private val properties: RefreshJobProperties,
    private val clock: Clock,
    private val random: Random,
    private val sleeper: Sleeper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(): RefreshSummary {
        val started = clock.instant()
        val pairs = metrics.findTopRecent(started.minus(properties.recency), Limit.of(properties.topN))
        var refreshed = 0
        var skipped = 0
        var failed = 0
        val consecutiveFailures = StoreName.entries.associateWith { 0 }.toMutableMap()

        if (pairs.isNotEmpty()) {
            // Sleeping Render free scrapers get up to the long timeout to wake, all at once (RF-10)
            wakeAll().filterValues { !it }.keys.forEach { store ->
                consecutiveFailures.merge(store, 1, Int::plus)
                failed++
            }
        }

        for ((index, metric) in pairs.withIndex()) {
            val active = StoreName.entries.filter { consecutiveFailures.getValue(it) < properties.storeFailureLimit }.toSet()
            if (active.isEmpty()) break
            if (index > 0) sleeper.sleep(jitter())

            val pairStarted = clock.instant()
            val result = try {
                search.search(SearchTerm.of(metric.queryTerm), PostalCode.of(metric.postalCode), active)
            } catch (e: AllStoresUnavailableException) {
                null
            }
            for (store in active) {
                val outcome = result?.stores?.single { it.store == store }
                when {
                    outcome == null || outcome.status == StoreStatus.UNAVAILABLE || outcome.stale -> {
                        consecutiveFailures.merge(store, 1, Int::plus)
                        failed++
                    }
                    outcome.fetchedAt?.isBefore(pairStarted) == true -> skipped++ // still fresh (RF-5)
                    else -> {
                        consecutiveFailures[store] = 0
                        refreshed++
                    }
                }
            }
        }

        val summary = RefreshSummary(pairs.size, refreshed, skipped, failed, Duration.between(started, clock.instant()))
        log.info(
            "refresh job finished pairs={} refreshed={} skipped={} failed={} duration={}",
            summary.pairs, summary.refreshed, summary.skipped, summary.failed, summary.duration,
        )
        return summary
    }

    private fun wakeAll(): Map<StoreName, Boolean> = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
        StoreName.entries.associateWith { executor.submit<Boolean> { catalog.wakeAndWait(it) } }
            .mapValues { (_, awake) -> awake.get() }
    }

    private fun jitter(): Duration =
        Duration.ofMillis(random.nextLong(properties.minDelay.toMillis(), properties.maxDelay.toMillis() + 1))
}
