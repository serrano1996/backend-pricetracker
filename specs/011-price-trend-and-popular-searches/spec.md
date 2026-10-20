# Spec 011 — Último cambio de precio y búsquedas populares

## Contexto y objetivo

Petición del usuario (2026-10-20), sobre dos mejoras propuestas para el frontend: *"haz la 5 y 6"*: un indicador de bajada o subida de precio junto a cada producto, y las búsquedas populares como sugerencias del buscador.

## Requisitos funcionales (EARS)

- **RF-1:** EL SISTEMA incluirá en cada producto de `stores[].products` `previous_price` (precio antes del último cambio) y `price_changed_at` (desde cuándo rige el actual), leídos de `price_history` en una sola consulta por búsqueda.
- **RF-2:** SI el producto no ha cambiado de precio desde su alta, ENTONCES ambos campos serán `null`.
- **RF-3:** CUANDO el cliente envíe `GET /api/v1/searches/popular`, EL SISTEMA responderá `{"terms": [...]}` con los 10 términos más buscados entre los buscados en los últimos 30 días, sumando todos los códigos postales; a igual cuenta, el más reciente primero.
- **RF-4:** Consultar las búsquedas populares no cuenta como búsqueda.

## Casos límite

- Los contadores de `search_metrics` son acumulados: un término cuenta entero si se buscó al menos una vez en los últimos 30 días (anotado como `shortcut:` en el repositorio).

## Criterios de finalización

- Cada RF con al menos un test en verde; `./gradlew build` en verde.
