# Plan 001 — Búsqueda bajo demanda con persistencia

Basado en [constitution.md](../../docs/constitution.md), [architecture.md](../../docs/architecture.md) y [spec.md](spec.md). Sin código: módulos, modelo de datos, decisiones y estrategia de test.

## 1. Módulos

```
src/main/kotlin/com/pricetracker/
├── PriceTrackerApplication.kt
├── search/
│   ├── domain/
│   │   ├── StoreName.kt            # enum MERCADONA, ALCAMPO, DIA (orden de RF-1)
│   │   ├── SearchTerm.kt           # value class + normalización (RF-2, RF-3)
│   │   ├── PostalCode.kt           # value class + validación ^[0-9]{5}$ (RF-2)
│   │   ├── StoreOffer.kt           # data class: producto tal como llega del scraper
│   │   ├── StoreSearchResult.kt    # sealed interface: Found(warehouse, offers) | NotServed | Unavailable(reason)
│   │   └── UnitPriceParser.kt      # price_format → BigDecimal?
│   ├── application/
│   │   ├── SearchService.kt        # orquesta: frescura → refresco paralelo → ensamblado (RF-1, RF-4, RF-5, RF-8, RF-9)
│   │   ├── StoreRefreshService.kt  # transacción por tienda: upsert + histórico + snapshot (RF-6, RF-7, RF-10)
│   │   └── port/StoreCatalogPort.kt  # search(store, term, postalCode) → StoreSearchResult
│   ├── infrastructure/
│   │   ├── scraper/ScraperClient.kt      # RestClient por tienda, mapea HTTP → StoreSearchResult (RF-8, RF-10, RF-12)
│   │   ├── scraper/ScraperProperties.kt  # @ConfigurationProperties: url, apiKey, timeouts por tienda
│   │   ├── scraper/ScraperWarmup.kt      # ping /health asíncrono a scrapers inactivos (RF-14)
│   │   └── persistence/                    # entidades + repos (sección 2)
│   └── web/
│       ├── SearchController.kt     # GET /api/v1/search
│       ├── SearchResponse.kt       # DTOs de la sección "Formato de respuesta" de la spec
│       └── ApiExceptionHandler.kt  # 400 / 502 con cuerpo {"detail": "..."}
├── metrics/
│   └── SearchMetricRecorder.kt     # upsert atómico (RF-11)
└── config/
    ├── CorsConfig.kt
    └── ClockConfig.kt              # Clock inyectable para tests de frescura
src/main/resources/
├── application.yml
└── db/migration/V1__init.sql
```

Cobertura por RF:

| RF | Dónde |
|---|---|
| RF-1, RF-9 | `SearchService`, `SearchController` |
| RF-2, RF-3 | `SearchTerm`, `PostalCode`, `ApiExceptionHandler` |
| RF-4, RF-5, RF-8 | `SearchService` |
| RF-6, RF-7, RF-10 | `StoreRefreshService`, persistence |
| RF-11 | `SearchMetricRecorder` |
| RF-12 | `ScraperClient`, `ScraperProperties` |
| RF-13 | Spring Boot Actuator (config) |
| RF-14 | `ScraperWarmup` |

## 2. Modelo de datos

`V1__init.sql`:

```sql
CREATE TABLE products (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    canonical_name VARCHAR(255) NOT NULL,
    category       VARCHAR(255),
    image_url      TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE store_products (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id         UUID REFERENCES products(id),          -- NULL hasta la spec de matching
    store_name         VARCHAR(20)  NOT NULL CHECK (store_name IN ('mercadona','alcampo','dia')),
    warehouse          VARCHAR(50)  NOT NULL,
    external_id        VARCHAR(100) NOT NULL,
    store_product_name VARCHAR(255) NOT NULL,
    category           VARCHAR(255),
    image_url          TEXT,
    current_price      NUMERIC(10,2) NOT NULL,
    price_format       VARCHAR(100),
    unit_price         NUMERIC(10,2),
    last_checked_at    TIMESTAMPTZ NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (store_name, warehouse, external_id)
);

CREATE TABLE price_history (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_product_id UUID NOT NULL REFERENCES store_products(id) ON DELETE CASCADE,
    price            NUMERIC(10,2) NOT NULL,
    unit_price       NUMERIC(10,2),
    recorded_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_price_history_product_time ON price_history (store_product_id, recorded_at DESC);

CREATE TABLE search_snapshots (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    query_term  VARCHAR(100) NOT NULL,
    postal_code CHAR(5)      NOT NULL,
    store_name  VARCHAR(20)  NOT NULL,
    status      VARCHAR(20)  NOT NULL CHECK (status IN ('ok','not_served')),
    fetched_at  TIMESTAMPTZ  NOT NULL,
    UNIQUE (query_term, postal_code, store_name)
);

CREATE TABLE search_snapshot_items (
    snapshot_id      UUID NOT NULL REFERENCES search_snapshots(id) ON DELETE CASCADE,
    position         INT  NOT NULL,
    store_product_id UUID NOT NULL REFERENCES store_products(id) ON DELETE CASCADE,
    PRIMARY KEY (snapshot_id, position)
);

CREATE TABLE search_metrics (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    query_term       VARCHAR(100) NOT NULL,
    postal_code      CHAR(5)      NOT NULL,
    search_count     BIGINT       NOT NULL DEFAULT 0,
    last_searched_at TIMESTAMPTZ  NOT NULL,
    UNIQUE (query_term, postal_code)
);
CREATE INDEX idx_search_metrics_rank ON search_metrics (search_count DESC, last_searched_at DESC);
```

