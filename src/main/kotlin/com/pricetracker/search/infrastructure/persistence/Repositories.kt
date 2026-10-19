package com.pricetracker.search.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

// Query methods are added by the task that first needs them (T9, T10, T11).

interface StoreProductRepository : JpaRepository<StoreProductEntity, UUID>

interface PriceHistoryRepository : JpaRepository<PriceHistoryEntity, Long>

interface SearchSnapshotRepository : JpaRepository<SearchSnapshotEntity, UUID>

interface SearchMetricRepository : JpaRepository<SearchMetricEntity, Long>
