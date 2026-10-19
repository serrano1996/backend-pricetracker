package com.pricetracker.history.web

import com.pricetracker.TestcontainersConfiguration
import com.pricetracker.search.application.StoreRefreshService
import com.pricetracker.search.domain.PostalCode
import com.pricetracker.search.domain.SearchTerm
import com.pricetracker.search.domain.StoreName
import com.pricetracker.search.domain.StoreOffer
import com.pricetracker.search.domain.StoreSearchResult
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PriceHistoryControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val refresh: StoreRefreshService,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val em: EntityManager,
) {
    private fun persist(price: String, format: String?) {
        refresh.persist(
            StoreName.MERCADONA, SearchTerm.of("leche"), PostalCode.of("28001"),
            StoreSearchResult.Found("mad1", listOf(StoreOffer("10381", "Leche entera", BigDecimal(price), format, "https://img/1.jpg", "Leche"))),
        )
        em.flush()
        em.clear()
    }

    private fun productId(): String = jdbc.queryForObject("select id from store_products", String::class.java)!!

    @Test
    fun `returns the product and its full history, oldest first`() {
        persist("0.95", "0.95 €/L")
        jdbc.update("update price_history set recorded_at = recorded_at - interval '30 days'")
        persist("0.99", null)
        val id = productId()

        mockMvc.get("/api/v1/products/$id/price-history").andExpect {
            status { isOk() }
            jsonPath("$.product.id") { value(id) }
            jsonPath("$.product.store") { value("mercadona") }
            jsonPath("$.product.external_id") { value("10381") }
            jsonPath("$.product.name") { value("Leche entera") }
            jsonPath("$.product.current_price") { value(0.99) }
            jsonPath("$.product.price_format") { value(null) }
            jsonPath("$.product.unit_price") { value(null) }
            jsonPath("$.product.unit_price_unit") { value(null) }
            jsonPath("$.product.image_url") { value("https://img/1.jpg") }
            jsonPath("$.product.category") { value("Leche") }
            jsonPath("$.product.last_checked_at") { isString() }
            jsonPath("$.history.length()") { value(2) }
            jsonPath("$.history[0].price") { value(0.95) }
            jsonPath("$.history[0].unit_price") { value(0.95) }
            jsonPath("$.history[0].recorded_at") { isString() }
            jsonPath("$.history[1].price") { value(0.99) }
            jsonPath("$.history[1].unit_price") { value(null) }
        }
        assertEquals(0L, jdbc.queryForObject("select count(*) from search_metrics", Long::class.java))
    }

    @Test
    fun `unknown product is 404`() {
        mockMvc.get("/api/v1/products/${UUID.randomUUID()}/price-history").andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Product not found") }
        }
    }

    @Test
    fun `malformed id is 400`() {
        mockMvc.get("/api/v1/products/not-a-uuid/price-history").andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("id must be a UUID") }
        }
    }

    @Test
    fun `cors rejects origins that are not configured`() {
        mockMvc.options("/api/v1/products/${UUID.randomUUID()}/price-history") {
            header("Origin", "https://evil.example")
            header("Access-Control-Request-Method", "GET")
        }.andExpect {
            status { isForbidden() }
        }
    }
}
