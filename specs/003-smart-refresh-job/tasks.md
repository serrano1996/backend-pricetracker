# Tasks 003 — Job nocturno de refresco

Desglose de [plan.md](plan.md). Requiere las specs 001 y 002 completadas.

- [x] **T1 — `RefreshJobProperties` + `findTopRecent`**
  RF: RF-1, RF-2, RF-9.
  Hecho cuando: test de repositorio devuelve el top N ordenado y excluye términos fuera de la ventana de recencia.

- [x] **T2 — `TopTermsRefresher`**
  Bucle secuencial, omisión de tiendas frescas, jitter con `Random` + `Sleeper`, corte por fallos consecutivos, resumen en log, sin tocar `search_metrics`.
  Depende: T1. RF: RF-3..RF-6, RF-8.
  Hecho cuando: tests unitarios de plan.md §3.

- [x] **T2b — Warm-up previo y timeout largo**
  Depende: T2. RF: RF-10.
  Hecho cuando: test WireMock: `/health` de cada scraper antes del primer par; un `/health` con retardo de 60 s no falla con el timeout de 90 s.

- [x] **T3 — `TopTermsRefreshJob` con candado y `@Scheduled`**
  Depende: T2. RF: RF-1, RF-7, RF-9.
  Hecho cuando: dos ejecuciones concurrentes → una procesa; Redis caído → no se ejecuta y warning; `enabled=false` → bean no registrado.

- [x] **T4 — Test de integración del job**
  Depende: T3.
  Hecho cuando: con métricas sembradas y WireMock, tras `run()` los snapshots están frescos y `search_count` no cambia.

## Resultado (2026-10-19)

`./gradlew build` en verde (94 tests, 2 ejecuciones). T4 se cubre con `TopTermsRefresherTest`: PostgreSQL y lock Redis reales, `SearchService` real y scrapers simulados con `FakeCatalog` (no WireMock). El retardo de 60 s de T2b se prueba a escala (`/health` de 300 ms vs 1,5 s con timeout de 500 ms) para no alargar la suite.
