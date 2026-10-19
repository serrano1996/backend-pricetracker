# backend-pricetracker

Backend del comparador de precios de supermercados (Mercadona, Alcampo, Dia). Unifica los tres scrapers en una búsqueda, persiste precios en PostgreSQL (Supabase) y guarda su histórico.

## Documentación

- [docs/constitution.md](docs/constitution.md) — reglas no negociables del proyecto.
- [docs/architecture.md](docs/architecture.md) — contexto, contrato de los scrapers, modelo de datos, despliegue, riesgos.
- Specs (cada una con `spec.md`, `plan.md`, `tasks.md`):
  - [001 — Búsqueda bajo demanda con persistencia](specs/001-backend-search-mvp/spec.md)
  - [002 — Request collapsing](specs/002-request-collapsing/spec.md)
  - [003 — Job nocturno de refresco](specs/003-smart-refresh-job/spec.md)

## Estado

Documentación y specs redactadas. Implementación pendiente (empieza por [tasks 001](specs/001-backend-search-mvp/tasks.md)).
