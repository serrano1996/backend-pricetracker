package com.pricetracker.refreshjob

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("pricetracker.refresh-job")
data class RefreshJobProperties(
    val enabled: Boolean = true,
    /** Spring cron, evaluated in Europe/Madrid (spec 003 RF-1). */
    val cron: String = "0 0 4 * * *",
    val topN: Int = 50,
    /** Only terms searched within this window are refreshed (spec 003 RF-2). */
    val recency: Duration = Duration.ofDays(30),
    val minDelay: Duration = Duration.ofSeconds(3),
    val maxDelay: Duration = Duration.ofSeconds(10),
    /** Consecutive failures after which a store is left alone for the rest of the run (spec 003 RF-6). */
    val storeFailureLimit: Int = 5,
    /** Long enough for a sleeping Render free scraper to wake up (spec 003 RF-10). */
    val readTimeout: Duration = Duration.ofSeconds(90),
) {
    /** Wake-up + every pair at its worst; the three stores of a pair run in parallel (spec 003, edge cases). */
    fun lockTtl(): Duration = readTimeout.plus(maxDelay.plus(readTimeout).multipliedBy(topN.toLong()))
}
