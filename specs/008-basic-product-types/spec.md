# Spec 008 — Tipos de producto básicos

## Contexto y objetivo

La comparación entre supermercados (spec 004) solo conocía 11 tipos (leche, aceite, arroz): buscar "pan de molde" no comparaba nada. Petición del usuario (2026-10-20): *"busco pan de molde y no se muestra ninguna comparacion, porque?"*, *"añade un tanda de basicos"*.

## Requisitos funcionales (EARS)

- **RF-1:** EL SISTEMA incluirá 16 tipos básicos más (`V4__basic_product_types.sql`): pan de molde (normal e integral), azúcar (blanco y moreno), harina de trigo, macarrones, espaguetis, tomate frito, yogur natural, lentejas, garbanzos, café molido natural, atún claro (en aceite de oliva y de girasol), mantequilla y agua mineral.
- **RF-2:** Una palabra obligatoria de un tipo podrá tener alternativas separadas por `|` (`macarron|macarrones`, `spaghetti|espagueti|espaguetis`): las tiendas difieren en singular, plural y ortografía. Cuenta como una sola palabra para la especificidad.
- **RF-3:** Solo tipos por kg o L: el scraper de Alcampo no da precio unitario en otras unidades, así que un tipo por unidad o docena (huevos) nunca compararía las tres tiendas.

## Verificación

Reglas ajustadas con nombres reales de Mercadona y Dia (CP 28001, 2026-10-20); Alcampo estaba `unavailable` salvo en pan de molde y garbanzos. Hallazgos que cambiaron las reglas: Alcampo escribe "con corteza" en el pan normal (se excluye `sin`, no `corteza`); Mercadona usa singulares ("Macarrón", "Lenteja", "Garbanzo") y "Spaghetti"; los yogures "con azúcar de caña" y "0% MG" no son el natural.

## Fuera de alcance

- Huevos, detergente y otros productos por unidad o lavado (RF-3).
- Reclasificar los productos ya guardados: se clasifican en su próximo refresco (spec 004), así que una búsqueda reciente no compara hasta que caduca su snapshot (12 h).

## Criterios de finalización

- Cada RF con al menos un test en verde; `./gradlew build` en verde.
