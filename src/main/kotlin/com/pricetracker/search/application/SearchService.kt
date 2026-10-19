package com.pricetracker.search.application

import com.pricetracker.config.SearchProperties
import com.pricetracker.search.application.port.LockAttempt
import com.pricetracker.search.application.port.RefreshLockPort
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
import java.time.Instant
import java.util.concurrent.Executors

/**
 * On-demand search: serve fresh snapshots from PostgreSQL, refresh stale stores in parallel (spec 001 RF-1, RF-4, RF-5,
 * RF-8, RF-9), one refresh per (store, postal code, term) across concurrent searches and instances (spec 002).
 */
@Service
class SearchService(
    private val catalog: StoreCatalogPort,
    private val snapshots: SearchSnapshotRepository,
    private val refresh: StoreRefreshService,
    private val locks: RefreshLockPort,
    private val properties: SearchProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun search(term: SearchTerm, postalCode: PostalCode): SearchResult {
        catalog.wakeIdleScrapers()
        val freshAfter = freshAfter()
        val current = StoreName.entries.associateWith { find(it, term, postalCode) }
        val stale = current.filterValues { it == null || it.fetchedAt.isBefore(freshAfter) }.keys

        // One virtual thread per stale store; each refresh is bounded by its scraper timeout or the lock wait
        val refreshed = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            stale.associateWith { executor.submit<StoreResult> { refreshStore(it, term, postalCode, current[it]) } }
                .mapValues { (_, future) -> future.get() }
        }

        val stores = StoreName.entries.map { refreshed[it] ?: view(current.getValue(it)!!, stale = false) }
        if (stores.all { it.status == StoreStatus.UNAVAILABLE }) throw AllStoresUnavailableException()
        return SearchResult(term, postalCode, stores)
    }

    private fun refreshStore(store: StoreName, term: SearchTerm, postalCode: PostalCode, snapshot: SearchSnapshotEntity?): StoreResult {
        val key = "pt:lock:refresh:${store.code}:${postalCode.value}:${term.value}"
        return when (val lock = locks.tryAcquire(key, properties.lockTtl)) {
            is LockAttempt.Acquired -> try {
                fetchAndPersist(store, term, postalCode, snapshot)
            } finally {
                locks.release(lock.token)
            }
            LockAttempt.Busy -> awaitOthersRefresh(store, term, postalCode, snapshot)
            LockAttempt.Unavailable -> {
                // Redis down never blocks a search; FOR UPDATE still protects the history (spec 002 RF-5, RF-7)
                log.warn("refreshing store={} term={} without lock", store.code, term.value)
                fetchAndPersist(store, term, postalCode, snapshot)
            }
        }
    }

    private fun fetchAndPersist(store: StoreName, term: SearchTerm, postalCode: PostalCode, snapshot: SearchSnapshotEntity?): StoreResult =
        when (val result = catalog.search(store, term, postalCode)) {
            is StoreSearchResult.Unavailable -> fallback(store, snapshot)
            else -> try {
                view(refresh.persist(store, term, postalCode, result), stale = false)
            } catch (e: RuntimeException) {
                // A write failure degrades this store only (constitution #9)
                log.error("persisting store={} term={} failed", store.code, term.value, e)
                fallback(store, snapshot)
            }
        }

    /** Another search holds the lock: poll the snapshot instead of calling the scraper again (spec 002 RF-3, RF-4). */
    private fun awaitOthersRefresh(store: StoreName, term: SearchTerm, postalCode: PostalCode, snapshot: SearchSnapshotEntity?): StoreResult {
        val deadline = clock.instant().plus(properties.lockWaitTimeout)
        var latest = snapshot
        while (clock.instant().isBefore(deadline)) {
            Thread.sleep(properties.lockPollInterval)
            latest = find(store, term, postalCode) ?: latest
            if (latest != null && !latest.fetchedAt.isBefore(freshAfter())) return view(latest, stale = false)
        }
        return fallback(store, latest)
    }

    private fun find(store: StoreName, term: SearchTerm, postalCode: PostalCode) =
        snapshots.findByQueryTermAndPostalCodeAndStoreName(term.value, postalCode.value, store)

    private fun freshAfter(): Instant = clock.instant().minus(properties.freshnessTtl)

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
