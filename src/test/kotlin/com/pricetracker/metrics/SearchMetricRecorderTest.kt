package com.pricetracker.metrics

import com.pricetracker.TestcontainersConfiguration
import com.pricetracker.search.domain.PostalCode
import com.pricetracker.search.domain.SearchTerm
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Commits for real (concurrency needs separate connections), so it cleans up around each test. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@ExtendWith(OutputCaptureExtension::class)
class SearchMetricRecorderTest(
    @Autowired private val recorder: SearchMetricRecorder,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val cp = PostalCode.of("28001")

    @BeforeTest
    @AfterTest
    fun clean() {
        jdbc.execute("truncate search_metrics")
    }

    private fun count(term: String, postalCode: String = "28001") = jdbc.queryForObject(
        "select search_count from search_metrics where query_term = ? and postal_code = ?", Long::class.java, term, postalCode,
    )

    @Test
    fun `first search creates the row, later ones increment it`() {
        recorder.record(SearchTerm.of("Leche"), cp)
        recorder.record(SearchTerm.of(" leche "), cp)

        assertEquals(2L, count("leche"))
    }

    @Test
    fun `counts are kept per term and postal code`() {
        recorder.record(SearchTerm.of("leche"), cp)
        recorder.record(SearchTerm.of("leche"), PostalCode.of("08001"))
        recorder.record(SearchTerm.of("pan"), cp)

        assertEquals(1L, count("leche"))
        assertEquals(1L, count("leche", "08001"))
        assertEquals(1L, count("pan"))
    }

    @Test
    fun `concurrent searches lose no increments`() {
        (1..20).map { Thread.ofVirtual().start { recorder.record(SearchTerm.of("leche"), cp) } }.forEach { it.join() }

        assertEquals(20L, count("leche"))
    }

    @Test
    fun `updates last searched time`() {
        val at = Instant.parse("2026-10-19T08:00:00Z")
        SearchMetricRecorder(jdbc, Clock.fixed(at, ZoneOffset.UTC)).record(SearchTerm.of("leche"), cp)

        val stored = jdbc.queryForObject("select last_searched_at from search_metrics", java.sql.Timestamp::class.java)
        assertEquals(at, stored?.toInstant())
    }

    @Test
    fun `a database failure is logged and never thrown`(output: CapturedOutput) {
        val broken = JdbcTemplate(DriverManagerDataSource("jdbc:postgresql://localhost:1/none", "u", "p"))

        SearchMetricRecorder(broken, Clock.systemUTC()).record(SearchTerm.of("leche"), cp)

        assertTrue("search metric not recorded" in output.all)
    }
}
