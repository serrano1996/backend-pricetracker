package com.pricetracker.search.infrastructure.scraper

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties("pricetracker.scrapers")
data class ScraperProperties(
    val mercadona: Store,
    val alcampo: Store,
    val dia: Store,
    /** A scraper idle for longer than this gets a background /health ping (spec 001 RF-14). */
    val warmupIdle: Duration = Duration.ofMinutes(10),
) {
    data class Store(
        val url: URI,
        val apiKey: String,
        val connectTimeout: Duration = Duration.ofSeconds(5),
        // Render free scrapers may be asleep; past this the store is served stale/unavailable (spec 001 RF-8).
        val readTimeout: Duration = Duration.ofSeconds(25),
    ) {
        // Masked so the key never reaches logs or actuator output (spec 001 RF-12).
        override fun toString() =
            "Store(url=$url, apiKey=***, connectTimeout=$connectTimeout, readTimeout=$readTimeout)"
    }
}
