package com.pricetracker.search.web

import com.pricetracker.metrics.SearchMetricRecorder
import com.pricetracker.search.application.PriceComparator
import com.pricetracker.search.application.ProductTypeCatalog
import com.pricetracker.search.application.SearchService
import com.pricetracker.search.domain.PostalCode
import com.pricetracker.search.domain.SearchTerm
import com.pricetracker.search.domain.StoreName
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class SearchController(
    private val searchService: SearchService,
    private val metrics: SearchMetricRecorder,
    private val productTypes: ProductTypeCatalog,
) {

    @GetMapping("/search")
    fun search(
        @RequestParam term: String,
        @RequestParam("postal_code") postalCode: String,
        // Comma-separated, split by Spring: ?stores=mercadona,dia
        @RequestParam(required = false) stores: List<String>?,
    ): SearchResponse {
        // Validation first: invalid input never reaches a scraper or the metrics (spec 001 RF-2)
        val searchTerm = SearchTerm.of(term)
        val code = PostalCode.of(postalCode)
        val selected = StoreName.selection(stores)
        // Recorded here, not in SearchService, so the nightly job (spec 003) never counts as a search
        metrics.record(searchTerm, code)
        val result = searchService.search(searchTerm, code, only = selected)
        return SearchResponse.from(result, PriceComparator.compare(result, productTypes.types), productTypes.types)
    }
}
