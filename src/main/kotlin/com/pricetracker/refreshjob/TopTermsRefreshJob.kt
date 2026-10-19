package com.pricetracker.refreshjob

import com.pricetracker.search.application.port.LockAttempt
import com.pricetracker.search.application.port.RefreshLockPort
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

/** Nightly entry point: one instance at a time through a Redis lock (spec 003 RF-1, RF-7). */
class TopTermsRefreshJob(
    private val locks: RefreshLockPort,
    private val properties: RefreshJobProperties,
    private val refresh: () -> RefreshSummary,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${pricetracker.refresh-job.cron}", zone = "Europe/Madrid")
    fun run() {
        when (val lock = locks.tryAcquire(LOCK_KEY, properties.lockTtl())) {
            is LockAttempt.Acquired -> try {
                refresh()
            } finally {
                locks.release(lock.token)
            }
            LockAttempt.Busy -> log.info("refresh job already running elsewhere, skipping")
            LockAttempt.Unavailable -> log.warn("refresh job not run: lock store unavailable")
        }
    }

    private companion object {
        const val LOCK_KEY = "pt:lock:job:refresh-top"
    }
}
