# Arquitectura — backend-pricetracker

Backend del comparador de precios (Mercadona, Alcampo, Dia). Orquesta los tres scrapers, persiste catálogo e histórico en PostgreSQL (Supabase) y sirve las búsquedas al frontend Angular.

Visión de producto original: [`../../supertracker.md`](../../supertracker.md). Este documento fija **cómo** se construye y corrige los puntos del documento original que no encajan con los scrapers reales.

## 1. Contexto

```
 Angular (Render/Vercel)
        │  GET /api/v1/search?term=&postal_code=
        ▼
 backend-pricetracker (Railway, Frankfurt) ──► Redis (candados)
        │                     │
        │ JDBC (HikariCP)     │ HTTPS + X-API-Key, en paralelo
        ▼                     ▼
 PostgreSQL (Supabase)   mercadona-scraper / alcampo-scraper / dia-scraper (Render)
                              └─ cada uno con su caché Redis (Upstash), rate limit y collapsing propios
```

## 2. Contrato de los scrapers (ya existente)

Los tres exponen el mismo contrato, verificado en su código (`app/models/product.py`):

- `GET /api/v1/products?postal_code=28001&term=leche&page=1&page_size=50`
- Cabecera `X-API-Key` obligatoria (401 si falta o es inválida). `/health` es público.
- `postal_code`: `^[0-9]{5}$`. `term`: 1–100 caracteres tras `strip`. `page` ≤ 20, `page_size` ≤ 100.
- Respuesta 200:

```json
{
  "search": { "postal_code": "28001", "term": "leche", "warehouse": "mad1", "strategy_used": "api",
              "scraped_at": "2026-07-24T10:00:00Z", "total_results": 1, "page": 1, "page_size": 50, "total_pages": 1 },
  "products": [ { "id": "1", "name": "Leche entera", "price": 1.05, "price_format": "1.05 €/L",
                  "image_url": "https://…", "category": "Lácteos" } ]
}
```

- Errores: `404` (código postal no servido; en Mercadona también "page out of range", distinguible por `detail`), `502` (supermercado caído tras reintentos).

**Consecuencias para el backend:**

- El precio depende de la tienda/almacén: la clave natural de un producto es `(store_name, warehouse, external_id)`, no solo `external_id`.
- No hay `unit_price` numérico: se obtiene parseando `price_format`; si no se puede, queda `NULL`.
- Los scrapers ya cachean (1 h) y colapsan peticiones en vuelo. El candado del backend (spec 002) no protege al supermercado, protege **nuestra** BD de refrescos e históricos duplicados.

## 3. Correcciones al documento original

| Original | Decisión | Motivo |
|---|---|---|
| `store_products` único por `external_id` | Único por `(store_name, warehouse, external_id)` | El precio varía por almacén/región. |
| Frescura por `last_checked_at` del producto | Tabla `search_snapshots` por `(término, código postal, tienda)` | Sin ella no se sabe si "leche" está fresca: un producto puede estar al día y la búsqueda no. |
| `products` (catálogo unificado) obligatorio | `products` son **tipos de producto** ("Leche entera · L") con reglas de palabras clave; `store_products.product_id` nullable | Sin EAN y con nombres distintos entre tiendas, el matching exacto apenas encuentra pares (casi todo es marca propia). Decisión del usuario: comparar equivalentes por tipo ([spec 004](../specs/004-product-type-matching/spec.md)). |
| Histórico en cada refresco (§1) vs. solo en cambio (§5) | Solo cuando cambia el precio (o alta del producto) | 500 MB en Supabase free; un registro por refresco crece sin aportar información. |
| `search_metrics.query_term` único | Único por `(query_term, postal_code)` | El job necesita saber para qué código postal refrescar. |
| WebFlux + JPA | Spring MVC + virtual threads + `RestClient` | JPA es bloqueante; mezclarlo con WebFlux obliga a `subscribeOn(boundedElastic)` en todas partes. Con un executor de virtual threads, el paralelismo de 3 llamadas es código lineal. |
| Redisson | `SET NX PX` + borrado con token (Lua) vía Spring Data Redis | Un candado simple con TTL basta; evita una dependencia. |
| Flyway o Liquibase | Flyway | SQL plano, menos ceremonia. |

