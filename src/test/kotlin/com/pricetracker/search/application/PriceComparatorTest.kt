package com.pricetracker.search.application

import com.pricetracker.search.domain.PostalCode
import com.pricetracker.search.domain.ProductType
import com.pricetracker.search.domain.SearchTerm
import com.pricetracker.search.domain.StoreName
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PriceComparatorTest {

    private val entera = ProductType(UUID.randomUUID(), "Leche entera", "L", listOf("leche", "entera"), emptyList())
    private val semi = ProductType(UUID.randomUUID(), "Leche semidesnatada", "L", listOf("leche", "semidesnatada"), emptyList())
    private val arroz = ProductType(UUID.randomUUID(), "Arroz redondo", "kg", listOf("arroz", "redondo"), emptyList())
    private val types = listOf(entera, semi, arroz).associateBy { it.id }

    private fun product(name: String, type: ProductType?, unitPrice: String?, unit: String? = type?.unit) = ProductView(
        id = UUID.randomUUID(), externalId = name, name = name, price = BigDecimal("1.00"), priceFormat = null,
        unitPrice = unitPrice?.let(::BigDecimal), unitPriceUnit = unit, imageUrl = null, category = null, productTypeId = type?.id,
    )

    private fun result(vararg stores: Pair<StoreName, List<ProductView>>) = SearchResult(
        SearchTerm.of("leche"), PostalCode.of("28001"),
        stores.map { (store, products) -> StoreResult(store, StoreStatus.OK, stale = false, fetchedAt = Instant.EPOCH, products = products) },
    )

    private fun summary(comparisons: List<TypeComparison>) =
        comparisons.map { c -> c.type.name to c.offers.map { "${it.store.code}:${it.product.name}" } }

    @Test
    fun `keeps the cheapest offer per store, cheapest store first`() {
        val comparisons = PriceComparator.compare(
            result(
                StoreName.MERCADONA to listOf(product("m-cara", entera, "1.20"), product("m-barata", entera, "0.95")),
                StoreName.ALCAMPO to listOf(product("a", entera, "0.89")),
                StoreName.DIA to listOf(product("d", entera, "1.05")),
            ),
            types,
        )

        assertEquals(listOf("Leche entera" to listOf("alcampo:a", "mercadona:m-barata", "dia:d")), summary(comparisons))
    }

    @Test
    fun `types offered by more stores come first, then by name`() {
        val comparisons = PriceComparator.compare(
            result(
                StoreName.MERCADONA to listOf(product("m-semi", semi, "0.80"), product("m-arroz", arroz, "1.15"), product("m-ent", entera, "0.95")),
                StoreName.DIA to listOf(product("d-semi", semi, "0.85"), product("d-arroz", arroz, "1.15")),
            ),
            types,
        )

        assertEquals(listOf("Arroz redondo", "Leche semidesnatada", "Leche entera"), comparisons.map { it.type.name })
    }

    @Test
    fun `untyped products, products without unit price or with another unit are left out`() {
        val comparisons = PriceComparator.compare(
            result(
                StoreName.MERCADONA to listOf(product("sin-tipo", null, "0.50", "L"), product("sin-precio", entera, null)),
                StoreName.DIA to listOf(product("otra-unidad", entera, "0.10", "kg"), product("ok", entera, "0.99")),
            ),
            types,
        )

        assertEquals(listOf("Leche entera" to listOf("dia:ok")), summary(comparisons))
    }

    @Test
    fun `no typed products give no comparison`() {
        assertEquals(emptyList(), PriceComparator.compare(result(StoreName.DIA to listOf(product("x", null, null))), types))
    }
}
