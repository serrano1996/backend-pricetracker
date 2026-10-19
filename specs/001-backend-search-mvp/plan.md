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
| RF-14 | `ScraperClient.wakeIdleScrapers` |

## 2. Modelo de datos

Fuente de verdad: [`V1__init.sql`](../../src/main/resources/db/migration/V1__init.sql) (+ [`afterMigrate.sql`](../../src/main/resources/db/migration/afterMigrate.sql)). Resumen de decisiones aplicadas (guía de buenas prácticas de Postgres de Supabase):

- `text` en lugar de `varchar(n)`; los límites que importan son `check` (`store_name`, `status`, longitud del término, `postal_code ~ '^[0-9]{5}$'`).
- Índice en cada clave foránea que no lo tiene ya por PK o índice compuesto (`store_products.product_id`, `search_snapshot_items.store_product_id`).
- RLS activado sin políticas en todas las tablas, incluida `flyway_schema_history`: Supabase expone `public` por su Data API con la clave anónima; el backend conecta como propietario y no le afecta. La de Flyway va en `afterMigrate.sql` porque dentro de una migración el `ALTER` queda bloqueado para siempre por el propio lock de Flyway.

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

### D3 — Paralelismo con virtual threads
**Elegido (implementado en T10):** `SearchService` envía una tarea por tienda caducada a `Executors.newVirtualThreadPerTaskExecutor()` y espera con `future.get()`; sin timeout global, porque cada llamada ya está acotada por el `read-timeout` de su tienda y el puerto nunca lanza. La persistencia (`StoreRefreshService`) se ejecuta después, en el hilo de la petición, una vez por tienda.
**Descartado:** coroutines (`runBlocking { async(vtDispatcher) }`). Motivo: envolver llamadas bloqueantes en `runBlocking` añade una dependencia y otro modelo de concurrencia sin ganar nada sobre virtual threads.
**Descartado:** WebFlux/`Mono.zip`. Motivo: JPA es bloqueante.
**Descartado:** controladores `suspend`. Motivo: las transacciones de Spring van ligadas al hilo.
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

### D7 — `UnitPriceParser` con normalización de unidades por 100
**Hecho verificado (T5):** los tres scrapers construyen `price_format` igual: `f"{importe} €/{unidad}"`, con punto decimal. Unidades vistas en sus mappers: `L`, `kg`, `ud`, `docena`, `lavado`, `100 ml`, `100 g` (Mercadona usa su `reference_format`).
**Elegido:** `parse(text) → UnitPrice(amount, unit)?`; `100 g` → `kg` y `100 ml` → `L` multiplicando por 10; el resto de unidades tal cual; formato no reconocido → `null`. Se guarda en `unit_price` + `unit_price_unit`.
**Descartado:** guardar solo el número. Motivo: 0,25 €/100 g y 2,50 €/kg son el mismo precio y sin unidad no se pueden comparar.
RF: caso límite "price_format no parseable".

### D7b — Warm-up sin bloquear
**Elegido (implementado en T8b):** `ScraperClient.wakeIdleScrapers()` guarda en memoria el último contacto por tienda (búsqueda o ping) y, si pasa de `warmup-idle`, lanza `GET /health` en un virtual thread; errores solo a log. La comprobación es atómica (`ConcurrentHashMap.compute`): búsquedas simultáneas hacen un único ping. Vive en el cliente porque es quien tiene los `RestClient` y ve cada contacto; un virtual thread basta para un *fire-and-forget* y evita adelantar la dependencia de coroutines.
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
