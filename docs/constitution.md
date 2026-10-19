# Constitución del proyecto — backend-pricetracker

1. **Stack fijo:** Kotlin 2.x, Spring Boot 4.x (Spring MVC + virtual threads), Gradle Kotlin DSL, Spring Data JPA, Flyway, Spring Data Redis, PostgreSQL (Supabase). No añadir librerías fuera de esto sin discutirlo (ver [architecture.md](architecture.md) D1–D3).
2. **Fuente de datos:** el backend solo consume los tres scrapers propios (`mercadona-scraper`, `alcampo-scraper`, `dia-scraper`) vía su contrato `GET /api/v1/products`. Nunca llama directamente a las webs/APIs de los supermercados.
3. **Fuente de verdad:** PostgreSQL es la fuente de verdad (catálogo, snapshots de búsqueda, histórico). Redis es solo coordinación (candados) y nunca guarda datos que no se puedan perder.
4. **Esquema por migraciones:** todo cambio de esquema va en una migración Flyway versionada. `spring.jpa.hibernate.ddl-auto=validate`, nunca `update`.
5. **Dinero:** precios en `NUMERIC(10,2)` en BD y `BigDecimal` en Kotlin. Prohibido `double`/`float` para precios.
6. **Tiempo:** todas las marcas de tiempo en `TIMESTAMPTZ` / `Instant` (UTC). Los cron se expresan en zona `Europe/Madrid`.
7. **Secretos:** URLs, API keys de scrapers y credenciales solo por variables de entorno. Nunca en el repo, nunca en logs.
8. **Tests:** cada RF tiene al menos un test. Unitarios con JUnit 5; integración con Testcontainers (PostgreSQL, Redis) y WireMock para los scrapers. PR sin test = no mergea.
9. **Degradación:** la caída de un supermercado, de Redis o de la escritura de métricas nunca tumba una búsqueda; se sirve lo que haya y se marca como obsoleto.
10. **Idioma:** código, identificadores, comentarios y commits en inglés (Conventional Commits); docs técnicas en español.
11. **Arquitectura:** paquetes por feature (screaming architecture) con capas `domain` / `application` / `infrastructure` / `web`. El dominio no depende de Spring ni de JPA.
12. **Kotlin idiomático:** nulabilidad explícita en lugar de `Optional`; `data class` para valores y DTOs, nunca para entidades JPA (plugins `kotlin-spring` y `kotlin-jpa`); prohibido `!!` fuera de tests.
