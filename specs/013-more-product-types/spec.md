# Spec 013 — Más tipos de producto

## Contexto y objetivo

La comparación entre supermercados conocía 34 tipos (specs 004, 008 y 009). Petición del usuario (2026-10-23): *"quiero que añadas muchas más comparaciones en la tabla de comparciones"*. De la lista propuesta eligió *"Toda la lista"*.

## Requisitos funcionales (EARS)

- **RF-1:** EL SISTEMA incluirá 32 tipos más (`V6__more_product_types.sql`):
  - desayuno: cacao soluble, galletas María, copos de avena, mermelada de fresa, miel de flores, café soluble;
  - lácteos: leche entera y semidesnatada sin lactosa, nata para cocinar, queso rallado (mozzarella, 4 quesos y emmental), yogur griego natural;
  - bebidas vegetales y zumos: bebida de avena, bebida de soja, zumo de naranja;
  - despensa: sal fina, vinagre de vino, fideos, tomate triturado, mayonesa, ketchup, alubias blancas, garbanzos cocidos, lentejas cocidas;
  - conservas: atún al natural, sardinas en aceite, maíz dulce;
  - bebidas: refresco de cola, cerveza;
  - higiene: gel de ducha, champú.
- **RF-2:** Mismas reglas que las specs 008 y 009: palabras normalizadas y completas, alternativas con `|`, gana el tipo más específico, solo kg o L.

## Verificación

Reglas ajustadas con nombres reales de las tres tiendas (CP 28001, 2026-10-23), obtenidos con los scrapers levantados en local y reproduciendo el clasificador sobre ellos. Hallazgos que cambiaron las reglas:

- Productos infantiles con "galleta María" (papillas, tarritos, postres): se excluyen.
- Monodosis, sobres y formatos "pack" de mermelada: se excluyen, porque su precio por kg no es comparable.
- La cerveza "suave" y "aromatizada" y las "bebidas de zumo" son más baratas que el producto base y ganarían la comparación: se excluyen.
- Mayonesa, gel y champú van por litro en las tres tiendas (Mercadona y Dia dan `€/100 ml`, que se convierte a L).
- Un "queso rallado" genérico mezclaba mozzarella, 4 quesos, mezclas para fundir y pecorino: se divide en tres tipos. Mercadona y Dia tienen las mismas variedades, con el mismo precio en mozzarella (8,00 €/kg) y 4 quesos (8,75 €/kg).
- Excluir "1", "2", "3" y "en" para quitar los "2 en 1" descartaba formatos como "1 L": se excluye por palabras del producto (`acondicionador`, `gel`).

## Limitaciones conocidas

- Alcampo no da precio por litro de gel ni de champú: esos dos tipos solo comparan Mercadona y Dia (como el papel higiénico, spec 009).
- Alcampo bloqueó por su WAF los términos atún al natural, fideos, garbanzos cocidos, nata y queso rallado incluso tras reintentar: esos tipos se ajustaron solo con Mercadona y Dia.
- Nata para cocinar solo encaja en Dia: la de Mercadona es "ligera" (no equivalente).

## Fuera de alcance

- Fruta y verdura frescas, huevos y detergente: unidades no comparables entre tiendas (specs 008 y 009).
- Reclasificar los productos ya guardados: se clasifican en su próximo refresco (spec 004).

## Criterios de finalización

- Cada RF con al menos un test en verde; `./gradlew build` en verde.
