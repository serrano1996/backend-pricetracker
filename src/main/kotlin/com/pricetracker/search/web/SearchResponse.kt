package com.pricetracker.search.web

import com.pricetracker.search.application.ProductView
import com.pricetracker.search.application.SearchResult
import com.pricetracker.search.application.StoreResult
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// Public contract of GET /api/v1/search (spec 001 "Formato de respuesta"), snake_case like the scrapers.

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class SearchResponse(val term: String, val postalCode: String, val stores: List<StoreResponse>) {
    companion object {
        fun from(result: SearchResult) = SearchResponse(
            term = result.term.value,
            postalCode = result.postalCode.value,
            stores = result.stores.map(StoreResponse::from),
        )
    }
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class StoreResponse(
    val store: String,
    /** ok | not_served | unavailable */
    val status: String,
    val stale: Boolean,
    val fetchedAt: Instant?,
    val products: List<ProductResponse>,
) {
    companion object {
        fun from(store: StoreResult) = StoreResponse(
            store = store.store.code,
            status = store.status.name.lowercase(),
            stale = store.stale,
            fetchedAt = store.fetchedAt,
            products = store.products.map(ProductResponse::from),
        )
    }
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ProductResponse(
    val id: UUID,
    val externalId: String,
    val name: String,
    val price: BigDecimal,
    val priceFormat: String?,
    val unitPrice: BigDecimal?,
    val unitPriceUnit: String?,
    val imageUrl: String?,
    val category: String?,
) {
    companion object {
        fun from(p: ProductView) = ProductResponse(
            p.id, p.externalId, p.name, p.price, p.priceFormat, p.unitPrice, p.unitPriceUnit, p.imageUrl, p.category,
        )
    }
}

/** Error body, same shape as the scrapers': {"detail": "..."}. */
data class ErrorResponse(val detail: String)