`unavailable` no se persiste: un fallo no debe pisar el último snapshot bueno (RF-8).

## 3. Decisiones de diseño

### D1 — Snapshot por `(término, CP, tienda)`, no por producto
**Elegido:** tabla `search_snapshots` + ítems ordenados.
**Descartado:** decidir frescura con `MIN(last_checked_at)` de los productos que casan por `LIKE` con el término. Motivo: un `LIKE` en BD no reproduce el ranking ni la relevancia del buscador de cada tienda, y no detecta productos nuevos.
RF: RF-4, RF-6.

### D2 — Frescura y estado por tienda, no por búsqueda completa
**Elegido:** cada tienda tiene su snapshot y se refresca de forma independiente.
**Descartado:** un único snapshot por término. Motivo: si Alcampo falla, se re-golpearía a Mercadona y Dia sin necesidad en la siguiente búsqueda.
RF: RF-4, RF-8.

### D3 — Paralelismo con coroutines sobre virtual threads
**Elegido:** `SearchService` hace `runBlocking { coroutineScope { staleStores.map { async(vtDispatcher) { client.search(it, …) } }.awaitAll() } }` con `withTimeout` global; la persistencia (`StoreRefreshService`) se llama después, fuera de la coroutine, una vez por tienda.
**Descartado:** WebFlux/`Mono.zip`. Motivo: JPA es bloqueante; así el código es lineal y testeable sin `StepVerifier`.
**Descartado:** controladores `suspend`. Motivo: las transacciones de Spring van ligadas al hilo; mezclarlas con coroutines que cambian de hilo da errores sutiles. Las coroutines quedan limitadas al fan-out HTTP.
RF: RF-5.

### D4 — Una transacción por tienda
**Elegido:** `StoreRefreshService.persist(store, term, postalCode, result)` es `@Transactional` y se llama una vez por tienda, fuera del hilo de la llamada HTTP.
**Descartado:** una transacción para las tres. Motivo: un error persistiendo Dia no debe deshacer Mercadona; y no se mantiene una conexión de BD abierta mientras se espera al scraper (HikariCP tiene pocas conexiones en Supabase free).
RF: RF-6, RF-7, NFR consistencia.

### D5 — Histórico comparando con `current_price` en la misma transacción
**Elegido:** cargar los `store_products` existentes por `(store, warehouse, external_id IN (...))` en una consulta, comparar en memoria, insertar histórico solo si es alta o cambio.
**Descartado:** trigger en PostgreSQL. Motivo: la regla vive en un sitio testeable en Kotlin; un trigger es invisible desde el código.
RF: RF-7.

### D6 — Métricas con `INSERT … ON CONFLICT DO UPDATE`
**Elegido:** consulta nativa `search_count = search_metrics.search_count + 1`, en su propia transacción y envuelta en try/catch.
**Descartado:** leer-modificar-guardar con JPA. Motivo: pierde incrementos con búsquedas concurrentes.
RF: RF-11.

### D7 — `UnitPriceParser` conservador
**Elegido:** extraer el primer número de `price_format` (admite `,` y `.` decimal) solo si el texto contiene `€/`; en cualquier otro caso `null`.
**Descartado:** intentar normalizar unidades (`€/100 g` → `€/kg`). Motivo: cada tienda usa formatos distintos y hoy no hay muestras suficientes; se amplía cuando haya fixtures reales.
RF: caso límite "price_format no parseable".

### D7b — Warm-up sin bloquear
**Elegido:** `ScraperWarmup` guarda en memoria el último instante de llamada por tienda y, si supera 10 min, lanza `GET /health` en una coroutine de fondo (`SupervisorJob`, errores solo a log).
**Descartado:** cron de ping cada 14 min. Motivo: tres servicios despiertos 24/7 superan las horas gratuitas de Render (architecture.md §8).
RF: RF-14.

### D8 — Mapeo de errores del scraper
| Respuesta del scraper | `StoreSearchResult` |
|---|---|
| `200` | `Found` |
| `404` con `detail` de código postal no servido | `NotServed` |
| `404` otro, `401`, `5xx`, timeout, red | `Unavailable(reason)` |

RF: RF-8, RF-10, RF-12.

## 4. Estrategia de test

- **Unitarios (sin Spring):** `SearchTerm`, `PostalCode`, `UnitPriceParser`, reglas de histórico.
- **`ScraperClient`:** WireMock con respuestas 200 / 200 vacío / 404 / 401 / 502 / timeout; verifica cabecera `X-API-Key`.
- **Integración (`@SpringBootTest` + Testcontainers + WireMock):** flujo completo por RF; `Clock` fijo para simular el paso de 12 h.
- **Paralelismo:** WireMock con retardo de 1 s en las tres tiendas → respuesta total < 2 s.
