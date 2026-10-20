package com.pricetracker.metrics

import com.pricetracker.TestcontainersConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class PopularSearchesControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
) {
    @BeforeTest
    @AfterTest
    fun clean() {
        jdbc.execute("truncate search_metrics")
    }

    private fun searched(term: String, postalCode: String, count: Int, daysAgo: Int = 0) = jdbc.update(
        "insert into search_metrics (query_term, postal_code, search_count, last_searched_at) values (?, ?, ?, now() - make_interval(days => ?))",
        term, postalCode, count, daysAgo,
    )

    @Test
    fun `most searched terms of the last 30 days, summed across postal codes`() {
        searched("leche", "28001", 3)
        searched("leche", "50500", 3)
        searched("aceite", "28001", 5)
        searched("arroz", "28001", 1)
        searched("pan de molde", "28001", 99, daysAgo = 31)

        mockMvc.get("/api/v1/searches/popular").andExpect {
            status { isOk() }
            jsonPath("$.terms.length()") { value(3) }
            jsonPath("$.terms[0]") { value("leche") }
            jsonPath("$.terms[1]") { value("aceite") }
            jsonPath("$.terms[2]") { value("arroz") }
        }
    }

    @Test
    fun `at most 10 terms and never counted as a search`() {
        (1..12).forEach { searched("term$it", "28001", it) }

        mockMvc.get("/api/v1/searches/popular").andExpect {
            jsonPath("$.terms.length()") { value(10) }
            jsonPath("$.terms[0]") { value("term12") }
        }
        assertEquals(12, jdbc.queryForObject("select count(*) from search_metrics", Int::class.java))
    }
}
