package com.pricetracker.search.application

import com.pricetracker.config.SearchProperties
import com.pricetracker.search.application.port.StoreCatalogPort
import com.pricetracker.search.domain.PostalCode
import com.pricetracker.search.domain.SearchTerm
import com.pricetracker.search.domain.StoreName
import com.pricetracker.search.domain.StoreSearchResult
import com.pricetracker.search.infrastructure.persistence.SearchSnapshotEntity
import com.pricetracker.search.infrastructure.persistence.SearchSnapshotRepository
import com.pricetracker.search.infrastructure.persistence.SnapshotStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.concurrent.Executors

/** On-demand search: serve fresh snapshots from PostgreSQL, refresh stale stores in parallel (spec 001 RF-1, RF-4, RF-5, RF-8, RF-9). */
@Service
class SearchService(
    private val catalog: StoreCatalogPort,
    private val snapshots: SearchSnapshotRepository,
    private val refresh: StoreRefreshService,
    private val properties: SearchProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun search(term: SearchTerm, postalCode: PostalCode): SearchResult {
        catalog.wakeIdleScrapers()
        val freshAfter = clock.instant().minus(properties.freshnessTtl)
        val current = StoreName.entries.associateWith {
            snapshots.findByQueryTermAndPostalCodeAndStoreName(term.value, postalCode.value, it)
        }
        val stale = current.filterValues { it == null || it.fetchedAt.isBefore(freshAfter) }.keys
        val fetched = fetchInParallel(stale, term, postalCode)

        val stores = StoreName.entries.map { store ->
            val snapshot = current[store]
            when (val result = fetched[store]) {
                null -> view(snapshot!!, stale = false)
                is StoreSearchResult.Unavailable -> fallback(store, snapshot)
                else -> try {
                    view(refresh.persist(store, term, postalCode, result), stale = false)
                } catch (e: RuntimeException) {
                    // A write failure degrades this store only (constitution #9)
                    log.error("persisting store={} term={} failed", store.code, term.value, e)
                    fallback(store, snapshot)
                }
            }
        }
        if (stores.all { it.status == StoreStatus.UNAVAILABLE }) throw AllStoresUnavailableException()
        return SearchResult(term, postalCode, stores)
    }

    /** One virtual thread per store; each call is already bounded by that store's read timeout. */
    private fun fetchInParallel(stores: Set<StoreName>, term: SearchTerm, postalCode: PostalCode): Map<StoreName, StoreSearchResult> =
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            stores.associateWith { executor.submit<StoreSearchResult> { catalog.search(it, term, postalCode) } }
                .mapValues { (_, future) -> future.get() }
        }

    private fun fallback(store: StoreName, snapshot: SearchSnapshotEntity?) =
        snapshot?.let { view(it, stale = it.status == SnapshotStatus.OK) }
            ?: StoreResult(store, StoreStatus.UNAVAILABLE, stale = false, fetchedAt = null, products = emptyList())

    private fun view(snapshot: SearchSnapshotEntity, stale: Boolean) = StoreResult(
        store = snapshot.storeName,
        status = if (snapshot.status == SnapshotStatus.OK) StoreStatus.OK else StoreStatus.NOT_SERVED,
        stale = stale,
        fetchedAt = snapshot.fetchedAt,
        products = snapshot.items.map {
            ProductView(
                id = requireNotNull(it.id), externalId = it.externalId, name = it.name, price = it.currentPrice,
                priceFormat = it.priceFormat, unitPrice = it.unitPrice, unitPriceUnit = it.unitPriceUnit,
                imageUrl = it.imageUrl, category = it.category,
            )
        },
    )
}
