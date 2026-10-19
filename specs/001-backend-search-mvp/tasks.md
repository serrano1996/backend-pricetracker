# Tasks 001 — Búsqueda bajo demanda con persistencia

Desglose de [plan.md](plan.md). Orden = orden de dependencia.

## Setup

- [x] **T1 — Scaffolding**
  Proyecto Gradle (Kotlin DSL) generado con Spring Initializr: Spring Boot 4.1.1, Kotlin 2.3.21, JDK 25; plugins `kotlin-spring` y `kotlin-jpa`; deps: webmvc, data-jpa, flyway, data-redis, actuator, validation, postgresql, jackson-module-kotlin; test: testcontainers (postgres, redis). WireMock entra en T8 y kotlinx-coroutines en T10, al usarse. `.gitignore`, `.env.example` (solo nombres de variables), `docker-compose.yml` (postgres:17-alpine, redis:7-alpine), `Dockerfile` multi-stage, `.dockerignore`.
  Estado (2026-10-19): `./gradlew build` en verde (test de contexto con Testcontainers) y `docker build` correcto.
  RF: base.
  Hecho cuando: `./gradlew build` en verde con el test de contexto y `docker compose up` levanta BD y Redis.

- [ ] **T2 — `application.yml` y propiedades**
  Datasource por env (SSL), Hikari (`maximum-pool-size` pequeño), `ddl-auto=validate`, virtual threads, `ScraperProperties` (url, apiKey, connect/read timeout por tienda, por defecto 25 s), `SCRAPER_WARMUP_IDLE`, `SEARCH_FRESHNESS_TTL`, CORS, actuator solo `health`.
  Depende: T1. RF: RF-12, RF-13.
  Hecho cuando: test de binding de `ScraperProperties`; `/actuator/health` responde 200 sin detalles.

- [ ] **T3 — Migración `V1__init.sql`**
  DDL de plan.md §2.
  Depende: T1. RF: RF-6, RF-7, RF-10, RF-11.
  Hecho cuando: test con Testcontainers arranca la app con `ddl-auto=validate` sin errores.

## Dominio

- [ ] **T4 — `SearchTerm` y `PostalCode`**
  Depende: T1. RF: RF-2, RF-3.
  Hecho cuando: tests de normalización (`" Leche  Entera "` → `"leche entera"`), vacío, 101 caracteres, CP `2800`, `28001`, dígitos Unicode no ASCII.

- [ ] **T5 — `UnitPriceParser`**
  Depende: T1. RF: caso límite.
  Hecho cuando: tests con `"1.05 €/L"`, `"0,95 €/L"`, `"2,10 €/kg"`, `null`, `"oferta"`.

- [ ] **T6 — `StoreName`, `StoreOffer`, `StoreSearchResult`**
  Depende: T1. RF: RF-1, RF-8, RF-10.
  Hecho cuando: compila y el orden del enum es mercadona, alcampo, dia.

## Infraestructura

- [ ] **T7 — Entidades JPA y repositorios**
  `StoreProductEntity`, `PriceHistoryEntity`, `SearchSnapshotEntity` (+ ítems), `SearchMetricEntity`, `ProductEntity`.
  Depende: T3. RF: RF-6, RF-7.
  Hecho cuando: test de repositorio guarda y lee un `store_product` con su histórico.

- [ ] **T8 — `ScraperClient`**
  Depende: T2, T6. RF: RF-8, RF-10, RF-12.
  Hecho cuando: tests WireMock de la tabla D8 y de la cabecera `X-API-Key`; la key no aparece en logs.

- [ ] **T8b — `ScraperWarmup`**
  Depende: T8. RF: RF-14.
  Hecho cuando: test WireMock: primera búsqueda → 1 `GET /health` por tienda; segunda búsqueda a los 5 min → 0; un `/health` lento no retrasa la respuesta.

## Aplicación

- [ ] **T9 — `StoreRefreshService`**
  Upsert por `(store, warehouse, external_id)`, histórico en alta/cambio, reemplazo de ítems del snapshot, redondeo HALF_UP.
  Depende: T5, T7. RF: RF-6, RF-7, RF-10.
  Hecho cuando: tests: alta → 1 histórico; mismo precio → 0; precio distinto → 1 más; duplicado en respuesta → una fila; lista vacía → snapshot fresco vacío.

- [ ] **T10 — `SearchService`**
  Frescura por tienda, refresco paralelo, fallback a snapshot obsoleto, 502 si todo falla.
  Depende: T8, T9. RF: RF-1, RF-4, RF-5, RF-8, RF-9.
  Hecho cuando: tests con `Clock` fijo: fresco → 0 llamadas; 13 h después → 1 llamada por tienda; una tienda caída con snapshot → `stale: true`; sin snapshot → `unavailable`; tres caídas sin datos → excepción mapeada a 502; latencia paralela < 2 s con retardos de 1 s.

- [ ] **T11 — `SearchMetricRecorder`**
  Depende: T7. RF: RF-11.
  Hecho cuando: 20 hilos concurrentes → `search_count == 20`; fallo de BD simulado → la búsqueda responde y hay warning.

## Web

- [ ] **T12 — `SearchController` + DTOs + `ApiExceptionHandler`**
  Depende: T10, T11. RF: RF-1, RF-2, RF-9.
  Hecho cuando: test de integración end-to-end (Testcontainers + WireMock) devuelve el JSON de la spec; 400 con CP inválido sin llamadas a WireMock; CORS rechaza un origen no permitido.

## Cierre

- [ ] **T13 — Verificación manual y README**
  Arrancar con los scrapers desplegados, buscar 3 términos reales, repetir y comprobar 0 llamadas en logs. Documentar arranque local en `README.md`.
  Depende: T12. RF: criterios de finalización.
