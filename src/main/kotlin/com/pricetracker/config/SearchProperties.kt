package com.pricetracker.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("pricetracker.search")
data class SearchProperties(
    /** Snapshots younger than this are served from PostgreSQL (spec 001 RF-4). */
    val freshnessTtl: Duration = Duration.ofHours(12),
)
