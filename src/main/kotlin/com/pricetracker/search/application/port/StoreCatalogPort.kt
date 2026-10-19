package com.pricetracker.search.application.port

import com.pricetracker.search.domain.PostalCode
import com.pricetracker.search.domain.SearchTerm
import com.pricetracker.search.domain.StoreName
import com.pricetracker.search.domain.StoreSearchResult

/** Asks one store's scraper for one search. Never throws: failures come back as [StoreSearchResult.Unavailable]. */
fun interface StoreCatalogPort {
    fun search(store: StoreName, term: SearchTerm, postalCode: PostalCode): StoreSearchResult
}
