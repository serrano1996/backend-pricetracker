package com.pricetracker.search.application

import com.pricetracker.search.domain.PostalCode
import com.pricetracker.search.domain.SearchTerm
import com.pricetracker.search.domain.StoreName
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Outcome of a search across the three stores, in [StoreName] order (spec 001 RF-1). */
data class SearchResult(val term: SearchTerm, val postalCode: PostalCode, val stores: List<StoreResult>)

enum class StoreStatus { OK, NOT_SERVED, UNAVAILABLE }

data class StoreResult(
    val store: StoreName,
    val status: StoreStatus,
    /** True only for OK data served from an expired snapshot after the scraper failed (spec 001 RF-8). */
    val stale: Boolean,
    val fetchedAt: Instant?,
    val products: List<ProductView>,
)

data class ProductView(
    val id: UUID,
    val externalId: String,
    val name: String,
    val price: BigDecimal,
    val priceFormat: String?,
    val unitPrice: BigDecimal?,
    val unitPriceUnit: String?,
    val imageUrl: String?,
    val category: String?,
    /** Product type (spec 004), null when none matched. */
    val productTypeId: UUID?,
)

/** No store answered and none had data to fall back on; the web layer answers 502 (spec 001 RF-9). */
class AllStoresUnavailableException : RuntimeException("All stores are unavailable")
