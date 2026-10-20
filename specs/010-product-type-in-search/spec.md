# Spec 010 — Tipo de cada producto en la búsqueda

## Contexto y objetivo

La lista de la compra del frontend admite productos concretos de cada supermercado y los compara con sus equivalentes de las otras tiendas. Para encontrarlos necesita el tipo de cada producto, que hasta ahora solo aparecía en `comparison`, y solo para la oferta más barata de cada tienda. Petición del usuario (2026-10-20): *"me gustaria que en las lista de la comprar pueda añadir producto de los listado de los supers tambien y me salga una comparacion de precios con lista de un solo super, de cvarios supers más economica, etc"*.

## Requisitos funcionales (EARS)

- **RF-1:** EL SISTEMA incluirá en cada producto de `stores[].products` el campo `type` con el nombre de su tipo de producto (spec 004), el mismo que usa `comparison[].type`, o `null` si no encaja en ninguno.

## Criterios de finalización

- RF-1 con test en verde; `./gradlew build` en verde.
