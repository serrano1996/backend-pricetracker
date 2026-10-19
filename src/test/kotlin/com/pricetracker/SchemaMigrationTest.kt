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
    fun `rejects malformed postal codes in metrics`() {
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("insert into search_metrics (query_term, postal_code, last_searched_at) values ('leche', '2800', now())")
        }
    }
}
