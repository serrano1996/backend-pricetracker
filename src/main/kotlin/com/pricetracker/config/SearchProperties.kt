package com.pricetracker.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("pricetracker.search")
data class SearchProperties(
    /** Snapshots younger than this are served from PostgreSQL (spec 001 RF-4). */
    val freshnessTtl: Duration = Duration.ofHours(12),
    /** Must exceed the scrapers' read timeout (25 s), or a slow refresh loses its lock midway (spec 002). */
    val lockTtl: Duration = Duration.ofSeconds(30),
    /** How often a search waiting on another's refresh re-reads the snapshot (spec 002 RF-3). */
    val lockPollInterval: Duration = Duration.ofMillis(250),
    /** How long a search waits on another's refresh before serving what it has (spec 002 RF-3, RF-4). */
    val lockWaitTimeout: Duration = Duration.ofSeconds(15),
    /** Scraper calls allowed per window across all instances; past it, searches serve what is stored. */
    val scraperCallBudget: Int = 300,
    val scraperCallBudgetWindow: Duration = Duration.ofHours(1),
)
