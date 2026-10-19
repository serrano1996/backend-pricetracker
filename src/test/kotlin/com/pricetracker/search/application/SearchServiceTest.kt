package com.pricetracker.search.application

import com.pricetracker.TestcontainersConfiguration
import com.pricetracker.config.SearchProperties
import com.pricetracker.search.application.port.StoreCatalogPort
import com.pricetracker.search.domain.PostalCode
import com.pricetracker.search.domain.SearchTerm
import com.pricetracker.search.domain.StoreName
import com.pricetracker.search.domain.StoreOffer
import com.pricetracker.search.domain.StoreSearchResult
import com.pricetracker.search.infrastructure.persistence.SearchSnapshotRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** No test transaction on purpose: production runs outside one, so lazy loading bugs must show up here. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class SearchServiceTest(
    @Autowired private val refresh: StoreRefreshService,
    @Autowired private val snapshots: SearchSnapshotRepository,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private class FakeCatalog : StoreCatalogPort {
        val responses = ConcurrentHashMap<StoreName, StoreSearchResult>()
        val calls = ConcurrentLinkedQueue<StoreName>()
        val wakeups = AtomicInteger()
        var delay: Duration = Duration.ZERO

        override fun search(store: StoreName, term: SearchTerm, postalCode: PostalCode): StoreSearchResult {
            calls += store
            Thread.sleep(delay)
            return responses[store] ?: StoreSearchResult.Found(
                "w-${store.code}",
                listOf(StoreOffer("${store.code}-1", "Leche ${store.code}", BigDecimal("0.95"), "0.95 €/L", "https://img", "Leche")),
            )
        }

        override fun wakeIdleScrapers() {
            wakeups.incrementAndGet()
        }
    }

    private val catalog = FakeCatalog()
    private val service by lazy {
        SearchService(catalog, snapshots, refresh, SearchProperties(freshnessTtl = Duration.ofHours(12)), Clock.systemUTC())
    }
    private val term = SearchTerm.of("leche")
    private val cp = PostalCode.of("28001")

    // Before and after: this class commits for real, and the @Transactional suites sharing the container expect empty tables
    @BeforeTest
    @AfterTest
    fun cleanDatabase() {
        jdbc.execute("truncate store_products, price_history, search_snapshots, search_snapshot_items, search_metrics cascade")
    }

    private fun ageSnapshots(hours: Long) =
        jdbc.update("update search_snapshots set fetched_at = fetched_at - make_interval(hours => ?)", hours.toInt())

    @Test
    fun `refreshes every store on first search and returns them in fixed order`() {
        val result = service.search(term, cp)

        assertEquals(listOf(StoreName.MERCADONA, StoreName.ALCAMPO, StoreName.DIA), result.stores.map { it.store })
        assertTrue(result.stores.all { it.status == StoreStatus.OK && !it.stale })
        assertEquals(listOf("alcampo-1"), result.stores[1].products.map { it.externalId })
        assertEquals(BigDecimal("0.95"), result.stores[1].products.single().unitPrice)
        assertEquals("L", result.stores[1].products.single().unitPriceUnit)
        assertEquals(3, catalog.calls.size)
        assertEquals(1, catalog.wakeups.get())
    }

    @Test
    fun `fresh snapshots are served from the database without calling scrapers`() {
        service.search(term, cp)
        catalog.calls.clear()

        val result = service.search(term, cp)

        assertEquals(0, catalog.calls.size)
        assertEquals(listOf("mercadona-1"), result.stores[0].products.map { it.externalId })
        assertTrue(result.stores.all { it.fetchedAt != null })
    }

    @Test
    fun `snapshots older than the TTL are refreshed`() {
        service.search(term, cp)
        ageSnapshots(13)
        catalog.calls.clear()

        service.search(term, cp)

        assertEquals(setOf(StoreName.MERCADONA, StoreName.ALCAMPO, StoreName.DIA), catalog.calls.toSet())
        assertEquals(3, catalog.calls.size)
    }

    @Test
    fun `only the stale store is refreshed`() {
        service.search(term, cp)
        jdbc.update("update search_snapshots set fetched_at = fetched_at - interval '13 hours' where store_name = 'dia'")
        catalog.calls.clear()

        service.search(term, cp)

        assertEquals(listOf(StoreName.DIA), catalog.calls.toList())
    }

    @Test
    fun `failing store with an old snapshot is served stale, others unaffected`() {
        service.search(term, cp)
        ageSnapshots(13)
        catalog.responses[StoreName.ALCAMPO] = StoreSearchResult.Unavailable("HTTP 502")

        val result = service.search(term, cp)

        val alcampo = result.stores[1]
        assertEquals(StoreStatus.OK, alcampo.status)
        assertTrue(alcampo.stale)
        assertEquals(listOf("alcampo-1"), alcampo.products.map { it.externalId })
        assertTrue(result.stores.filter { it.store != StoreName.ALCAMPO }.none { it.stale })
    }

    @Test
    fun `failing store without snapshot is unavailable with no products`() {
        catalog.responses[StoreName.DIA] = StoreSearchResult.Unavailable("timeout")

        val result = service.search(term, cp)

        val dia = result.stores[2]
        assertEquals(StoreStatus.UNAVAILABLE, dia.status)
        assertEquals(emptyList(), dia.products)
        assertNull(dia.fetchedAt)
        assertEquals(StoreStatus.OK, result.stores[0].status)
    }

    @Test
    fun `not served store is reported and cached as fresh`() {
        catalog.responses[StoreName.ALCAMPO] = StoreSearchResult.NotServed

        assertEquals(StoreStatus.NOT_SERVED, service.search(term, cp).stores[1].status)
        catalog.calls.clear()
        assertEquals(StoreStatus.NOT_SERVED, service.search(term, cp).stores[1].status)
        assertEquals(0, catalog.calls.size)
    }

    @Test
    fun `all stores unavailable with no data fails the search`() {
        StoreName.entries.forEach { catalog.responses[it] = StoreSearchResult.Unavailable("down") }

        assertFailsWith<AllStoresUnavailableException> { service.search(term, cp) }
    }

    @Test
    fun `stale stores are fetched in parallel`() {
        catalog.delay = Duration.ofSeconds(1)

        val started = System.nanoTime()
        service.search(term, cp)
        val elapsed = Duration.ofNanos(System.nanoTime() - started)

        assertTrue(elapsed < Duration.ofSeconds(2), "took $elapsed")
    }
}
