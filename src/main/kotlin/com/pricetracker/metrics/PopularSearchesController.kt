package com.pricetracker.metrics

import com.pricetracker.search.infrastructure.persistence.SearchMetricRepository
import org.springframework.data.domain.Limit
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration

data class PopularSearchesResponse(val terms: List<String>)

/** Search suggestions for the frontend (spec 011 RF-3, RF-4). Read-only: never counts as a search. */
@RestController
@RequestMapping("/api/v1/searches")
class PopularSearchesController(private val metrics: SearchMetricRepository, private val clock: Clock) {

    @GetMapping("/popular")
    fun popular() = PopularSearchesResponse(metrics.findPopularTerms(clock.instant().minus(WINDOW), Limit.of(LIMIT)))

    private companion object {
        val WINDOW: Duration = Duration.ofDays(30)
        const val LIMIT = 10
    }
}
