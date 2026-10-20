# Tasks 007 — Elegir en qué supermercados buscar

- [x] **T1 — Parámetro `stores` en la búsqueda**
  RF: RF-1..RF-5. Tests: `StoreNameTest` (selección, valores vacíos, desconocidos), `SearchServiceTest` (solo los elegidos, 502 con los elegidos caídos), `SearchControllerTest` (no llama al no elegido, 400 sin llamadas ni métrica).

## Resultado (2026-10-20)

`./gradlew build` en verde (138 tests). Sin RED previo: tests escritos junto con la implementación.
