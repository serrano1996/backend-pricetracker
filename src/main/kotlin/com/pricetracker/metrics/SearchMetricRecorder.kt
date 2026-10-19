package com.pricetracker.metrics

import com.pricetracker.search.domain.PostalCode
import com.pricetracker.search.domain.SearchTerm
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Counts user searches per (term, postal code) for the nightly refresh job (spec 001 RF-11, spec 003). */
@Component
class SearchMetricRecorder(private val jdbc: JdbcTemplate, private val clock: Clock) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** One atomic upsert: concurrent searches lose no increments (plan 001 D6). Never throws. */
    fun record(term: SearchTerm, postalCode: PostalCode) {
        try {
            jdbc.update(
                """
                insert into search_metrics (query_term, postal_code, search_count, last_searched_at)
                values (?, ?, 1, ?)
                on conflict (query_term, postal_code) do update
                set search_count = search_metrics.search_count + 1,
                    last_searched_at = excluded.last_searched_at
                """,
                term.value, postalCode.value,
                // pgjdbc binds OffsetDateTime, not Instant
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
            )
        } catch (e: DataAccessException) {
            // Metrics are best effort: losing one count must never fail a search (constitution #9)
            log.warn("search metric not recorded term={} postal_code={}: {}", term.value, postalCode.value, e.javaClass.simpleName)
        }
    }
}
