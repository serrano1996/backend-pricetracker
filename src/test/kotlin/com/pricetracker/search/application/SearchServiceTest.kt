package com.pricetracker.search.application

import com.pricetracker.TestcontainersConfiguration
import com.pricetracker.config.SearchProperties
import com.pricetracker.search.application.port.LockAttempt
import com.pricetracker.search.application.port.LockToken
import com.pricetracker.search.application.port.RefreshLockPort
import com.pricetracker.search.application.port.ScraperCallBudgetPort
import com.pricetracker.search.application.port.StoreCatalogPort
import com.pricetracker.search.domain.Coordinates
import com.pricetracker.search.domain.NearbyPostalCode
import com.pricetracker.search.domain.PostalCode
import com.pricetracker.search.domain.PostalCodeLocator
import com.pricetracker.search.domain.SearchTerm
import com.pricetracker.search.domain.StoreName
import com.pricetracker.search.domain.StoreOffer
import com.pricetracker.search.domain.StoreSearchResult
import com.pricetracker.search.infrastructure.persistence.SearchSnapshotRepository
import com.pricetracker.search.infrastructure.persistence.SnapshotStatus
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
    @Autowired private val redisLock: RefreshLockPort,
) {
    private val catalog = FakeCatalog()
    private val properties = SearchProperties(
        freshnessTtl = Duration.ofHours(12),
        lockTtl = Duration.ofSeconds(30),
        lockPollInterval = Duration.ofMillis(50),
        lockWaitTimeout = Duration.ofSeconds(5),
    )
    private fun service(
        locks: RefreshLockPort = redisLock,
        props: SearchProperties = properties,
        budget: ScraperCallBudgetPort = ScraperCallBudgetPort { true },
        // No coordinates by default: only the spec 006 tests look for other postal codes
        locator: PostalCodeLocator = PostalCodeLocator(emptyMap()),
    ) = SearchService(catalog, snapshots, refresh, locks, budget, locator, props, Clock.systemUTC())
    private val service by lazy { service() }
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
    fun `spends one budget unit per scraper call, none for fresh snapshots`() {
        val spent = AtomicInteger()
        val counted = service(budget = { spent.incrementAndGet(); true })

        counted.search(term, cp)
        counted.search(term, cp)

        assertEquals(3, spent.get())
    }

    @Test
    fun `exhausted budget serves old snapshots stale without calling scrapers`() {
        service.search(term, cp)
        ageSnapshots(13)
        catalog.calls.clear()

        val result = service(budget = { false }).search(term, cp)

        assertEquals(0, catalog.calls.size)
        assertTrue(result.stores.all { it.status == StoreStatus.OK && it.stale && it.products.size == 1 })
    }

    @Test
    fun `exhausted budget with nothing stored fails the search without calling scrapers`() {
        assertFailsWith<AllStoresUnavailableException> { service(budget = { false }).search(term, cp) }
        assertEquals(0, catalog.calls.size)
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

    @Test
    fun `concurrent searches of a stale term call each scraper once`() {
        catalog.delay = Duration.ofMillis(500)

        val results = (1..20).map { Thread.ofVirtual().start { service.search(term, cp) } }
        results.forEach { it.join() }

        assertEquals(mapOf(StoreName.MERCADONA to 1, StoreName.ALCAMPO to 1, StoreName.DIA to 1), catalog.calls.groupingBy { it }.eachCount())
    }

    @Test
    fun `waiters see the holder's fresh data`() {
        catalog.delay = Duration.ofMillis(300)
        val results = java.util.concurrent.ConcurrentLinkedQueue<SearchResult>()

        (1..5).map { Thread.ofVirtual().start { results += service.search(term, cp) } }.forEach { it.join() }

        assertTrue(results.all { r -> r.stores.all { it.status == StoreStatus.OK && !it.stale && it.products.size == 1 } })
    }

    @Test
    fun `when the wait times out the old snapshot is served stale without calling the scraper`() {
        service.search(term, cp)
        ageSnapshots(13)
        catalog.calls.clear()
        val held = redisLock.tryAcquire("pt:lock:refresh:dia:28001:leche", Duration.ofSeconds(30)) as LockAttempt.Acquired
        try {
            val result = service(props = properties.copy(lockWaitTimeout = Duration.ofMillis(300))).search(term, cp)

            assertTrue(result.stores[2].stale)
            assertEquals(StoreStatus.OK, result.stores[2].status)
            assertEquals(setOf(StoreName.MERCADONA, StoreName.ALCAMPO), catalog.calls.toSet())
        } finally {
            redisLock.release(held.token)
        }
    }

    @Test
    fun `refreshes without the lock when redis is unavailable`() {
        val downLock = object : RefreshLockPort {
            override fun tryAcquire(key: String, ttl: Duration) = LockAttempt.Unavailable
            override fun release(token: LockToken) = error("nothing to release")
        }

        val result = service(locks = downLock).search(term, cp)

        assertTrue(result.stores.all { it.status == StoreStatus.OK })
        assertEquals(3, catalog.calls.size)
    }

    @Test
    fun `the lock is released after a refresh, even a failed one`() {
        catalog.responses[StoreName.ALCAMPO] = StoreSearchResult.Unavailable("HTTP 502")

        service.search(term, cp)

        StoreName.entries.forEach { store ->
            val attempt = redisLock.tryAcquire("pt:lock:refresh:${store.code}:28001:leche", Duration.ofSeconds(1))
            assertTrue(attempt is LockAttempt.Acquired, "$store still locked")
            redisLock.release(attempt.token)
        }
    }

    // Spec 006, coordinates from the GeoNames file. From Tarazona: Soria 64 km < Zaragoza 76 < Logroño 86 < Pamplona 101
    private val tarazona = PostalCode.of("50500")
    private val aragon = PostalCodeLocator(
        mapOf(
            "50500" to Coordinates(41.9037, -1.7272), // Tarazona
            "50540" to Coordinates(41.8344, -1.5333), // Borja, not a provincial capital
            "42001" to Coordinates(41.7670, -2.4727), // Soria
            "50001" to Coordinates(41.6516, -0.8771), // Zaragoza
            "26001" to Coordinates(42.4667, -2.4457), // Logroño
            "31001" to Coordinates(42.8178, -1.6446), // Pamplona
        ),
    )
    private fun diaPostalCodes() = catalog.postalCodes.filter { it.first == StoreName.DIA }.map { it.second }

    @Test
    fun `a store that does not serve the postal code answers with the nearest one it serves`() {
        catalog.byPostalCode[StoreName.DIA to "50500"] = StoreSearchResult.NotServed
        catalog.byPostalCode[StoreName.DIA to "42001"] = StoreSearchResult.NotServed

        val result = service(locator = aragon).search(term, tarazona)

        val dia = result.stores[2]
        assertEquals(StoreStatus.OK, dia.status)
        assertEquals(NearbyPostalCode("50001", 76), dia.reference)
        assertEquals(listOf("dia-1"), dia.products.map { it.externalId })
        assertEquals(listOf("50500", "42001", "50001"), diaPostalCodes())
        assertTrue(result.stores.take(2).all { it.status == StoreStatus.OK && it.reference == null })
        // Each answer is stored under its own postal code
        assertEquals(SnapshotStatus.NOT_SERVED, snapshots.findByQueryTermAndPostalCodeAndStoreName("leche", "50500", StoreName.DIA)?.status)
        assertEquals(SnapshotStatus.OK, snapshots.findByQueryTermAndPostalCodeAndStoreName("leche", "50001", StoreName.DIA)?.status)
    }

    @Test
    fun `postal codes learnt as not served are skipped for any term`() {
        catalog.byPostalCode[StoreName.DIA to "50500"] = StoreSearchResult.NotServed
        catalog.byPostalCode[StoreName.DIA to "42001"] = StoreSearchResult.NotServed
        service(locator = aragon).search(term, tarazona)
        catalog.postalCodes.clear()

        val dia = service(locator = aragon).search(SearchTerm.of("agua"), tarazona).stores[2]

        assertEquals("50001", dia.reference?.postalCode)
        assertEquals(listOf("50500", "50001"), diaPostalCodes())
    }

    @Test
    fun `a postal code learnt as served is used even if it is not a provincial capital`() {
        service(locator = aragon).search(term, PostalCode.of("50540"))
        catalog.byPostalCode[StoreName.DIA to "50500"] = StoreSearchResult.NotServed
        catalog.postalCodes.clear()

        val dia = service(locator = aragon).search(term, tarazona).stores[2]

        assertEquals(NearbyPostalCode("50540", 18), dia.reference)
        // Borja's snapshot is fresh: no call for it
        assertEquals(listOf("50500"), diaPostalCodes())
    }

    @Test
    fun `gives up after the probe limit and stays not served`() {
        catalog.responses[StoreName.DIA] = StoreSearchResult.NotServed

        val dia = service(locator = aragon).search(term, tarazona).stores[2]

        assertEquals(StoreStatus.NOT_SERVED, dia.status)
        assertNull(dia.reference)
        assertEquals(listOf("50500", "42001", "50001", "26001"), diaPostalCodes())
    }

    @Test
    fun `only the selected stores are read, called and returned, in fixed order`() {
        val result = service.search(term, cp, only = setOf(StoreName.DIA, StoreName.MERCADONA))

        assertEquals(listOf(StoreName.MERCADONA, StoreName.DIA), result.stores.map { it.store })
        assertEquals(setOf(StoreName.MERCADONA, StoreName.DIA), catalog.calls.toSet())
        assertEquals(2, catalog.calls.size)
        assertNull(snapshots.findByQueryTermAndPostalCodeAndStoreName("leche", "28001", StoreName.ALCAMPO))
    }

    @Test
    fun `selected stores all unavailable fail the search even if another store has data`() {
        service.search(term, cp, only = setOf(StoreName.MERCADONA))
        catalog.responses[StoreName.DIA] = StoreSearchResult.Unavailable("down")

        assertFailsWith<AllStoresUnavailableException> { service.search(term, cp, only = setOf(StoreName.DIA)) }
    }
}
