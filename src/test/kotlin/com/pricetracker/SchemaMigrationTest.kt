package com.pricetracker

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Transactional
class SchemaMigrationTest(@Autowired private val jdbc: JdbcTemplate) {

    private val tables = listOf(
        "products", "store_products", "price_history",
        "search_snapshots", "search_snapshot_items", "search_metrics", "flyway_schema_history",
    )

    private fun insertStoreProduct(store: String = "mercadona", externalId: String = "10381") =
        jdbc.update(
            """insert into store_products (store_name, warehouse, external_id, store_product_name, current_price, last_checked_at)
               values (?, 'mad1', ?, 'Leche entera', 0.95, now())""",
            store, externalId,
        )

    @Test
    fun `creates every table with row level security enabled`() {
        val rls = jdbc.queryForList(
            "select relname from pg_class where relnamespace = 'public'::regnamespace and relkind = 'r' and relrowsecurity",
            String::class.java,
        )

        assertEquals(tables.sorted(), rls.filterNotNull().sorted())
    }

    @Test
    fun `store product is unique per store, warehouse and external id`() {
        insertStoreProduct()
        insertStoreProduct(store = "dia")

        assertFailsWith<DataIntegrityViolationException> { insertStoreProduct() }
    }

    @Test
    fun `rejects unknown store names`() {
        assertFailsWith<DataIntegrityViolationException> { insertStoreProduct(store = "carrefour") }
    }

    @Test
    fun `seeds the initial product types with unit and match rules`() {
        val types = jdbc.queryForList("select canonical_name, unit, array_to_string(match_all, ' ') as words from products")
            .associate { it["canonical_name"] to "${it["unit"]}|${it["words"]}" }

        // 11 from V2, 16 basic groceries from V4, 7 cleaning products from V5
        assertEquals(66, types.size)
        assertEquals("L|leche entera sin lactosa", types["Leche entera sin lactosa"])
        assertEquals("kg|queso rallado 4 quesos", types["Queso rallado 4 quesos"])
        assertEquals("L|leche entera", types["Leche entera"])
        assertEquals("L|aceite oliva virgen extra", types["Aceite de oliva virgen extra"])
        assertEquals("kg|arroz bomba", types["Arroz bomba"])
        assertEquals("kg|pan molde", types["Pan de molde"])
        assertEquals("kg|macarron|macarrones", types["Macarrones"])
        assertEquals("L|agua mineral", types["Agua mineral"])
        assertEquals("L|lejia|deterlejia detergente|deterlejia", types["Lejía con detergente"])
        assertEquals("ud|papel higienico doble rollo", types["Papel higiénico doble rollo"])
    }

    @Test
    fun `product type names are unique`() {
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("insert into products (canonical_name, unit, match_all) values ('Leche entera', 'L', '{leche}')")
        }
    }

    @Test
    fun `rejects malformed postal codes in metrics`() {
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("insert into search_metrics (query_term, postal_code, last_searched_at) values ('leche', '2800', now())")
        }
    }
}
