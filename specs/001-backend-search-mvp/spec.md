# Spec 001 — Búsqueda bajo demanda con persistencia (MVP)

## Contexto y objetivo

Los tres scrapers (Mercadona, Alcampo, Dia) ya exponen un contrato común de búsqueda, pero cada uno por separado y sin memoria más allá de una caché de 1 h. El frontend necesita **una** llamada que devuelva los resultados de las tres tiendas, y el producto necesita **persistir** precios para construir histórico.

Esta spec cubre la búsqueda bajo demanda (*lazy loading*): servir desde PostgreSQL si los datos tienen menos de 12 h; si no, refrescar desde los scrapers en paralelo, persistir y registrar cambios de precio.

## Actores

- **Usuario final** (vía frontend Angular): busca un término en su código postal.
- **Scrapers** (sistemas externos propios): fuente de datos.

## Historias de usuario

- **H1:** Como usuario, quiero buscar "leche" en mi código postal y ver los resultados de las tres tiendas en una sola pantalla.
- **H2:** Como usuario, quiero ver resultados aunque una tienda esté caída, sabiendo cuál falla.
- **H3:** Como producto, quiero guardar cada cambio de precio para mostrar su evolución más adelante.

## Requisitos funcionales (EARS)

- **RF-1:** CUANDO el cliente envíe `GET /api/v1/search?term=leche&postal_code=28001`, EL SISTEMA responderá `200 OK` con los resultados agrupados por tienda (`mercadona`, `alcampo`, `dia`), siempre las tres y en ese orden.
- **RF-2:** SI `postal_code` no cumple `^[0-9]{5}$` o `term` queda vacío o supera 100 caracteres tras recortar espacios, ENTONCES EL SISTEMA responderá `400 Bad Request` sin llamar a ningún scraper. (Mismas reglas que los scrapers.)
- **RF-3:** EL SISTEMA normalizará el término (recortar, minúsculas, colapsar espacios internos) y usará el término normalizado como clave de frescura y de métricas. `" Leche  Entera "` y `"leche entera"` son la misma búsqueda.
- **RF-4:** CUANDO exista un snapshot de `(término, código postal, tienda)` con `fetched_at` de hace menos de `SEARCH_FRESHNESS_TTL` (por defecto 12 h), EL SISTEMA servirá esa tienda desde PostgreSQL sin llamar a su scraper.
- **RF-5:** CUANDO falte el snapshot o esté obsoleto, EL SISTEMA llamará al scraper de esa tienda (página 1, `page_size` 50). Las tiendas que necesiten refresco se consultan **en paralelo**; la latencia total es la de la más lenta, no la suma.
- **RF-6:** CUANDO un scraper responda `200`, EL SISTEMA hará *upsert* de cada producto en `store_products` por `(store_name, warehouse, external_id)`, actualizando nombre, precio, `price_format`, `unit_price`, imagen, categoría y `last_checked_at`, y reemplazará los ítems del snapshot manteniendo el orden del scraper.
- **RF-7:** CUANDO un producto se cree o su `current_price` cambie, EL SISTEMA insertará un registro en `price_history`. SI el precio no cambia, ENTONCES no se inserta nada.
- **RF-8:** SI un scraper falla (`5xx`, timeout o error de red), ENTONCES EL SISTEMA servirá el último snapshot de esa tienda con `stale: true`; SI no hay snapshot, devolverá esa tienda con `status: "unavailable"` y `products: []`. Las demás tiendas no se ven afectadas.
- **RF-9:** SI las tres tiendas quedan `unavailable` sin datos que servir, ENTONCES EL SISTEMA responderá `502 Bad Gateway`.
- **RF-10:** CUANDO un scraper devuelva `200` con lista vacía, EL SISTEMA guardará el snapshot vacío como fresco (no se reintenta hasta que caduque). CUANDO devuelva `404` por código postal no servido, EL SISTEMA devolverá esa tienda con `status: "not_served"` y lo guardará como snapshot fresco.
- **RF-11:** CUANDO una búsqueda pase la validación, EL SISTEMA incrementará atómicamente `search_count` y actualizará `last_searched_at` de `(término, código postal)` en `search_metrics`. SI esa escritura falla, ENTONCES se registra un *warning* y la búsqueda responde igual.
- **RF-12:** EL SISTEMA enviará a cada scraper su `X-API-Key` desde variables de entorno y nunca la escribirá en logs. Un `401` del scraper se trata como fallo de esa tienda (RF-8) y se registra como error de configuración.
- **RF-13:** EL SISTEMA expondrá `GET /actuator/health` público, sin detalles internos.
- **RF-14:** CUANDO llegue una búsqueda válida, EL SISTEMA lanzará en segundo plano, sin esperar respuesta, un `GET /health` a cada scraper que no haya recibido ninguna llamada en los últimos `SCRAPER_WARMUP_IDLE` (por defecto 10 min). El resultado del ping no afecta a la respuesta. (Mitiga el *cold start* de Render free, architecture.md §8.)

