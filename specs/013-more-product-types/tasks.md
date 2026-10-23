# Tasks 013 — Más tipos de producto

- [x] **T1 — Migración V6 con 32 tipos** (RF-1, RF-2). Tests: `SchemaMigrationTest` (66 tipos), `StoreRefreshServiceTest` (60 nombres reales con su tipo o sin él). Dos expectativas previas cambian por los tipos nuevos: el garbanzo cocido de Luengo pasa a "Garbanzos cocidos" y el test de reclasificación usa la leche desnatada sin lactosa como producto sin tipo.

## Resultado (2026-10-23)

`./gradlew build` en verde (144 tests).
