package com.pricetracker.search.domain

import java.math.BigDecimal

/** Declaration order is the response order (spec 001 RF-1); `code` is the value stored in the database. */
enum class StoreName(val code: String) {
    MERCADONA("mercadona"),
    ALCAMPO("alcampo"),
    DIA("dia"),
}

/** One product as returned by a scraper. */
data class StoreOffer(
    val externalId: String,
    val name: String,
    val price: BigDecimal,
    val priceFormat: String?,
    val imageUrl: String,
    val category: String,
)

/** Outcome of asking one scraper for one search (plan 001 D8). */
sealed interface StoreSearchResult {
    data class Found(val warehouse: String, val offers: List<StoreOffer>) : StoreSearchResult
    data object NotServed : StoreSearchResult
    data class Unavailable(val reason: String) : StoreSearchResult
}
