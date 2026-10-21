# Spec 012 — Precios actuales de varios productos

## Contexto y objetivo

La lista de la compra del frontend refresca sus precios al abrirse. Con el endpoint de histórico (spec 005) hacía una petición por producto. Petición del usuario (2026-10-21): *"haz el endpoint para mejorar el rendimiento"*.

## Requisitos funcionales (EARS)

- **RF-1:** CUANDO el cliente envíe `GET /api/v1/products?ids=<uuid>,<uuid>,…`, EL SISTEMA responderá `200 OK` con `{"products": [...]}`: los datos actuales de cada producto, con la misma forma que `product` en la spec 005, en el orden pedido y sin repetir. Los `id` que no existan se omiten.
- **RF-2:** SI falta `ids`, ENTONCES `400` con `{"detail": "ids is required"}`.
- **RF-3:** SI `ids` no son entre 1 y 100 UUID separados por comas, ENTONCES `400` con `{"detail": "ids must be 1 to 100 comma-separated UUIDs"}`.
- **RF-4:** EL SISTEMA servirá esta consulta solo desde PostgreSQL, en una sola consulta, sin llamar a scrapers ni registrar métricas.

## Criterios de finalización

- Cada RF con al menos un test en verde; `./gradlew build` en verde.
