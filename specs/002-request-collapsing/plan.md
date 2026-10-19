# Plan 002 — Request collapsing

Basado en [spec.md](spec.md) y en el plan de la [spec 001](../001-backend-search-mvp/plan.md).

## 1. Módulos

```
search/application/port/RefreshLockPort.kt       # tryAcquire(key, ttl): LockToken?; release(token)
search/infrastructure/lock/RedisRefreshLock.kt   # SET NX PX + liberación Lua compare-and-delete
search/application/SearchService.kt              # (modificado) envuelve el refresco de cada tienda con el candado
search/infrastructure/persistence/...              # (modificado) consulta FOR UPDATE para RF-7
```

## 2. Decisiones de diseño

### D1 — `SET key token NX PX ttl` + Lua para liberar
**Elegido:** Spring Data Redis `opsForValue().setIfAbsent(key, token, ttl)`; liberación con script `if get(key) == token then del(key)`.
**Descartado:** Redisson. Motivo: añade dependencia y un modelo (watchdog, fair locks) que no necesitamos; un candado con TTL y token cubre RF-1, RF-2, RF-6.
**Descartado:** Redlock multi-nodo. Motivo: una sola instancia de Redis; la corrección final la garantiza RF-7 en BD, no el candado.

### D2 — Esperar sondeando PostgreSQL, no pub/sub
**Elegido:** los que esperan leen el snapshot cada 250 ms.
**Descartado:** Redis pub/sub para avisar del fin del refresco. Motivo: más piezas móviles para ahorrar unas pocas consultas baratas por índice único; con virtual threads esperar no consume hilos de plataforma.

### D3 — Sin reintento automático tras fallo del titular
**Elegido:** si el titular falla, los que esperan sirven lo que haya al vencer (RF-4).
**Descartado:** que un esperador tome el candado y reintente. Motivo: si el scraper acaba de fallar, reintentar en segundos solo suma carga; la siguiente búsqueda del usuario lo reintentará.

### D4 — Clave con término normalizado
La clave usa el término ya normalizado (spec 001 RF-3). Longitud máxima ~140 bytes, aceptable para Redis.

## 3. Estrategia de test

- `RedisRefreshLock` con Testcontainers Redis: adquirir, segundo intento falla, liberar con token ajeno no borra, expiración por TTL.
- Integración concurrente: 20 hilos + WireMock con retardo 500 ms → 1 llamada por tienda.
- Redis parado (contenedor detenido) → 200 y warning.
- RF-7: dos refrescos forzados sin candado en paralelo → un solo histórico.
