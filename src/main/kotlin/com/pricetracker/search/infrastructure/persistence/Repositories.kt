package com.pricetracker.search.infrastructure.persistence

import com.pricetracker.search.domain.StoreName
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.EntityGraph
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

// Query methods are added by the task that first needs them (T9, T10, T11).

interface StoreProductRepository : JpaRepository<StoreProductEntity, UUID> {
    // SELECT ... FOR UPDATE: concurrent refreshes compare prices one after another, so history is never duplicated (spec 002 RF-7)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByStoreNameAndWarehouseAndExternalIdIn(
        storeName: StoreName,
        warehouse: String,
        externalIds: Collection<String>,
    ): List<StoreProductEntity>
}

interface ProductRepository : JpaRepository<ProductEntity, UUID>

interface PriceHistoryRepository : JpaRepository<PriceHistoryEntity, Long> {
    fun findByStoreProductIdOrderByRecordedAtAsc(storeProductId: UUID): List<PriceHistoryEntity>
}

interface SearchSnapshotRepository : JpaRepository<SearchSnapshotEntity, UUID> {
    // Items fetched in the same query: callers read them outside any transaction (open-in-view is off)
    @EntityGraph(attributePaths = ["items"])
    fun findByQueryTermAndPostalCodeAndStoreName(queryTerm: String, postalCode: String, storeName: StoreName): SearchSnapshotEntity?

    /** Postal codes where [storeName] answered [status] for any term: being served does not depend on the term (spec 006). */
    @Query("select distinct s.postalCode from SearchSnapshotEntity s where s.storeName = :storeName and s.status = :status")
    fun findPostalCodes(storeName: StoreName, status: SnapshotStatus): List<String>
}

interface SearchMetricRepository : JpaRepository<SearchMetricEntity, Long> {
    /** Most searched (term, postal code) pairs searched since [since]; ties go to the most recent (spec 003 RF-2). */
    @Query("select m from SearchMetricEntity m where m.lastSearchedAt >= :since order by m.searchCount desc, m.lastSearchedAt desc")
    fun findTopRecent(since: Instant, limit: Limit): List<SearchMetricEntity>

    /**
     * Terms searched since [since], most searched first across postal codes (spec 011 RF-3).
     * shortcut: counts are all-time, only the last search must be recent; keep counts per day if that skews suggestions.
     */
    @Query(
        "select m.queryTerm from SearchMetricEntity m where m.lastSearchedAt >= :since " +
            "group by m.queryTerm order by sum(m.searchCount) desc, max(m.lastSearchedAt) desc",
    )
    fun findPopularTerms(since: Instant, limit: Limit): List<String>
}
