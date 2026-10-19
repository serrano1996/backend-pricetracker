package com.pricetracker.search.infrastructure.scraper

import com.fasterxml.jackson.annotation.JsonProperty
import com.pricetracker.search.application.port.StoreCatalogPort
import com.pricetracker.search.domain.PostalCode
import com.pricetracker.search.domain.SearchTerm
import com.pricetracker.search.domain.StoreName
import com.pricetracker.search.domain.StoreOffer
import com.pricetracker.search.domain.StoreSearchResult
import org.slf4j.LoggerFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.net.http.HttpClient

/** HTTP adapter for the three scrapers' shared `GET /api/v1/products` contract (docs/architecture.md §2). */
@Component
class ScraperClient(properties: ScraperProperties) : StoreCatalogPort {

    private val log = LoggerFactory.getLogger(javaClass)

    private val clients: Map<StoreName, RestClient> = mapOf(
        StoreName.MERCADONA to restClient(properties.mercadona),
        StoreName.ALCAMPO to restClient(properties.alcampo),
        StoreName.DIA to restClient(properties.dia),
    )

    override fun search(store: StoreName, term: SearchTerm, postalCode: PostalCode): StoreSearchResult {
        val started = System.nanoTime()
        val result = try {
            clients.getValue(store).get()
                // Template variables are strictly encoded: a '+' in the term stays a '+'
                .uri(PRODUCTS_URI, postalCode.value, term.value, PAGE_SIZE)
                .exchange { _, response ->
                    val status = response.statusCode.value()
                    when {
                        status == 200 -> response.bodyTo(ProductsResponse::class.java)?.toResult()
                            ?: StoreSearchResult.Unavailable("empty body")
                        status == 404 && response.bodyTo(ErrorResponse::class.java)?.detail
                            ?.startsWith(NOT_SERVED_DETAIL) == true -> StoreSearchResult.NotServed
                        else -> StoreSearchResult.Unavailable("HTTP $status")
                    }
                }
        } catch (e: RuntimeException) {
            // Timeouts, refused connections and unreadable bodies degrade this store only (constitution #9)
            StoreSearchResult.Unavailable(e.javaClass.simpleName)
        }
        logCall(store, term, postalCode, result, (System.nanoTime() - started) / 1_000_000)
        return result
    }

    private fun logCall(store: StoreName, term: SearchTerm, postalCode: PostalCode, result: StoreSearchResult, millis: Long) {
        val outcome = when (result) {
            is StoreSearchResult.Found -> "found(${result.offers.size})"
            StoreSearchResult.NotServed -> "not_served"
            is StoreSearchResult.Unavailable -> "unavailable(${result.reason})"
        }
        val message = "scraper call store={} term={} postal_code={} outcome={} duration_ms={}"
        when {
            result == StoreSearchResult.Unavailable("HTTP 401") ->
                log.error("$message - check SCRAPER_${store.name}_API_KEY", store.code, term.value, postalCode.value, outcome, millis)
            result is StoreSearchResult.Unavailable ->
                log.warn(message, store.code, term.value, postalCode.value, outcome, millis)
            else -> log.info(message, store.code, term.value, postalCode.value, outcome, millis)
        }
    }

    private fun restClient(store: ScraperProperties.Store): RestClient {
        val http = HttpClient.newBuilder().connectTimeout(store.connectTimeout).build()
        return RestClient.builder()
            .baseUrl(store.url.toString())
            .defaultHeader("X-API-Key", store.apiKey)
            .requestFactory(JdkClientHttpRequestFactory(http).apply { setReadTimeout(store.readTimeout) })
            .build()
    }

    internal data class ProductsResponse(val search: SearchMetadata, val products: List<Product>) {
        fun toResult() = StoreSearchResult.Found(
            search.warehouse,
            products.map { StoreOffer(it.id, it.name, it.price, it.priceFormat, it.imageUrl, it.category) },
        )
    }

    internal data class SearchMetadata(val warehouse: String)

    internal data class Product(
        val id: String,
        val name: String,
        // Read as BigDecimal straight from the JSON text: no float rounding (constitution #5)
        val price: BigDecimal,
        @JsonProperty("price_format") val priceFormat: String?,
        @JsonProperty("image_url") val imageUrl: String,
        val category: String,
    )

    internal data class ErrorResponse(val detail: String?)

    private companion object {
        const val PRODUCTS_URI = "/api/v1/products?postal_code={postalCode}&term={term}&page=1&page_size={pageSize}"
        const val PAGE_SIZE = 50
        const val NOT_SERVED_DETAIL = "Postal code not served"
    }
}
