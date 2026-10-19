package com.pricetracker.search.infrastructure.persistence

import com.pricetracker.TestcontainersConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.Limit
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Transactional
class SearchMetricRepositoryTest(@Autowired private val metrics: SearchMetricRepository) {

    private val now = Instant.parse("2026-10-19T04:00:00Z")

    private fun metric(term: String, count: Long, daysAgo: Long, cp: String = "28001") =
        metrics.save(SearchMetricEntity(term, cp, count, now.minus(Duration.ofDays(daysAgo))))

    @Test
    fun `returns the most searched recent pairs, most searched first`() {
        metric("pan", 5, daysAgo = 1)
        metric("leche", 10, daysAgo = 2)
        metric("leche", 7, daysAgo = 1, cp = "08001")
        metric("arroz", 1, daysAgo = 3)
        metric("turron", 100, daysAgo = 40) // outside the 30 day window

        val top = metrics.findTopRecent(now.minus(Duration.ofDays(30)), Limit.of(3))

        assertEquals(listOf("leche/28001", "leche/08001", "pan/28001"), top.map { "${it.queryTerm}/${it.postalCode}" })
    }

    @Test
    fun `ties are broken by the most recent search`() {
        metric("pan", 5, daysAgo = 3)
        metric("leche", 5, daysAgo = 1)

        val top = metrics.findTopRecent(now.minus(Duration.ofDays(30)), Limit.of(10))

        assertEquals(listOf("leche", "pan"), top.map { it.queryTerm })
    }
}
