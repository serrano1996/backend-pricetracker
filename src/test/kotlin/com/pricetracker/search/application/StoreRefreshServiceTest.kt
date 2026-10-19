package com.pricetracker.search.application

import com.pricetracker.TestcontainersConfiguration
import com.pricetracker.search.domain.PostalCode
import com.pricetracker.search.domain.SearchTerm
import com.pricetracker.search.domain.StoreName
import com.pricetracker.search.domain.StoreOffer
import com.pricetracker.search.domain.StoreSearchResult
import com.pricetracker.search.infrastructure.persistence.PriceHistoryRepository
import com.pricetracker.search.infrastructure.persistence.SearchSnapshotRepository
import com.pricetracker.search.infrastructure.persistence.SnapshotStatus
import com.pricetracker.search.infrastructure.persistence.StoreProductRepository
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Transactional
class StoreRefreshServiceTest(
    @Autowired private val service: StoreRefreshService,
    @Autowired private val storeProducts: StoreProductRepository,
    @Autowired private val priceHistory: PriceHistoryRepository,
    @Autowired private val snapshots: SearchSnapshotRepository,
    @Autowired private val em: EntityManager,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val term = SearchTerm.of("leche")
    private val cp = PostalCode.of("28001")

    private fun offer(id: String, price: String, format: String? = "$price €/L") =
        StoreOffer(id, "Leche $id", BigDecimal(price), format, "https://img/$id.jpg", "Leche")

    private fun persist(vararg offers: StoreOffer, store: StoreName = StoreName.MERCADONA, warehouse: String = "mad1") {
        service.persist(store, term, cp, StoreSearchResult.Found(warehouse, offers.toList()))
        em.flush()
        em.clear()
    }

    private fun historyPrices() = priceHistory.findAll().sortedBy { it.id }.map { it.price.toPlainString() }

    @Test
    fun `new product is stored with parsed unit price and one history entry`() {
        persist(offer("1", "0.95", "0.25 €/100 g"))

        val product = storeProducts.findAll().single()
        assertEquals(StoreName.MERCADONA, product.storeName)
        assertEquals("mad1", product.warehouse)
        assertEquals(BigDecimal("0.95"), product.currentPrice)
        assertEquals(BigDecimal("2.50"), product.unitPrice)
        assertEquals("kg", product.unitPriceUnit)
        assertEquals(listOf("0.95"), historyPrices())
    }

    @Test
    fun `same price adds no history, a different price adds one`() {
        persist(offer("1", "0.95"))
        persist(offer("1", "0.95"))
        assertEquals(listOf("0.95"), historyPrices())

        persist(offer("1", "0.99"))

        assertEquals(listOf("0.95", "0.99"), historyPrices())
        assertEquals(BigDecimal("0.99"), storeProducts.findAll().single().currentPrice)
    }

    @Test
    fun `prices are rounded half up to cents before comparing`() {
        persist(offer("1", "1.055"))
        persist(offer("1", "1.0551"))

        assertEquals(listOf("1.06"), historyPrices())
    }

    @Test
    fun `same external id in another warehouse or store is another product`() {
        persist(offer("1", "0.95"))
        persist(offer("1", "0.97"), warehouse = "bcn1")
        persist(offer("1", "0.99"), store = StoreName.DIA)

        assertEquals(3, storeProducts.count())
    }

    @Test
    fun `duplicate offers in one response are stored once, at their first position`() {
        persist(offer("b", "2.00"), offer("a", "1.00"), offer("b", "2.00"))

        assertEquals(2, storeProducts.count())
        assertEquals(listOf("b", "a"), snapshots.findAll().single().items.map { it.externalId })
    }

    @Test
    fun `snapshot is replaced in place with the new order and fetch time`() {
        persist(offer("a", "1.00"), offer("b", "2.00"))
        // Copied out: the shared test transaction hands persist() this same managed instance
        val (firstId, firstFetchedAt) = snapshots.findAll().single().let { it.id to it.fetchedAt }

        persist(offer("c", "3.00"), offer("a", "1.00"))

        val second = snapshots.findAll().single()
        assertEquals(firstId, second.id)
        assertEquals(SnapshotStatus.OK, second.status)
        assertEquals(listOf("c", "a"), second.items.map { it.externalId })
        assertTrue(!second.fetchedAt.isBefore(firstFetchedAt))
    }

    @Test
    fun `empty result is stored as a fresh empty snapshot`() {
        persist()

        val snapshot = snapshots.findAll().single()
        assertEquals(SnapshotStatus.OK, snapshot.status)
        assertEquals(emptyList(), snapshot.items)
    }

    @Test
    fun `not served is stored as a not_served snapshot without items`() {
        service.persist(StoreName.ALCAMPO, term, cp, StoreSearchResult.NotServed)
        em.flush()
        em.clear()

        val snapshot = snapshots.findAll().single()
        assertEquals(StoreName.ALCAMPO, snapshot.storeName)
        assertEquals(SnapshotStatus.NOT_SERVED, snapshot.status)
        assertEquals(emptyList(), snapshot.items)
    }

    private fun typeOf(externalId: String): String? = jdbc.queryForList(
        "select p.canonical_name from store_products s left join products p on p.id = s.product_id where s.external_id = ?",
        String::class.java, externalId,
    ).single()

    @Test
    fun `assigns product types to real product names (spec 004 edge cases)`() {
        val expected = linkedMapOf(
            StoreOffer("1", "CENTRAL LECHERA ASTURIANA Leche entera botella 6 x 1 l.", BigDecimal("7.56"), "1.26 €/L", "i", "c") to "Leche entera",
            StoreOffer("2", "Leche semidesnatada Hacendado", BigDecimal("4.98"), "0.83 €/L", "i", "c") to "Leche semidesnatada",
            StoreOffer("3", "Galletas cubiertas de chocolate con leche Galleteca de Dia 150 g", BigDecimal("1.20"), "8.00 €/kg", "i", "c") to null,
            StoreOffer("4", "Leche desnatada sin lactosa Dia Láctea 1 L", BigDecimal("0.99"), "0.99 €/L", "i", "c") to null,
            StoreOffer("5", "Aceite de oliva virgen extra en spray Carbonell 200 ml", BigDecimal("3.29"), "16.45 €/L", "i", "c") to null,
            StoreOffer("6", "CARBONELL Aceite de oliva suave botella 1 l.", BigDecimal("5.24"), "5.24 €/L", "i", "c") to "Aceite de oliva",
            StoreOffer("7", "Aceite de oliva virgen extra Carbonell 1 L", BigDecimal("7.69"), "7.69 €/L", "i", "c") to "Aceite de oliva virgen extra",
            StoreOffer("8", "TILDA Arroz basmati integral 1 kg", BigDecimal("3.10"), "3.10 €/kg", "i", "c") to null,
            StoreOffer("9", "1880 Polvorones de almendra con aceite de oliva y harina integral, vegan 310 g.", BigDecimal("3.00"), "9.68 €/kg", "i", "c") to null,
            StoreOffer("10", "LA FALLERA Arroz redondo paquete de 1 kg.", BigDecimal("1.74"), "1.74 €/kg", "i", "c") to "Arroz redondo",
            StoreOffer("11", "Aceite de girasol Koipe 1 L", BigDecimal("2.85"), null, "i", "c") to null, // no unit price
            // False positives found with real data after V2, fixed in V3
            StoreOffer("12", "Vasos de arroz redondo Brillante 2 x 200 g", BigDecimal("1.99"), "4.98 €/kg", "i", "c") to null,
            StoreOffer("13", "Arroz cocido redondo Sabroz", BigDecimal("1.60"), "4.00 €/kg", "i", "c") to null,
        )

        persist(*expected.keys.toTypedArray())

        expected.forEach { (offer, type) -> assertEquals(type, typeOf(offer.externalId), offer.name) }
    }

    @Test
    fun `product type is recomputed on every refresh`() {
        persist(StoreOffer("1", "Leche entera Hacendado", BigDecimal("0.95"), "0.95 €/L", "i", "c"))
        assertEquals("Leche entera", typeOf("1"))

        persist(StoreOffer("1", "Leche entera sin lactosa Hacendado", BigDecimal("0.99"), "0.99 €/L", "i", "c"))
        assertEquals(null, typeOf("1"))

        persist(StoreOffer("1", "Leche semidesnatada Hacendado", BigDecimal("0.99"), "0.99 €/L", "i", "c"))
        assertEquals("Leche semidesnatada", typeOf("1"))
    }

    @Test
    fun `unavailable results are never persisted`() {
        assertFailsWith<IllegalArgumentException> {
            service.persist(StoreName.DIA, term, cp, StoreSearchResult.Unavailable("HTTP 502"))
        }
        assertEquals(0, snapshots.count())
    }
}