## Requisitos no funcionales

- **Rendimiento:** respuesta servida íntegramente desde BD < 150 ms p95. Con refresco, la acota el scraper más lento (timeout de lectura por tienda configurable, por defecto 25 s; un scraper de Render free dormido no llega a tiempo, ver RF-14).
- **Consistencia:** el *upsert* de productos, el histórico y el snapshot de una tienda se escriben en **una** transacción por tienda.
- **CORS:** solo los orígenes de `CORS_ALLOWED_ORIGINS`.
- **Observabilidad:** un log por llamada a scraper con tienda, término, código postal, estado y duración.

## Formato de respuesta (200 OK)

```json
{
  "term": "leche",
  "postal_code": "28001",
  "stores": [
    {
      "store": "mercadona",
      "status": "ok",
      "stale": false,
      "fetched_at": "2026-10-19T08:00:00Z",
      "products": [
        {
          "id": "6f1c…",
          "external_id": "10381",
          "name": "Leche entera Hacendado",
          "price": 0.95,
          "price_format": "0,95 €/L",
          "unit_price": 0.95,
          "unit_price_unit": "L",
          "image_url": "https://…",
          "category": "Leche y bebidas vegetales"
        }
      ]
    },
    { "store": "alcampo", "status": "unavailable", "stale": false, "fetched_at": null, "products": [] },
    { "store": "dia", "status": "not_served", "stale": false, "fetched_at": "2026-10-19T08:00:00Z", "products": [] }
  ]
}
```

`status`: `ok` | `not_served` | `unavailable`. `stale: true` solo con `status: ok` servido desde un snapshot caducado tras fallo del scraper.

## Casos límite

- **`price_format` no parseable** (formato desconocido, `null`): `unit_price` y `unit_price_unit` a `null`, el producto se guarda igual.
- **Unidades por 100:** `"0.25 €/100 g"` se guarda como `unit_price = 2.50`, `unit_price_unit = "kg"` (y `100 ml` → `L`), para que el mismo producto sea comparable entre tiendas. `price_format` se devuelve sin cambios.
- **Mismo producto en dos posiciones** de la respuesta del scraper: se guarda una vez; el snapshot conserva la primera posición.
- **Producto que desaparece** de los resultados: se queda en `store_products` con su último precio; solo deja de estar en el snapshot.
- **Dos búsquedas simultáneas** del mismo término sin snapshot: en esta spec ambas refrescan (resultado correcto, trabajo duplicado). Lo resuelve la spec 002.
- **Precio con más de 2 decimales** desde el scraper: se redondea HALF_UP a 2 decimales antes de comparar y guardar.

## Fuera de alcance

- Candado distribuido / request collapsing → spec 002.
- Job programado → spec 003.
- Matching entre tiendas y tabla `products` → spec futura (`product_id` queda `NULL`).
- Paginación más allá de la página 1.
- Endpoint de histórico de precios.
- Autenticación de usuarios finales y rate limiting del backend.

## Criterios de finalización

- Cada RF con al menos un test en verde (unitario o de integración con Testcontainers + WireMock).
- `./gradlew build` en verde.
- Migración Flyway aplicada sobre una BD vacía y `ddl-auto=validate` arranca sin errores.
- Prueba manual: búsqueda real de 3 términos contra los scrapers desplegados; la segunda búsqueda del mismo término no genera llamadas a scrapers (verificable en logs).

## Dudas abiertas

- ~~¿Java o Kotlin?~~ Resuelto: Kotlin (architecture.md D1).
- **[NECESITA ACLARACIÓN]** ¿El frontend pedirá el código postal al usuario o se usará uno fijo? Asumido: obligatorio en la API (los scrapers lo exigen); el frontend decide cómo obtenerlo.
