package com.pricetracker.search.web

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.ok
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.pricetracker.TestcontainersConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** End to end: HTTP → service → PostgreSQL, with the three scrapers stubbed by WireMock (told apart by API key). */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class SearchControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
) {
    companion object {
        private val wm = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun scrapers(registry: DynamicPropertyRegistry) {
            listOf("mercadona", "alcampo", "dia").forEach { store ->
                registry.add("pricetracker.scrapers.$store.url") { wm.baseUrl() }
                registry.add("pricetracker.scrapers.$store.api-key") { "key-$store" }
            }
            registry.add("pricetracker.cors.allowed-origins") { "https://app.example" }
        }
    }

    private fun stubProducts(store: String, status: Int, body: String) = wm.stubFor(
        get(urlPathEqualTo("/api/v1/products")).withHeader("X-API-Key", equalTo("key-$store"))
            .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)),
    )

    @BeforeTest
    @AfterTest
    fun reset() {
        wm.resetAll()
        wm.stubFor(get(urlPathEqualTo("/health")).willReturn(ok()))
        jdbc.execute("truncate store_products, price_history, search_snapshots, search_snapshot_items, search_metrics cascade")
    }

    private fun productsRequests() = wm.findAll(anyRequestedFor(urlPathEqualTo("/api/v1/products"))).size

    private fun metricCount(): Long = jdbc.queryForObject("select coalesce(sum(search_count), 0) from search_metrics", Long::class.java) ?: 0

    @Test
    fun `returns the three stores with their status and products`() {
        stubProducts(
            "mercadona", 200,
            """{"search": {"warehouse": "mad1"}, "products": [
                {"id": "10381", "name": "Leche entera", "price": 0.95, "price_format": "0.95 €/L", "image_url": "https://img/1.jpg", "category": "Leche"}]}""",
        )
        stubProducts("alcampo", 404, """{"detail": "Postal code not served by Alcampo"}""")
        stubProducts("dia", 502, """{"detail": "Dia is currently unavailable"}""")

        mockMvc.get("/api/v1/search") {
            param("term", " Leche ")
            param("postal_code", "28001")
        }.andExpect {
            status { isOk() }
            jsonPath("$.term") { value("leche") }
            jsonPath("$.postal_code") { value("28001") }
            jsonPath("$.stores.length()") { value(3) }
            jsonPath("$.stores[0].store") { value("mercadona") }
            jsonPath("$.stores[1].store") { value("alcampo") }
            jsonPath("$.stores[2].store") { value("dia") }
            jsonPath("$.stores[0].status") { value("ok") }
            jsonPath("$.stores[0].stale") { value(false) }
            jsonPath("$.stores[0].fetched_at") { isString() }
            jsonPath("$.stores[0].reference_postal_code") { value(null) }
            jsonPath("$.stores[0].reference_distance_km") { value(null) }
            jsonPath("$.stores[0].products[0].id") { isString() }
            jsonPath("$.stores[0].products[0].external_id") { value("10381") }
            jsonPath("$.stores[0].products[0].name") { value("Leche entera") }
            jsonPath("$.stores[0].products[0].price") { value(0.95) }
            jsonPath("$.stores[0].products[0].price_format") { value("0.95 €/L") }
            jsonPath("$.stores[0].products[0].unit_price") { value(0.95) }
            jsonPath("$.stores[0].products[0].unit_price_unit") { value("L") }
            jsonPath("$.stores[0].products[0].image_url") { value("https://img/1.jpg") }
            jsonPath("$.stores[0].products[0].category") { value("Leche") }
            jsonPath("$.stores[1].status") { value("not_served") }
            jsonPath("$.stores[1].products") { isEmpty() }
            jsonPath("$.stores[2].status") { value("unavailable") }
            jsonPath("$.stores[2].fetched_at") { value(null) }
            jsonPath("$.comparison.length()") { value(1) }
            jsonPath("$.comparison[0].type") { value("Leche entera") }
            jsonPath("$.comparison[0].unit") { value("L") }
            jsonPath("$.comparison[0].offers.length()") { value(1) }
            jsonPath("$.comparison[0].offers[0].store") { value("mercadona") }
            jsonPath("$.comparison[0].offers[0].product_id") { isString() }
            jsonPath("$.comparison[0].offers[0].name") { value("Leche entera") }
            jsonPath("$.comparison[0].offers[0].unit_price") { value(0.95) }
            jsonPath("$.comparison[0].offers[0].price") { value(0.95) }
        }
        assertEquals(1L, metricCount())
    }

    @Test
    fun `stores limits which scrapers are called and which stores are returned`() {
        listOf("mercadona", "alcampo", "dia").forEach {
            stubProducts(it, 200, """{"search": {"warehouse": "w"}, "products": []}""")
        }

        mockMvc.get("/api/v1/search") {
            param("term", "leche")
            param("postal_code", "28001")
            param("stores", "dia,mercadona")
        }.andExpect {
            status { isOk() }
            jsonPath("$.stores.length()") { value(2) }
            jsonPath("$.stores[0].store") { value("mercadona") }
            jsonPath("$.stores[1].store") { value("dia") }
        }
        assertEquals(0, wm.findAll(anyRequestedFor(urlPathEqualTo("/api/v1/products")).withHeader("X-API-Key", equalTo("key-alcampo"))).size)
        assertEquals(2, productsRequests())
    }

    @Test
    fun `unknown store is 400 without calling scrapers or counting the search`() {
        mockMvc.get("/api/v1/search") {
            param("term", "leche")
            param("postal_code", "28001")
            param("stores", "dia,lidl")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("stores must be a comma-separated list of: mercadona, alcampo, dia") }
        }
        assertEquals(0, productsRequests())
        assertEquals(0L, metricCount())
    }

    @Test
    fun `invalid postal code is 400 without calling scrapers or counting the search`() {
        mockMvc.get("/api/v1/search") {
            param("term", "leche")
            param("postal_code", "2800")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("postal_code must be 5 digits") }
        }
        assertEquals(0, productsRequests())
        assertEquals(0L, metricCount())
    }

    @Test
    fun `missing or too long term is 400`() {
        mockMvc.get("/api/v1/search") { param("postal_code", "28001") }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { isString() }
        }
        mockMvc.get("/api/v1/search") {
            param("term", "a".repeat(101))
            param("postal_code", "28001")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("term must be 1 to 100 characters") }
        }
        assertEquals(0, productsRequests())
    }

    @Test
    fun `all stores unavailable with no data is 502`() {
        listOf("mercadona", "alcampo", "dia").forEach { stubProducts(it, 502, """{"detail": "down"}""") }

        mockMvc.get("/api/v1/search") {
            param("term", "leche")
            param("postal_code", "28001")
        }.andExpect {
            status { isBadGateway() }
            jsonPath("$.detail") { value("All stores are unavailable") }
        }
    }

    @Test
    fun `cors allows the configured origin only`() {
        mockMvc.options("/api/v1/search") {
            header("Origin", "https://app.example")
            header("Access-Control-Request-Method", "GET")
        }.andExpect {
            status { isOk() }
            header { string("Access-Control-Allow-Origin", "https://app.example") }
        }
        mockMvc.options("/api/v1/search") {
            header("Origin", "https://evil.example")
            header("Access-Control-Request-Method", "GET")
        }.andExpect {
            status { isForbidden() }
        }
    }
}
