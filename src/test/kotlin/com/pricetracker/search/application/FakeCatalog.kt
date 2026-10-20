package com.pricetracker.search.application

import com.pricetracker.search.application.port.StoreCatalogPort
import com.pricetracker.search.domain.PostalCode
import com.pricetracker.search.domain.SearchTerm
import com.pricetracker.search.domain.StoreName
import com.pricetracker.search.domain.StoreOffer
import com.pricetracker.search.domain.StoreSearchResult
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Scrapers stand-in: one product per store unless [byPostalCode] or [responses] says otherwise; records every call. */
class FakeCatalog : StoreCatalogPort {
    val responses = ConcurrentHashMap<StoreName, StoreSearchResult>()
    val byPostalCode = ConcurrentHashMap<Pair<StoreName, String>, StoreSearchResult>()
    val calls = ConcurrentLinkedQueue<StoreName>()
    val searches = ConcurrentLinkedQueue<Pair<StoreName, String>>()
    val postalCodes = ConcurrentLinkedQueue<Pair<StoreName, String>>()
    val wakeups = AtomicInteger()
    val awakened = ConcurrentLinkedQueue<StoreName>()
    val wakeFails = ConcurrentHashMap.newKeySet<StoreName>()
    var delay: Duration = Duration.ZERO

    override fun search(store: StoreName, term: SearchTerm, postalCode: PostalCode): StoreSearchResult {
        calls += store
        searches += store to term.value
        postalCodes += store to postalCode.value
        Thread.sleep(delay)
        return byPostalCode[store to postalCode.value] ?: responses[store] ?: StoreSearchResult.Found(
            "w-${store.code}",
            listOf(StoreOffer("${store.code}-1", "Leche ${store.code}", BigDecimal("0.95"), "0.95 €/L", "https://img", "Leche")),
        )
    }

    override fun wakeIdleScrapers() {
        wakeups.incrementAndGet()
    }

    override fun wakeAndWait(store: StoreName): Boolean {
        awakened += store
        return store !in wakeFails
    }
}
