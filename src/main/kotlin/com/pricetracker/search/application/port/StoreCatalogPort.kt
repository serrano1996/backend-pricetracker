package com.pricetracker.search.application.port

import com.pricetracker.search.domain.PostalCode
import com.pricetracker.search.domain.SearchTerm
import com.pricetracker.search.domain.StoreName
import com.pricetracker.search.domain.StoreSearchResult

interface StoreCatalogPort {
    /** Asks one store's scraper for one search. Never throws: failures come back as [StoreSearchResult.Unavailable]. */
    fun search(store: StoreName, term: SearchTerm, postalCode: PostalCode): StoreSearchResult

    /** Starts waking up scrapers that have been idle, without blocking (spec 001 RF-14). */
    fun wakeIdleScrapers()

    /** Pings one scraper and waits for it; true if it answered within its read timeout (spec 003 RF-10). */
    fun wakeAndWait(store: StoreName): Boolean
}
