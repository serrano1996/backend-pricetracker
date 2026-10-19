# Plan 004 — Matching por tipo de producto

Basado en [spec.md](spec.md).

## 1. Módulos

```
src/main/resources/db/migration/V2__product_types.sql   # columnas de reglas en products + tipos iniciales (RF-1)
search/domain/ProductTypeClassifier.kt                   # ProductType + normalización + clasificación pura (RF-2)
search/infrastructure/persistence/Entities.kt            # (añadido) ProductEntity con arrays de reglas
search/application/ProductTypeCatalog.kt                 # carga los tipos una vez y expone el clasificador
search/application/StoreRefreshService.kt                # (modificado) asigna product_id en cada upsert (RF-2, RF-3)
search/application/PriceComparator.kt                    # SearchResult → comparación por tipo (RF-4, RF-5)
search/web/…                                             # (modificado) sección `comparison` en la respuesta
```

## 2. Decisiones de diseño

### D1 — Reutilizar `products` como tabla de tipos
**Elegido:** cada fila de `products` es un tipo; `store_products.product_id` ya apunta a ella (V1).
**Descartado:** tabla nueva `product_types`. Motivo: `products` existía para esto ("catálogo unificado") y estaba vacía.

### D2 — Reglas por palabras completas, no similitud de texto
**Elegido:** `match_all` / `match_none` sobre el nombre normalizado, gana el tipo con más palabras obligatorias; empate → sin tipo.
**Descartado:** similitud difusa (Levenshtein, trigramas). Motivo: con nombres tan distintos entre tiendas da falsos positivos difíciles de explicar; las reglas son deterministas y cada fallo se corrige con una migración.

### D3 — La unidad del tipo debe coincidir con la del producto
Evita falsos positivos de ingredientes (`polvorones … con aceite de oliva`, en kg) y garantiza que la comparación por precio unitario es homogénea.

### D4 — Comparación calculada al responder, no persistida
`PriceComparator` trabaja sobre los productos de la respuesta (incluidos snapshots `stale`). Lo llama `SearchController`; `SearchService` y el job no cambian.

### D5 — Tipos cacheados en memoria
Solo cambian con migraciones (es decir, con un despliegue), así que `ProductTypeCatalog` los lee una vez.

## 3. Estrategia de test

- Unitarios del clasificador con los nombres reales de los casos límite de la spec.
- Migración: tipos sembrados con unidad y reglas.
- `StoreRefreshService`: asigna y reasigna `product_id` al refrescar.
- Unitarios de `PriceComparator` (más barata por tienda, orden, exclusión sin precio unitario).
- E2E en `SearchControllerTest`: `comparison` en el JSON.
