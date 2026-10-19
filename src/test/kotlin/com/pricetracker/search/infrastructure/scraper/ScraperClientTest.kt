package com.pricetracker.search.infrastructure.scraper

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import com.pricetracker.search.domain.PostalCode
import com.pricetracker.search.domain.SearchTerm
import com.pricetracker.search.domain.StoreName
import com.pricetracker.search.domain.StoreOffer
import com.pricetracker.search.domain.StoreSearchResult
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.math.BigDecimal
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

@ExtendWith(OutputCaptureExtension::class)
class ScraperClientTest {

    companion object {
        @JvmField
        @RegisterExtension
        val wm: WireMockExtension = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build()

        const val API_KEY = "s3cr3t-alcampo-key"
        const val PATH = "/api/v1/products"
    }

    private fun client(): ScraperClient {
        val store = ScraperProperties.Store(
            url = URI(wm.baseUrl()), apiKey = API_KEY,
            connectTimeout = Duration.ofSeconds(1), readTimeout = Duration.ofMillis(500),
        )
        return ScraperClient(ScraperProperties(mercadona = store, alcampo = store, dia = store))
    }

    private fun search(term: String = "Leche entera") =
        client().search(StoreName.ALCAMPO, SearchTerm.of(term), PostalCode.of("28001"))

    private fun stubStatus(status: Int, detail: String) =
        wm.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody("""{"detail":"$detail"}""")))

    @Test
    fun `maps a 200 response to found offers keeping order and exact prices`() {
        wm.stubFor(
            get(urlPathEqualTo(PATH)).willReturn(
                okJson(
                    """
                    {"search": {"postal_code": "28001", "term": "leche entera", "warehouse": "mad1", "strategy_used": "api",
                                "scraped_at": "2026-10-19T08:00:00Z", "total_results": 2, "page": 1, "page_size": 50, "total_pages": 1},
                     "products": [
                       {"id": "2", "name": "Leche B", "price": 0.95, "price_format": "0.95 €/L", "image_url": "https://img/2.jpg", "category": "Leche"},
                       {"id": "1", "name": "Leche A", "price": 1.1, "price_format": null, "image_url": "https://img/1.jpg", "category": "Leche"}
                     ]}
                    """,
                ),
            ),
        )

        val result = search()

        assertEquals(
            StoreSearchResult.Found(
                "mad1",
                listOf(
                    StoreOffer("2", "Leche B", BigDecimal("0.95"), "0.95 €/L", "https://img/2.jpg", "Leche"),
                    StoreOffer("1", "Leche A", BigDecimal("1.1"), null, "https://img/1.jpg", "Leche"),
                ),
            ),
            result,
        )
    }

    @Test
    fun `sends api key, normalized term, postal code and first page`() {
        wm.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""{"search": {"warehouse": "mad1"}, "products": []}""")))

        search("  Plátano  de Canarias ")

        wm.verify(
            getRequestedFor(urlPathEqualTo(PATH))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .withQueryParam("term", equalTo("plátano de canarias"))
                .withQueryParam("postal_code", equalTo("28001"))
                .withQueryParam("page", equalTo("1"))
                .withQueryParam("page_size", equalTo("50")),
        )
    }

    @Test
    fun `encodes reserved characters in the term`() {
        wm.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""{"search": {"warehouse": "mad1"}, "products": []}""")))

        search("c+c & co")

        wm.verify(getRequestedFor(urlPathEqualTo(PATH)).withQueryParam("term", equalTo("c+c & co")))
    }

    @Test
    fun `empty product list is found, not a failure`() {
        wm.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""{"search": {"warehouse": "mad1"}, "products": []}""")))

        assertEquals(StoreSearchResult.Found("mad1", emptyList()), search())
    }

    @Test
    fun `404 postal code not served maps to not served`() {
        stubStatus(404, "Postal code not served by Alcampo")

        assertEquals(StoreSearchResult.NotServed, search())
    }

    @Test
    fun `any other 404, 401, 5xx or malformed body maps to unavailable`() {
        listOf(404 to "Page out of range", 401 to "Invalid or missing API key", 502 to "Alcampo is unavailable", 500 to "boom")
            .forEach { (status, detail) ->
                stubStatus(status, detail)
                assertIs<StoreSearchResult.Unavailable>(search(), "status $status")
            }
        wm.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""{"unexpected": true}""")))
        assertIs<StoreSearchResult.Unavailable>(search(), "malformed body")
    }

    @Test
    fun `read timeout maps to unavailable`() {
        wm.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""{"search": {"warehouse": "mad1"}, "products": []}""").withFixedDelay(1500)))

        assertIs<StoreSearchResult.Unavailable>(search())
    }

    @Test
    fun `connection refused maps to unavailable`() {
        val store = ScraperProperties.Store(url = URI("http://localhost:1"), apiKey = API_KEY)
        val client = ScraperClient(ScraperProperties(store, store, store))

        assertIs<StoreSearchResult.Unavailable>(client.search(StoreName.DIA, SearchTerm.of("leche"), PostalCode.of("28001")))
    }

    @Test
    fun `never logs the api key`(output: CapturedOutput) {
        stubStatus(401, "Invalid or missing API key")

        search()

        assertFalse(API_KEY in output.all)
    }
}
