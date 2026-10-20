# Tasks 006 — Precios de referencia del código postal servido más cercano

- [x] **T1 — `PostalCodeLocator` y coordenadas de GeoNames**
  RF: RF-2, RF-3. Tests: `PostalCodeLocatorTest`, `PostalCodeLocatorConfigTest` (52 capitales; desde 50500, Soria, Zaragoza, Logroño).

- [x] **T2 — Búsqueda en el código postal servido más cercano**
  RF: RF-1, RF-3..RF-7. Tests: `SearchServiceTest` (más cercano servido, aprende no servidos entre términos, usa un servido que no es capital, límite de pruebas), `SearchControllerTest` (campos `reference_*`).

## Resultado (2026-10-20)

`./gradlew build` en verde (131 tests). Sin RED previo: los tests se escribieron junto con la implementación. Primera ejecución: un fallo en la distancia esperada a Madrid del test (232 km reales, no 234).
