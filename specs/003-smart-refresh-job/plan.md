# Plan 003 — Job nocturno de refresco

Basado en [spec.md](spec.md) y en las specs [001](../001-backend-search-mvp/plan.md) y [002](../002-request-collapsing/plan.md).

## 1. Módulos

```
refreshjob/
├── TopTermsRefreshJob.kt        # @Scheduled(cron, zone) → adquiere candado → TopTermsRefresher
├── TopTermsRefresher.kt         # selección, bucle secuencial, jitter, contador de fallos, resumen (RF-2..RF-6, RF-8)
├── RefreshJobProperties.kt      # @ConfigurationProperties refresh-job.*
└── Sleeper.kt                   # interfaz para dormir; en tests no duerme
search/application/StoreRefreshService.kt   # (reutilizado) mismo camino que las búsquedas
metrics/SearchMetricRepository.kt           # (añadido) findTopRecent(topN, since)
```

`@EnableScheduling` condicionado a `refresh-job.enabled` (RF-9).

## 2. Decisiones de diseño

### D1 — Reutilizar el refresco de búsqueda, no un camino paralelo
**Elegido:** el job llama al mismo servicio que `SearchService` usa para refrescar una tienda (con candado de spec 002).
**Descartado:** un refresco propio del job. Motivo: dos caminos que escriben precios e histórico divergen con el tiempo.

### D2 — Secuencial con jitter
**Elegido:** un par cada vez, retraso aleatorio entre pares; las tres tiendas de un par sí en paralelo (cada una golpea un scraper distinto).
**Descartado:** paralelismo entre pares. Motivo: el objetivo es parecer tráfico humano y no disparar los rate limiters de los scrapers.

### D3 — Candado Redis en vez de ShedLock
**Elegido:** `RefreshLockPort` de la spec 002 con clave fija.
**Descartado:** ShedLock. Motivo: ya tenemos el candado; ShedLock añade dependencia y tabla.

### D3b — Timeout largo y candados del job
Las llamadas del job pasan `REFRESH_JOB_READ_TIMEOUT` (90 s) al cliente; los candados por término de spec 002 se adquieren con un TTL mayor (100 s) en el job.

### D4 — `Random` y `Sleeper` inyectables
Para testear RF-4 sin esperar segundos reales.

## 3. Estrategia de test

- Unitarios de `TopTermsRefresher` con fakes: orden, omisión de frescos, jitter en rango, corte por fallos consecutivos, resumen.
- Integración: `search_metrics` sembrada + WireMock → snapshots refrescados y `search_count` sin cambios.
- Candado: dos invocaciones concurrentes de `TopTermsRefreshJob.run()` → una procesa.
