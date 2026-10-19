package com.pricetracker.search.infrastructure.persistence

import com.pricetracker.TestcontainersConfiguration
import com.pricetracker.search.domain.StoreName
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Transactional
class PersistenceMappingTest(
    @Autowired private val storeProducts: StoreProductRepository,
    @Autowired private val priceHistory: PriceHistoryRepository,
    @Autowired private val snapshots: SearchSnapshotRepository,
    @Autowired private val em: EntityManager,
) {
    private val now = Instant.now().truncatedTo(ChronoUnit.MICROS)

    private fun storeProduct(externalId: String, price: String) = storeProducts.save(
        StoreProductEntity(
            storeName = StoreName.ALCAMPO, warehouse = "mad1", externalId = externalId,
            name = "Leche $externalId", category = "Lácteos", imageUrl = "https://img/$externalId.jpg",
            currentPrice = BigDecimal(price), priceFormat = "$price €/L",
            unitPrice = BigDecimal(price), unitPriceUnit = "L", lastCheckedAt = now,
        ),
    )

    /** Forces a real database round trip instead of reading the persistence context. */
    private fun flushAndClear() {
        em.flush()
        em.clear()
    }

    @Test
    fun `store product round-trips with its price history`() {
        val product = storeProduct("10381", "0.95")
        priceHistory.save(PriceHistoryEntity(product, BigDecimal("0.99"), BigDecimal("0.99"), now.minusSeconds(3600)))
        priceHistory.save(PriceHistoryEntity(product, BigDecimal("0.95"), BigDecimal("0.95"), now))
        flushAndClear()

        val loaded = storeProducts.findById(product.id!!).get()
        assertEquals(StoreName.ALCAMPO, loaded.storeName)
        assertEquals(BigDecimal("0.95"), loaded.currentPrice)
        assertEquals("L", loaded.unitPriceUnit)
        assertEquals(now, loaded.lastCheckedAt)
        assertEquals(
            listOf(BigDecimal("0.99"), BigDecimal("0.95")),
            priceHistory.findAll().sortedBy { it.recordedAt }.map { it.price },
        )
    }

    @Test
    fun `snapshot keeps item order and replacing items rewrites them`() {
        val a = storeProduct("a", "1.00")
        val b = storeProduct("b", "2.00")
        val c = storeProduct("c", "3.00")
        val snapshot = snapshots.save(
            SearchSnapshotEntity("leche", "28001", StoreName.DIA, SnapshotStatus.OK, now, mutableListOf(b, a)),
        )
        flushAndClear()

        val loaded = snapshots.findById(snapshot.id!!).get()
        assertEquals(listOf("b", "a"), loaded.items.map { it.externalId })

        loaded.items = mutableListOf(c)
        flushAndClear()
        assertEquals(listOf("c"), snapshots.findById(snapshot.id!!).get().items.map { it.externalId })
    }
}
