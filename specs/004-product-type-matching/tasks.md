# Tasks 004 — Matching por tipo de producto

Desglose de [plan.md](plan.md). Requiere la spec 001 completada.

- [x] **T1 — Migración V2 + `ProductEntity`**
  Columnas `unit`, `match_all`, `match_none` y unicidad de `canonical_name`; tipos iniciales de la spec.
  RF: RF-1. Hecho cuando: test que lee los 11 tipos con unidad y reglas.

- [x] **T2 — `ProductTypeClassifier`**
  Depende: T1 (forma de los tipos). RF: RF-2.
  Hecho cuando: tests con cada caso límite de la spec, usando nombres reales.

- [x] **T3 — Clasificación en el refresco**
  `ProductTypeCatalog` + `StoreRefreshService` asigna `product_id`.
  Depende: T1, T2. RF: RF-2, RF-3.
  Hecho cuando: un producto nuevo queda tipado; si cambia su nombre o unidad, se reasigna o se queda sin tipo.

- [x] **T4 — `PriceComparator` + `comparison` en la búsqueda**
  Depende: T3. RF: RF-4, RF-5.
  Hecho cuando: tests unitarios del comparador y E2E con la sección `comparison` en el JSON.

## Resultado (2026-10-19)

`./gradlew build` en verde (113 tests). RED observado en cada tarea (columnas inexistentes, clasificador inexistente, `product_id` nulo, `comparison` ausente). Los 11 casos límite de la spec, con nombres reales de T13, se clasifican como se especificó. No verificado contra scrapers reales: los productos guardados antes de V2 siguen sin tipo hasta su próximo refresco.
