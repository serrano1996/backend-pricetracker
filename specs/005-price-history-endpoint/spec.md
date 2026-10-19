# Spec 005 — Endpoint de histórico de precios

## Contexto y objetivo

Desde la spec 001 cada alta o cambio de precio queda en `price_history`, pero nada lo expone. El frontend necesita esa evolución para dibujar una gráfica por producto. El `id` que devuelve `GET /api/v1/search` en cada producto es la entrada natural.

## Actores

- **Usuario final** (vía frontend): abre un producto de los resultados y ve cómo ha cambiado su precio.

## Requisitos funcionales (EARS)

- **RF-1:** CUANDO el cliente envíe `GET /api/v1/products/{id}/price-history` con el `id` de un producto devuelto por la búsqueda, EL SISTEMA responderá `200 OK` con los datos actuales del producto y su histórico completo ordenado del más antiguo al más reciente.
- **RF-2:** SI el `id` no corresponde a ningún producto, ENTONCES EL SISTEMA responderá `404 Not Found` con `{"detail": "Product not found"}`.
- **RF-3:** SI el `id` no es un UUID, ENTONCES EL SISTEMA responderá `400 Bad Request` con `{"detail": "id must be a UUID"}`.
- **RF-4:** EL SISTEMA servirá esta consulta solo desde PostgreSQL: no llamará a ningún scraper ni registrará métricas de búsqueda.
- **RF-5:** EL SISTEMA aplicará a este endpoint la misma política CORS que al resto de `/api/**` (solo `GET`, solo orígenes configurados).

## Formato de respuesta (200 OK)

```json
{
  "product": {
    "id": "6f1c…",
    "store": "mercadona",
    "external_id": "10381",
    "name": "Leche entera Hacendado",
    "current_price": 0.99,
    "price_format": "0.99 €/L",
    "unit_price": 0.99,
    "unit_price_unit": "L",
    "image_url": "https://…",
    "category": "Leche",
    "last_checked_at": "2026-10-19T08:00:00Z"
  },
  "history": [
    { "price": 0.95, "unit_price": 0.95, "recorded_at": "2026-09-01T04:00:00Z" },
    { "price": 0.99, "unit_price": 0.99, "recorded_at": "2026-10-19T08:00:00Z" }
  ]
}
```

Cada punto marca el inicio de un precio: el precio vigente en una fecha es el del último punto anterior a ella.

## Casos límite

- **Producto con un solo precio:** `history` tiene un único punto (el alta).
- **`unit_price` desconocido:** `null` en el producto y en los puntos del histórico.

## Fuera de alcance

- Filtro por rango de fechas o paginación: hay una fila por cambio de precio, no por refresco, así que el histórico completo es pequeño.
- Histórico de un producto unificado entre tiendas (depende de la spec de matching).

## Criterios de finalización

- Cada RF con al menos un test en verde; `./gradlew build` en verde.