## 4. Decisiones de stack

- **D1 — Kotlin 2.x + Spring Boot 4.x + Gradle Kotlin DSL.** Decidido por el usuario (2026-10-19). Versiones fijadas en T1 (2026-10-19): Spring Boot 4.1.1, Kotlin 2.3.21, Gradle 9.8.1, JDK 25 (LTS). Virtual threads (`spring.threads.virtual.enabled=true`).
- **D2 — `RestClient` (bloqueante) + virtual threads para el paralelismo:** una tarea por tienda en `Executors.newVirtualThreadPerTaskExecutor()` y `future.get()`; cada llamada ya está acotada por el timeout de su tienda. Se descartaron coroutines (decisión en T10): `runBlocking` alrededor de código bloqueante y una dependencia más, sin ganancia. Controladores MVC normales (no `suspend`).
- **D3 — Tests:** JUnit 5, AssertJ (o `kotlin.test`), Testcontainers (`postgres:17-alpine`, `redis:7-alpine`), WireMock para simular scrapers.

## 5. Estructura de paquetes

```
com.pricetracker
├── search/
│   ├── domain/          # StoreName, StoreOffer, SearchTerm (normalización), UnitPriceParser — Kotlin puro
│   ├── application/     # SearchService, StoreRefreshService, puertos: StoreCatalogPort, RefreshLockPort
│   ├── infrastructure/
│   │   ├── scraper/     # ScraperClient (RestClient), ScraperProperties
│   │   ├── persistence/ # entidades JPA + repositorios
│   │   └── lock/        # RedisRefreshLock (spec 002)
│   └── web/             # SearchController, DTOs, manejo de errores
├── metrics/             # SearchMetricRecorder
└── refreshjob/          # TopTermsRefreshJob (spec 003)
```

## 6. Modelo de datos

```
products 1───* store_products 1───* price_history
                     │
                     *
search_snapshot_items *───1 search_snapshots

search_metrics (independiente)
```

| Tabla | Clave / unicidad | Propósito |
|---|---|---|
| `products` | `id` UUID; único `canonical_name` | Tipos de producto con unidad y reglas `match_all` / `match_none` (spec 004). |
| `store_products` | `id` UUID; único `(store_name, warehouse, external_id)` | Producto de una tienda en un almacén, con precio actual. |
| `price_history` | `id` BIGINT identity | Un registro por alta o cambio de precio. |
| `search_snapshots` | `id` UUID; único `(query_term, postal_code, store_name)` | Última vez que se trajo un término de una tienda y su estado. |
| `search_snapshot_items` | PK `(snapshot_id, position)` | Resultados ordenados de ese snapshot. |
| `search_metrics` | `id` BIGINT; único `(query_term, postal_code)` | Popularidad para el job nocturno. |

