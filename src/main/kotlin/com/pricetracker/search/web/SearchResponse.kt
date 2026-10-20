package com.pricetracker.search.web

import com.pricetracker.search.application.ProductView
import com.pricetracker.search.application.SearchResult
import com.pricetracker.search.application.StoreResult
import com.pricetracker.search.application.TypeComparison
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// Public contract of GET /api/v1/search (spec 001 "Formato de respuesta"), snake_case like the scrapers.

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class SearchResponse(
    val term: String,
    val postalCode: String,
    val stores: List<StoreResponse>,
    val comparison: List<ComparisonResponse>,
) {
    companion object {
        fun from(result: SearchResult, comparison: List<TypeComparison>) = SearchResponse(
            term = result.term.value,
            postalCode = result.postalCode.value,
            stores = result.stores.map(StoreResponse::from),
            comparison = comparison.map(ComparisonResponse::from),
        )
    }
}

/** Cheapest offer per store for one product type, cheapest first (spec 004 RF-4). */
data class ComparisonResponse(val type: String, val unit: String, val offers: List<OfferResponse>) {
    companion object {
        fun from(c: TypeComparison) = ComparisonResponse(
            type = c.type.name,
            unit = c.type.unit,
            offers = c.offers.map {
                OfferResponse(it.store.code, it.product.id, it.product.name, requireNotNull(it.product.unitPrice), it.product.price)
            },
        )
    }
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OfferResponse(val store: String, val productId: UUID, val name: String, val unitPrice: BigDecimal, val price: BigDecimal)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class StoreResponse(
    val store: String,
    /** ok | not_served | unavailable */
    val status: String,
    val stale: Boolean,
    val fetchedAt: Instant?,
    val products: List<ProductResponse>,
    /** Postal code the prices come from when the store does not serve the searched one, else null (spec 006 RF-5). */
    val referencePostalCode: String?,
    val referenceDistanceKm: Int?,
) {
    companion object {
        fun from(store: StoreResult) = StoreResponse(
            store = store.store.code,
            status = store.status.name.lowercase(),
            stale = store.stale,
            fetchedAt = store.fetchedAt,
            products = store.products.map(ProductResponse::from),
            referencePostalCode = store.reference?.postalCode,
            referenceDistanceKm = store.reference?.distanceKm,
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
