package com.pricetracker.refreshjob

import com.pricetracker.config.SearchProperties
import com.pricetracker.search.application.SearchService
import com.pricetracker.search.application.StoreRefreshService
import com.pricetracker.search.application.port.RefreshLockPort
import com.pricetracker.search.infrastructure.persistence.SearchMetricRepository
import com.pricetracker.search.infrastructure.persistence.SearchSnapshotRepository
import com.pricetracker.search.infrastructure.scraper.ScraperClient
import com.pricetracker.search.infrastructure.scraper.ScraperProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock
import kotlin.random.Random

@Configuration
@EnableScheduling
@ConditionalOnProperty("pricetracker.refresh-job.enabled", havingValue = "true", matchIfMissing = true)
class RefreshJobConfig {

    /**
     * The job's own scraper client and search service, built here and not exposed as beans: they differ from
     * the request path only in the long read timeout and a lock TTL that outlives it (spec 003 RF-10, plan D3b).
     */
    @Bean
    fun topTermsRefreshJob(
        locks: RefreshLockPort,
        metrics: SearchMetricRepository,
        snapshots: SearchSnapshotRepository,
        refresh: StoreRefreshService,
        scrapers: ScraperProperties,
        searchProperties: SearchProperties,
        properties: RefreshJobProperties,
        clock: Clock,
    ): TopTermsRefreshJob {
        val catalog = ScraperClient(scrapers.withReadTimeout(properties.readTimeout), clock)
        // No call budget: the job is already capped by top-n, and user traffic must not starve it
        val search = SearchService(
            catalog, snapshots, refresh, locks, { true },
            searchProperties.copy(lockTtl = properties.readTimeout.plusSeconds(10)), clock,
        )
        val refresher = TopTermsRefresher(metrics, search, catalog, properties, clock, Random.Default) { Thread.sleep(it) }
        return TopTermsRefreshJob(locks, properties, refresher::run)
    }
}
