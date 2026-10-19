# Plan 005 — Endpoint de histórico de precios

Basado en [spec.md](spec.md) y en el modelo de datos de la [spec 001](../001-backend-search-mvp/plan.md#2-modelo-de-datos).

## 1. Módulos

```
history/
├── application/PriceHistoryService.kt   # carga producto + histórico en una transacción de solo lectura (RF-1, RF-2, RF-4)
└── web/
    └── PriceHistoryController.kt        # GET /api/v1/products/{id}/price-history + DTOs snake_case de la spec
search/infrastructure/persistence/Repositories.kt   # (añadido) PriceHistoryRepository.findByStoreProductIdOrderByRecordedAtAsc
search/web/ApiExceptionHandler.kt                   # (añadido) 404 producto, 400 id no UUID (RF-2, RF-3)
```

## 2. Decisiones de diseño

### D1 — Feature propia `history/`, reutilizando la persistencia de `search`
**Elegido:** paquete `history` con su servicio y controlador; lee los repositorios que ya existen.
**Descartado:** meterlo en `search`. Motivo: es otra capacidad (consultar, no buscar) y crecerá con la spec de matching.

### D2 — Sin filtro de fechas
**Elegido:** histórico completo.
**Descartado:** parámetro `days`/`from`. Motivo: una fila por cambio de precio (spec 001 RF-7) mantiene el volumen pequeño, y una gráfica necesita el precio vigente al inicio del rango, que un filtro recortaría.

### D3 — `id` como `UUID` en la firma del controlador
Spring convierte el path; un valor no UUID lanza `MethodArgumentTypeMismatchException`, que `ApiExceptionHandler` traduce a 400 (RF-3).

## 3. Estrategia de test

- Integración (`@SpringBootTest` + MockMvc + Testcontainers, `@Transactional`): datos sembrados con `StoreRefreshService` (alta + cambio de precio) → JSON completo y orden ascendente; 404; 400; sin métricas registradas.
