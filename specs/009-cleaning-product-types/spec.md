# Spec 009 — Tipos de producto de limpieza

## Contexto y objetivo

Tras los básicos de alimentación (spec 008), el usuario pidió (2026-10-20): *"añade tambien productos de limpieza"*.

## Requisitos funcionales (EARS)

- **RF-1:** EL SISTEMA incluirá 7 tipos de limpieza (`V5__cleaning_product_types.sql`): lejía, lejía con detergente, friegasuelos, lavavajillas a mano, limpiacristales y limpiador multiusos (por L, en las tres tiendas), y papel higiénico doble rollo (por unidad).
- **RF-2:** El papel higiénico es la excepción a la spec 008 RF-3: se compara por unidad (rollo) y solo entre Mercadona y Dia, porque el scraper de Alcampo no da precio por unidad.

## Verificación

Reglas ajustadas con nombres reales de las tres tiendas (CP 28001, 2026-10-20). Hallazgos: Alcampo escribe "Fregasuelos" y "Deterlejía" (una sola palabra, que cubre las dos obligatorias de la lejía con detergente); la lejía normal cuesta lo mismo en las tres (0,40 €/L), lo que confirma que el tipo agrupa productos equivalentes.

## Fuera de alcance

- **Detergente y suavizante de ropa:** Mercadona da el precio por "lv", Dia por "lavado" y Alcampo por L; además los "lv" de Mercadona coinciden con precios por litro de otras tiendas (gel lavavajillas: Mercadona 5,07 €/lv, Alcampo 5,07 €/L), así que compararlos daría resultados falsos.
- Pastillas y gel de lavavajillas a máquina, toallitas, bayetas y papel de cocina (precio por unidad poco comparable entre formatos).

## Criterios de finalización

- Cada RF con al menos un test en verde; `./gradlew build` en verde.
