# Spec 003 — Job nocturno de refresco de términos populares

## Contexto y objetivo

La búsqueda bajo demanda (spec 001) hace esperar al primer usuario que busca un término caducado. Refrescando de madrugada los términos más buscados, la mayoría de búsquedas del día se sirven desde BD. El job además mantiene activa la BD de Supabase free y despierta los scrapers.

## Requisitos funcionales (EARS)

- **RF-1:** EL SISTEMA ejecutará el job según `REFRESH_JOB_CRON` (por defecto `0 0 4 * * *`, zona `Europe/Madrid`).
- **RF-2:** CUANDO arranque, EL SISTEMA seleccionará los `REFRESH_JOB_TOP_N` (por defecto 50) pares `(término, CP)` de `search_metrics` con mayor `search_count` cuyo `last_searched_at` sea de los últimos `REFRESH_JOB_RECENCY` (por defecto 30 días).
- **RF-3:** EL SISTEMA refrescará cada par **secuencialmente**, reutilizando el mismo camino de refresco y candado que las búsquedas (spec 001 RF-5..RF-7, spec 002), sin incrementar `search_metrics`.
- **RF-4:** EL SISTEMA esperará entre pares un retraso aleatorio uniforme entre `REFRESH_JOB_MIN_DELAY` y `REFRESH_JOB_MAX_DELAY` (por defecto 3 s y 10 s).
- **RF-5:** SI un par ya tiene snapshot fresco para una tienda (refrescado por un usuario hace poco), ENTONCES el job omitirá esa tienda.
- **RF-6:** SI una tienda falla para un par, ENTONCES el job registra el fallo y continúa con el siguiente. SI una tienda falla `REFRESH_JOB_STORE_FAILURE_LIMIT` veces seguidas (por defecto 5), ENTONCES el job deja de llamarla en esa ejecución.
- **RF-7:** EL SISTEMA garantizará que solo una instancia ejecuta el job a la vez mediante un candado Redis `pt:lock:job:refresh-top` con TTL mayor que la duración máxima esperada. SI Redis no está disponible, ENTONCES el job no se ejecuta y se registra un *warning*.
- **RF-8:** AL terminar, EL SISTEMA registrará un resumen: pares procesados, tiendas refrescadas, omitidas, fallidas y duración.
- **RF-9:** EL SISTEMA permitirá desactivar el job con `REFRESH_JOB_ENABLED=false`.
- **RF-10:** ANTES de procesar el primer par, EL SISTEMA despertará cada scraper con `GET /health` (timeout `REFRESH_JOB_READ_TIMEOUT`, por defecto 90 s) y usará ese mismo timeout en las llamadas del job. Un scraper que no despierte cuenta como fallo de tienda (RF-6). (Los scrapers están en Render free, architecture.md §8.)

## Casos límite

- **`search_metrics` vacía:** el job termina sin llamadas y lo registra.
- **El job tarda más que su candado:** el TTL del candado se calcula como `90 s + TOP_N × (MAX_DELAY + REFRESH_JOB_READ_TIMEOUT)` (las tres tiendas de un par van en paralelo); con los valores por defecto, ~85 min.
- **Despliegue durante el job:** la ejecución se pierde; la siguiente noche se repite. Sin reanudación.

## Fuera de alcance

- Refrescar páginas más allá de la 1.
- Endpoint para lanzar el job manualmente.
- Purga de histórico o de métricas antiguas.

## Criterios de finalización

- Test con `Random` y `Clock` inyectados: orden de selección, retrasos dentro del rango, omisión de frescos, corte por fallos consecutivos.
- Test de candado: dos ejecuciones simultáneas → solo una procesa.
