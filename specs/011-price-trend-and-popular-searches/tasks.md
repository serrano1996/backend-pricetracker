# Tasks 011 — Último cambio de precio y búsquedas populares

- [x] **T1 — `previous_price` y `price_changed_at` en la búsqueda** (RF-1, RF-2). Test: `SearchControllerTest` (null en el alta, precio anterior tras un cambio). RED observado.
- [x] **T2 — `GET /api/v1/searches/popular`** (RF-3, RF-4). Tests: `PopularSearchesControllerTest` (suma por códigos postales, ventana de 30 días, máximo 10, no cuenta como búsqueda). RED observado.

## Resultado (2026-10-20)

`./gradlew build` en verde (142 tests).

**Test intermitente sin resolver:** `TopTermsRefresherTest` › *refreshes the most searched recent terms in order, sequentially* falló 2 de 14 ejecuciones completas en esta rama (refreshed 3 en vez de 6) y 0 de 7 en `main` (43fb2e0); pasa siempre en solitario. No se reprodujo con logs, así que la causa sigue sin confirmar (sospecha: orden o tiempo entre clases de test, p. ej. un candado de Redis aún ocupado). Vigilar.
