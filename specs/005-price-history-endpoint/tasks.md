# Tasks 005 — Endpoint de histórico de precios

Desglose de [plan.md](plan.md). Requiere la spec 001 completada.

- [x] **T1 — Consulta del histórico + `PriceHistoryService`**
  RF: RF-1, RF-2, RF-4.
  Hecho cuando: el test de T2 devuelve producto e histórico ascendente desde la BD.

- [x] **T2 — `PriceHistoryController`, DTOs y errores**
  Depende: T1. RF: RF-1..RF-5.
  Hecho cuando: tests de integración: JSON de la spec, 404 con id desconocido, 400 con id no UUID, sin métricas registradas, CORS rechaza un origen no permitido.

## Resultado (2026-10-19)

`./gradlew build` en verde (98 tests). RED observado: los 3 tests del endpoint daban 404 antes de implementarlo; el de CORS ya pasaba (política global de `/api/**` desde la spec 001).