DDL completo: [specs/001-backend-search-mvp/plan.md](../specs/001-backend-search-mvp/plan.md#2-modelo-de-datos).

## 7. Despliegue

- **Backend:** Railway (Hobby), región Europa. Imagen Docker multi-stage (`eclipse-temurin` con el JDK de D1).
- **Redis del backend:** instancia propia (Railway o Upstash), separada de las de los scrapers.
- **BD:** Supabase free. Conexión por el *pooler* (Supavisor, modo *session*) si el host no tiene salida IPv6, ya que la conexión directa de Supabase es IPv6. SSL `sslmode=require`.
- **Local:** `docker-compose.yml` con `postgres:17-alpine` y `redis:7-alpine`; los scrapers se apuntan por variable de entorno (locales o los de Render).

### Variables de entorno

| Variable | Ejemplo |
|---|---|
| `DB_URL` | `jdbc:postgresql://<host>:5432/postgres?sslmode=require` |
| `DB_USER` / `DB_PASSWORD` | — |
| `REDIS_URL` | `rediss://default:<pwd>@<host>:6379` |
| `SCRAPER_MERCADONA_URL` / `SCRAPER_MERCADONA_API_KEY` | `https://mercadona-scraper-….onrender.com` |
| `SCRAPER_ALCAMPO_URL` / `SCRAPER_ALCAMPO_API_KEY` | — |
| `SCRAPER_DIA_URL` / `SCRAPER_DIA_API_KEY` | — |
| `SCRAPER_<TIENDA>_READ_TIMEOUT` | `PT25S` (búsquedas de usuario) |
| `REFRESH_JOB_READ_TIMEOUT` | `PT90S` (job nocturno) |
| `REFRESH_JOB_ENABLED` / `REFRESH_JOB_CRON` / `REFRESH_JOB_TOP_N` | `true` / `0 0 4 * * *` (Europe/Madrid) / `50` |
| `REFRESH_JOB_RECENCY` / `REFRESH_JOB_MIN_DELAY` / `REFRESH_JOB_MAX_DELAY` / `REFRESH_JOB_STORE_FAILURE_LIMIT` | `P30D` / `PT3S` / `PT10S` / `5` |
| `SEARCH_FRESHNESS_TTL` | `PT12H` |
| `LOCK_TTL` / `LOCK_POLL_INTERVAL` / `LOCK_WAIT_TIMEOUT` | `PT30S` / `PT0.25S` / `PT15S` (spec 002) |
| `SCRAPER_CALL_BUDGET` / `SCRAPER_CALL_BUDGET_WINDOW` | `300` / `PT1H`: llamadas a scrapers por ventana, entre todas las instancias. Agotado, se sirve lo guardado. El job nocturno no lo consume. |
| `SEARCH_FALLBACK_MAX_PROBES` | `3`: códigos postales cercanos probados por tienda cuando no sirve el buscado (spec 006). |
| `CORS_ALLOWED_ORIGINS` | `https://<frontend>` |

## 8. Riesgos conocidos

- **Cold start de los scrapers (Render free, confirmado):** según la documentación de Render, un servicio gratuito se duerme tras ~15 min sin tráfico y tarda del orden de un minuto en despertar. Además, las horas gratuitas son unas 750/mes por workspace, menos de lo que necesitan tres servicios encendidos todo el mes (~2.200 h), así que **no** se pueden mantener despiertos con pings permanentes. Comprobar estos límites en la documentación vigente de Render.
  Mitigación:
  1. Timeout de lectura corto para el usuario (25 s): si vence, esa tienda sale con snapshot obsoleto o `unavailable` (spec 001 RF-8). La petición igualmente despierta el scraper, así que la siguiente búsqueda funciona.
  2. *Warm-up* al recibir una búsqueda: ping asíncrono a `/health` de los scrapers que no se han llamado en los últimos 10 min (spec 001 RF-14). No consume horas extra porque solo ocurre cuando hay usuarios.
  3. El job nocturno usa un timeout largo (90 s) y despierta cada scraper con un `/health` antes de empezar (spec 003 RF-10).
  4. Para quitar el problema de raíz: pasar los scrapers a un plan de pago o alojarlos junto al backend en Railway.
- **Pausa de Supabase free** tras días sin actividad: el job nocturno (spec 003) la mantiene activa.
- **Crecimiento del histórico:** solo se inserta en cambios de precio; revisar tamaño con 500 MB de límite.
- **Matching entre tiendas** sin resolver: el frontend compara por término, no por producto unificado, hasta su spec.

## 9. Roadmap de specs

| Spec | Alcance |
|---|---|
| [001-backend-search-mvp](../specs/001-backend-search-mvp/spec.md) | Búsqueda bajo demanda, persistencia, TTL, histórico, métricas. |
| [002-request-collapsing](../specs/002-request-collapsing/spec.md) | Candado distribuido Redis por `(término, CP, tienda)`. |
| [003-smart-refresh-job](../specs/003-smart-refresh-job/spec.md) | Job nocturno sobre los términos más buscados con jitter. |
| [004-product-type-matching](../specs/004-product-type-matching/spec.md) | Tipos de producto y comparación por precio unitario en la búsqueda. |
| [005-price-history-endpoint](../specs/005-price-history-endpoint/spec.md) | Endpoint de histórico de precios para gráficas. |
