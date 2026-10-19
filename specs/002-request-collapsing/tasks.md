# Tasks 002 — Request collapsing

Desglose de [plan.md](plan.md). Requiere la spec 001 completada.

- [x] **T1 — `RefreshLockPort` + `RedisRefreshLock`**
  RF: RF-1, RF-2, RF-6.
  Hecho cuando: tests Testcontainers de plan.md §3 (adquirir / rechazo / liberar ajeno / TTL).

- [x] **T2 — `FOR UPDATE` en la carga de `store_products` del refresco**
  RF: RF-7.
  Hecho cuando: test de dos refrescos paralelos sin candado → 1 registro de histórico por cambio.

- [x] **T3 — Integrar el candado en `SearchService`**
  Adquirir → refrescar → liberar en `finally`; si no adquiere, sondeo con timeout; propiedades `LOCK_TTL`, `LOCK_POLL_INTERVAL`, `LOCK_WAIT_TIMEOUT`.
  Depende: T1, T2. RF: RF-1..RF-4, RF-6.
  Hecho cuando: test concurrente de 20 búsquedas → 1 llamada por tienda en WireMock; vencida la espera → `stale: true`.

- [x] **T4 — Degradación sin Redis**
  Depende: T3. RF: RF-5.
  Hecho cuando: con el contenedor Redis detenido la búsqueda responde 200 y hay un warning.
  Implementado como: `RedisRefreshLock` contra un Redis inaccesible devuelve `Unavailable` en ms (timeouts de Lettuce a 2 s en `application.yml`, por defecto eran 60 s) y `SearchService` con un lock `Unavailable` refresca y responde. No se detiene el contenedor compartido para no romper el resto de suites.

## Resultado (2026-10-19)

`./gradlew build` en verde (77 tests, 3 ejecuciones seguidas). RED observado en T2: 10 refrescos concurrentes dejaban 4 históricos duplicados; con `@Lock(PESSIMISTIC_WRITE)`, ninguno. 20 búsquedas concurrentes → 1 llamada por tienda.
