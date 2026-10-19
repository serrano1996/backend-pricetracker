# backend-pricetracker

Backend del comparador de precios de supermercados (Mercadona, Alcampo, Dia). Unifica los tres scrapers en una búsqueda, persiste precios en PostgreSQL (Supabase) y guarda su histórico.

## Documentación

- [docs/constitution.md](docs/constitution.md) — reglas no negociables del proyecto.
- [docs/architecture.md](docs/architecture.md) — contexto, contrato de los scrapers, modelo de datos, despliegue, riesgos.
- Specs (cada una con `spec.md`, `plan.md`, `tasks.md`):
  - [001 — Búsqueda bajo demanda con persistencia](specs/001-backend-search-mvp/spec.md)
  - [002 — Request collapsing](specs/002-request-collapsing/spec.md)
  - [003 — Job nocturno de refresco](specs/003-smart-refresh-job/spec.md)

## Arranque local

Requisitos: JDK 25 (p. ej. `winget install EclipseAdoptium.Temurin.25.JDK`) y Docker.

1. Levantar PostgreSQL y Redis:

   ```bash
   docker compose up -d
   ```

2. Crear `.env` en la raíz (no se versiona; la app lo carga sola). Mínimo, los scrapers:

   ```properties
   SCRAPER_MERCADONA_URL=https://...
   SCRAPER_MERCADONA_API_KEY=...
   SCRAPER_ALCAMPO_URL=https://...
   SCRAPER_ALCAMPO_API_KEY=...
   SCRAPER_DIA_URL=https://...
   SCRAPER_DIA_API_KEY=...
   ```

   Base de datos y Redis apuntan por defecto a los de `docker-compose.yml`. El resto de variables, con sus valores por defecto, en [architecture.md §7](docs/architecture.md#7-despliegue). Las variables de entorno del sistema tienen prioridad sobre `.env`.

3. Arrancar:

   ```bash
   ./gradlew bootRun
   ```

   Flyway aplica las migraciones al arrancar. Sin las variables `SCRAPER_*` la app no arranca, a propósito.

4. Probar:

   ```bash
   curl "http://localhost:8080/api/v1/search?term=leche&postal_code=28001"
   curl http://localhost:8080/actuator/health
   ```

## Tests

```bash
./gradlew build
```

Necesita Docker: los tests de integración levantan PostgreSQL y Redis con Testcontainers y simulan los scrapers con WireMock.

## Estado

Specs 001, 002 y 003 completadas (001 verificada contra los scrapers reales). Pendientes en el roadmap: matching entre tiendas y endpoint de histórico ([architecture.md §9](docs/architecture.md#9-roadmap-de-specs)).
