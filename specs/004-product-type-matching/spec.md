# Spec 004 — Matching por tipo de producto

## Contexto y objetivo

La búsqueda devuelve hasta 50 productos por tienda, pero no dice dónde es más barato *lo mismo*. Investigación previa (2026-10-19, datos reales de T13 de la spec 001):

- Ningún supermercado expone EAN/GTIN: no hay clave exacta entre tiendas.
- Los nombres no coinciden: `CARBONELL Aceite de oliva suave botella 1 l.` (Alcampo) frente a `Aceite de oliva suave Carbonell 1 L` (Dia).
- La mayor parte del surtido son marcas propias (Hacendado, Dia, Alcampo), que por definición nunca coinciden entre tiendas.

**Decisión del usuario (2026-10-19):** "Equivalentes por tipo". El comparador agrupa productos por tipo y unidad (p. ej. *Leche entera · L*) y compara por precio unitario, incluidas las marcas propias. Responde a "¿dónde es más barata la leche entera?".

## Requisitos funcionales (EARS)

- **RF-1:** EL SISTEMA mantendrá un catálogo de tipos de producto en la tabla `products`, cada uno con nombre, categoría, unidad de comparación (`L`, `kg`…), palabras obligatorias (`match_all`) y palabras excluyentes (`match_none`). La lista inicial se carga por migración y se amplía con nuevas migraciones.
- **RF-2:** CUANDO un producto de tienda se guarde en un refresco, EL SISTEMA le asignará el tipo cuyas palabras obligatorias aparezcan todas como palabras completas en su nombre normalizado (minúsculas, sin acentos, sin puntuación), sin ninguna excluyente, y cuya unidad coincida con el `unit_price_unit` del producto. SI ningún tipo cumple, o varios empatan como más específicos (mismo número de palabras obligatorias), ENTONCES quedará sin tipo.
- **RF-3:** EL SISTEMA recalculará el tipo en cada refresco, porque nombre y precio unitario pueden cambiar.
- **RF-4:** CUANDO se responda a `GET /api/v1/search`, EL SISTEMA añadirá una sección `comparison`: por cada tipo presente en los productos de la respuesta, la oferta más barata de cada tienda por `unit_price`, con las ofertas ordenadas de menor a mayor precio unitario.
- **RF-5:** Los tipos de `comparison` se ordenarán por número de tiendas con oferta (desc) y después por nombre. Un producto sin precio unitario no entra en la comparación, aunque siga apareciendo en su tienda.

## Formato (añadido a la respuesta de la spec 001)

```json
"comparison": [
  {
    "type": "Leche entera",
    "unit": "L",
    "offers": [
      { "store": "dia",       "product_id": "…", "name": "Leche entera Dia Láctea 1 L",  "unit_price": 0.89, "price": 0.89 },
      { "store": "mercadona", "product_id": "…", "name": "Leche entera Hacendado",       "unit_price": 0.95, "price": 5.70 }
    ]
  }
]
```

## Casos límite (de datos reales)

- `Galletas cubiertas de chocolate con leche` → sin tipo (`leche entera` exige `entera`; además `chocolate` excluye).
- `Leche desnatada sin lactosa` → sin tipo en la lista inicial (`lactosa` excluye).
- `Aceite de oliva virgen extra en spray` → sin tipo (`spray` excluye).
- `Arroz basmati integral` → encaja en *Arroz basmati* y *Arroz integral* → excluyentes cruzadas → sin tipo.
- `Polvorones … con aceite de oliva … 310 g` → unidad `kg` ≠ `L` → sin tipo.
- `Leche semidesnatada` → solo *Leche semidesnatada*: `desnatada` no es palabra completa en `semidesnatada`.

## Fuera de alcance

- Matching exacto por marca y formato, sugerencias o curación manual.
- Backfill de productos ya guardados: producción aún no tiene datos; los locales se reclasifican al refrescarse.
- Edición de tipos por API.

## Criterios de finalización

- Cada RF y cada caso límite con test en verde; `./gradlew build` en verde.

## Asunciones

- **Lista inicial de tipos** (RF-1), derivada de los términos probados: leche entera / semidesnatada / desnatada; aceite de oliva virgen extra / de oliva / de girasol; arroz redondo / largo / basmati / integral / bomba.
