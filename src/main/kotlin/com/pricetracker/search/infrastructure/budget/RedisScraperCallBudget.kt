package com.pricetracker.search.infrastructure.budget

import com.pricetracker.config.SearchProperties
import com.pricetracker.search.application.port.ScraperCallBudgetPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import java.time.Duration

/** Fixed-window counter in Redis: the first call of a window starts it, shared by every instance. */
@Component
class RedisScraperCallBudget(
    private val redis: StringRedisTemplate,
    private val limit: Int,
    private val window: Duration,
    private val key: String = KEY,
) : ScraperCallBudgetPort {

    @Autowired
    constructor(redis: StringRedisTemplate, properties: SearchProperties) :
        this(redis, properties.scraperCallBudget, properties.scraperCallBudgetWindow)

    private val log = LoggerFactory.getLogger(javaClass)

    override fun tryConsume(): Boolean = try {
        val used = redis.execute(CONSUME, listOf(key), window.toMillis().toString())
        used <= limit
    } catch (e: RuntimeException) {
        // Redis down never blocks a search, as with the refresh lock (spec 002 RF-5)
        log.warn("scraper call budget unavailable: {}", e.javaClass.simpleName)
        true
    }

    private companion object {
        const val KEY = "pt:budget:scraper-calls"

        // Atomic, so the counter always gets its TTL and a crash cannot leave it blocking forever
        val CONSUME: RedisScript<Long> = RedisScript.of(
            "local n = redis.call('incr', KEYS[1]) if n == 1 then redis.call('pexpire', KEYS[1], ARGV[1]) end return n",
            Long::class.java,
        )
    }
}
