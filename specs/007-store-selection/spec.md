# Spec 007 — Elegir en qué supermercados buscar

## Contexto y objetivo

El frontend deja marcar con qué supermercados comparar, pero el backend consultaba siempre los tres: los desmarcados gastaban llamadas a los scrapers (presupuesto de la spec 002) sin que nadie viera el resultado.

Petición del usuario (2026-10-20): *"estaria bien modificar el backend para que si un super no se quiere consultar, ahorrarnos esas llamadas"*.

## Requisitos funcionales (EARS)

- **RF-1:** CUANDO el cliente envíe `GET /api/v1/search` con `stores` (lista separada por comas de `mercadona`, `alcampo`, `dia`), EL SISTEMA buscará solo en esos supermercados.
- **RF-2:** Los supermercados no elegidos no se leen de PostgreSQL, no se llaman ni aparecen en `stores` ni en `comparison`. Los elegidos salen en el orden fijo de la spec 001, sin importar el orden pedido.
- **RF-3:** SI falta `stores` o está vacío, ENTONCES EL SISTEMA buscará en los tres, como hasta ahora.
- **RF-4:** SI `stores` contiene un valor desconocido, ENTONCES EL SISTEMA responderá `400` con `{"detail": "stores must be a comma-separated list of: mercadona, alcampo, dia"}`, sin llamar a ningún scraper ni contar la búsqueda.
- **RF-5:** SI todos los supermercados elegidos están caídos y sin datos, ENTONCES `502` como en la spec 001 RF-9.

## Fuera de alcance

- El job nocturno (spec 003) sigue refrescando los tres supermercados de los términos más buscados: las métricas no guardan qué supermercados se eligieron.

## Criterios de finalización

- Cada RF con al menos un test en verde; `./gradlew build` en verde.
