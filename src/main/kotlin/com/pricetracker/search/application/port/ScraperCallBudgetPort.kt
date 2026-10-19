package com.pricetracker.search.application.port

/** Global cap on scraper calls across instances, so no traffic can run up compute costs. */
fun interface ScraperCallBudgetPort {
    /** Spends one call; false once the current window is used up. Never throws. */
    fun tryConsume(): Boolean
}
