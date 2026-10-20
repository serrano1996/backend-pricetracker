package com.pricetracker.refreshjob

import com.pricetracker.TestcontainersConfiguration
import com.pricetracker.config.SearchProperties
import com.pricetracker.search.application.FakeCatalog
import com.pricetracker.search.application.SearchService
import com.pricetracker.search.application.StoreRefreshService
import com.pricetracker.search.application.port.RefreshLockPort
import com.pricetracker.search.domain.PostalCode
import com.pricetracker.search.domain.PostalCodeLocator
import com.pricetracker.search.domain.SearchTerm
import com.pricetracker.search.domain.StoreName
import com.pricetracker.search.domain.StoreSearchResult
import com.pricetracker.search.infrastructure.persistence.SearchMetricRepository
import com.pricetracker.search.infrastructure.persistence.SearchSnapshotRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Duration
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real PostgreSQL, Redis lock and SearchService; only the scrapers are faked. Commits for real. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class TopTermsRefresherTest(
    @Autowired private val metrics: SearchMetricRepository,
    @Autowired private val snapshots: SearchSnapshotRepository,
    @Autowired private val refresh: StoreRefreshService,
    @Autowired private val locks: RefreshLockPort,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val catalog = FakeCatalog()
    private val sleeps = mutableListOf<Duration>()
    private val props = RefreshJobProperties(
        topN = 10, minDelay = Duration.ofSeconds(3), maxDelay = Duration.ofSeconds(10), storeFailureLimit = 2,
    )

    private fun refresher(properties: RefreshJobProperties = props): TopTermsRefresher {
        val search = SearchService(catalog, snapshots, refresh, locks, { true }, PostalCodeLocator(emptyMap()), SearchProperties(), Clock.systemUTC())
        return TopTermsRefresher(metrics, search, catalog, properties, Clock.systemUTC(), Random(42)) { sleeps += it }
    }

    @BeforeTest
    @AfterTest
    fun clean() {
        jdbc.execute("truncate store_products, price_history, search_snapshots, search_snapshot_items, search_metrics cascade")
    }

    private fun searched(term: String, count: Int, daysAgo: Int = 0, cp: String = "28001") = jdbc.update(
        "insert into search_metrics (query_term, postal_code, search_count, last_searched_at) values (?, ?, ?, now() - make_interval(days => ?))",
        term, cp, count, daysAgo,
    )

    private fun termsCalled() = catalog.searches.map { it.second }.distinct()

    @Test
    fun `refreshes the most searched recent terms in order, sequentially`() {
        searched("pan", 5)
        searched("leche", 10)
        searched("turron", 100, daysAgo = 40)

        val summary = refresher().run()

        assertEquals(listOf("leche", "pan"), termsCalled())
        assertEquals(2, summary.pairs)
        assertEquals(6, summary.refreshed)
        assertEquals(0, summary.failed)
    }

    @Test
    fun `waits a random delay within range between pairs, not before the first`() {
        searched("leche", 3)
        searched("pan", 2)
        searched("arroz", 1)

        refresher().run()

        assertEquals(2, sleeps.size)
        assertTrue(sleeps.all { it >= Duration.ofSeconds(3) && it <= Duration.ofSeconds(10) }, "$sleeps")
    }

    @Test
    fun `skips stores whose snapshot is still fresh`() {
        searched("leche", 1)
        SearchService(catalog, snapshots, refresh, locks, { true }, PostalCodeLocator(emptyMap()), SearchProperties(), Clock.systemUTC())
            .search(SearchTerm.of("leche"), PostalCode.of("28001"))
        jdbc.update("update search_snapshots set fetched_at = fetched_at - interval '13 hours' where store_name = 'dia'")
        catalog.calls.clear()
        catalog.searches.clear()

        val summary = refresher().run()

        assertEquals(listOf(StoreName.DIA), catalog.calls.toList())
        assertEquals(1, summary.refreshed)
        assertEquals(2, summary.skipped)
    }

    @Test
    fun `stops calling a store after consecutive failures`() {
        (1..4).forEach { searched("term$it", 10 - it) }
        catalog.responses[StoreName.ALCAMPO] = StoreSearchResult.Unavailable("HTTP 502")

        val summary = refresher().run()

        assertEquals(2, catalog.calls.count { it == StoreName.ALCAMPO })
        assertEquals(4, catalog.calls.count { it == StoreName.MERCADONA })
        assertEquals(2, summary.failed)
    }

    @Test
    fun `wakes every scraper before the first pair, and a scraper that does not wake counts as failed`() {
        searched("leche", 2)
        searched("pan", 1)
        catalog.wakeFails += StoreName.DIA

        refresher(props.copy(storeFailureLimit = 1)).run()

        assertEquals(StoreName.entries.toSet(), catalog.awakened.toSet())
        assertTrue(StoreName.DIA !in catalog.calls)
    }

    @Test
    fun `skips stored terms that today's rules reject and refreshes the rest`() {
        searched("!!", 9)
        searched("leche", 5)

        val summary = refresher().run()

        assertEquals(listOf("leche"), termsCalled())
        assertEquals(3, summary.refreshed)
    }

    @Test
    fun `does not count as user searches`() {
        searched("leche", 7)

        refresher().run()

        assertEquals(7L, jdbc.queryForObject("select search_count from search_metrics", Long::class.java))
    }

    @Test
    fun `empty metrics end without calls`() {
        val summary = refresher().run()

        assertEquals(0, summary.pairs)
        assertEquals(0, catalog.calls.size)
        assertEquals(emptyList(), sleeps)
    }
}
