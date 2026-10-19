# Spec 002 — Request collapsing con candado distribuido

## Contexto y objetivo

Con la spec 001, N búsquedas simultáneas del mismo término caducado disparan N refrescos de la misma tienda: N llamadas al scraper, N transacciones de *upsert* y riesgo de históricos duplicados si dos transacciones ven el precio antiguo a la vez.

Los scrapers ya colapsan sus propias peticiones en vuelo, así que el objetivo aquí **no** es proteger al supermercado, sino que el backend haga **un solo refresco** por `(término, código postal, tienda)` aunque tenga varias instancias.

## Requisitos funcionales (EARS)

- **RF-1:** CUANDO una búsqueda necesite refrescar `(término, CP, tienda)`, EL SISTEMA intentará adquirir en Redis el candado `pt:lock:refresh:{tienda}:{cp}:{término}` con TTL `LOCK_TTL` (por defecto 30 s).
- **RF-2:** SI adquiere el candado, ENTONCES refresca (spec 001 RF-5..RF-7) y lo libera solo si sigue siendo suyo (comparación por token).
- **RF-3:** SI no lo adquiere, ENTONCES esperará consultando el snapshot en PostgreSQL cada `LOCK_POLL_INTERVAL` (por defecto 250 ms) hasta que sea fresco o pasen `LOCK_WAIT_TIMEOUT` (por defecto 15 s), sin llamar al scraper.
- **RF-4:** SI vence la espera, ENTONCES servirá el snapshot existente con `stale: true` o, si no existe, `status: "unavailable"` (mismo comportamiento que spec 001 RF-8).
- **RF-5:** SI Redis no está disponible, ENTONCES EL SISTEMA refrescará sin candado (comportamiento de spec 001) y registrará un *warning*. Redis caído nunca tumba una búsqueda.
- **RF-6:** CUANDO el titular del candado termine, con éxito o con error, EL SISTEMA liberará el candado; si el proceso muere, el TTL lo libera.
- **RF-7:** EL SISTEMA garantizará que el histórico no se duplica aunque dos refrescos lleguen a ejecutarse (candado expirado, Redis caído): la comparación con `current_price` se hace sobre la fila bloqueada (`SELECT … FOR UPDATE`) dentro de la transacción.

## Casos límite

- **Refresco más lento que `LOCK_TTL`:** el candado expira y otra petición puede refrescar; RF-7 evita la corrupción. `LOCK_TTL` debe ser mayor que el timeout de lectura del scraper (25 s por defecto → `LOCK_TTL` 30 s; el job usa 90 s y su propio TTL, ver spec 003).
- **El titular falla:** libera el candado (RF-6), los que esperan ven el snapshot sin cambios y caen en RF-4 al vencer, o uno de ellos adquiere el candado en su siguiente sondeo si se decide reintentar (ver plan D3).

## Fuera de alcance

- Rate limiting por IP del backend.
- Caché de respuestas en Redis (PostgreSQL ya es la caché, constitución #3).

## Criterios de finalización

- Test de integración: 20 búsquedas concurrentes del mismo término caducado → exactamente 1 llamada por tienda en WireMock y 1 registro de histórico por producto cambiado.
- Test con Redis parado: la búsqueda responde 200.
