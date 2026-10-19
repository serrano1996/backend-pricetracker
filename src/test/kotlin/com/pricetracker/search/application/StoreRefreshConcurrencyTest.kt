package com.pricetracker.search.application

import com.pricetracker.TestcontainersConfiguration
import com.pricetracker.search.domain.PostalCode
import com.pricetracker.search.domain.SearchTerm
import com.pricetracker.search.domain.StoreName
import com.pricetracker.search.domain.StoreOffer
import com.pricetracker.search.domain.StoreSearchResult
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Spec 002 RF-7: even without the Redis lock, concurrent refreshes must not duplicate price history. Commits for real. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class StoreRefreshConcurrencyTest(
    @Autowired private val service: StoreRefreshService,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val term = SearchTerm.of("leche")
    private val cp = PostalCode.of("28001")

    @BeforeTest
    @AfterTest
    fun clean() {
        jdbc.execute("truncate store_products, price_history, search_snapshots, search_snapshot_items, search_metrics cascade")
    }

    private fun found(price: String) = StoreSearchResult.Found(
        "mad1", listOf(StoreOffer("1", "Leche", BigDecimal(price), "$price €/L", "https://img", "Leche")),
    )

    @Test
    fun `concurrent refreshes record a price change once`() {
        service.persist(StoreName.MERCADONA, term, cp, found("0.95"))
        val start = CountDownLatch(1)

        val threads = (1..10).map {
            Thread.ofVirtual().start {
                start.await()
                service.persist(StoreName.MERCADONA, term, cp, found("0.99"))
            }
        }
        start.countDown()
        threads.forEach { it.join() }

        val history = jdbc.queryForList("select price from price_history order by id", BigDecimal::class.java)
        assertEquals(listOf(BigDecimal("0.95"), BigDecimal("0.99")), history)
    }
}
