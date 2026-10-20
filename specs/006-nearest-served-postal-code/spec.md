# Spec 006 — Precios de referencia del código postal servido más cercano

## Contexto y objetivo

Un supermercado puede no servir el código postal buscado aunque tenga tienda física en él: Dia responde "sin servicio" para Tarazona (50500), donde hay un Dia. Hoy esa tienda sale `not_served` y sin precios, y el usuario no puede comparar.

Petición del usuario (2026-10-20): *"vivo en tarazona, aqui hay un dia, aunque no tenga reparto querria saber precios para comparar"*; *"eso de que si no encuentra un supermercado en el cp indicado que busque en otro más cercano pero podria ser de forma general"*; *"prefiero la cercania por distancia"*. Se descartó un barrido de todos los códigos postales (10–30 h de llamadas, riesgo de bloqueo); el sistema aprende qué códigos postales sirve cada tienda a partir de las búsquedas.

Los precios de referencia son una aproximación: el precio online de otra zona puede no coincidir con el de la tienda física.

## Requisitos funcionales (EARS)

- **RF-1:** CUANDO una tienda responda `not_served` para el código postal buscado, EL SISTEMA buscará el mismo término en el código postal más cercano que esa tienda sirva y devolverá sus productos con `status: "ok"`.
- **RF-2:** EL SISTEMA medirá la cercanía como distancia en línea recta entre las coordenadas de los códigos postales (GeoNames, un punto medio por código postal).
- **RF-3:** Los candidatos serán los códigos postales donde la tienda respondió `ok` para cualquier término, más las capitales de provincia (`XX001`), sin los que solo respondieron `not_served`, ordenados del más cercano al más lejano.
- **RF-4:** EL SISTEMA probará como mucho `SEARCH_FALLBACK_MAX_PROBES` candidatos (por defecto 3) por tienda y búsqueda. SI ninguno sirve, ENTONCES la tienda seguirá `not_served`.
- **RF-5:** La respuesta de esa tienda incluirá `reference_postal_code` y `reference_distance_km` (km enteros); en el resto de casos, `null`.
- **RF-6:** Cada respuesta de un scraper se guarda bajo su propio código postal: el snapshot del código postal buscado sigue `not_served` y el del candidato guarda sus productos. Los candidatos pasan por la misma frescura, candado y presupuesto de llamadas que una búsqueda normal.
- **RF-7:** El job nocturno (spec 003) no busca en otros códigos postales.

## Formato de respuesta

Solo cambia cada elemento de `stores`:

```json
{ "store": "dia", "status": "ok", "stale": false, "fetched_at": "…", "products": [ … ],
  "reference_postal_code": "50001", "reference_distance_km": 76 }
```

## Casos límite

- **Código postal sin coordenadas** (no está en GeoNames): no hay candidatos, la tienda sigue `not_served`.
- **Zona sin ningún código postal servido cerca** (p. ej. Canarias en Dia): cada búsqueda prueba hasta 3 candidatos nuevos y los aprende como no servidos, así que las siguientes llegan antes al más lejano que sí sirve.
- **Un código postal que pasa a tener servicio** sigue excluido como candidato hasta que alguien busque desde él y responda `ok` (`ok` gana a `not_served`).

## Fuera de alcance

- Precios de la tienda física: Dia no los expone.
- Distancia por carretera.

## Regenerar las coordenadas

```bash
curl -O https://download.geonames.org/export/zip/ES.zip && unzip ES.zip ES.txt
awk -F'\t' '$2 ~ /^[0-9]{5}$/ && $10!="" {lat[$2]+=$10; lon[$2]+=$11; n[$2]++} END {for (c in n) printf "%s,%.4f,%.4f\n", c, lat[c]/n[c], lon[c]/n[c]}' ES.txt | sort > src/main/resources/geo/postal-codes-es.csv
```

## Criterios de finalización

- Cada RF con al menos un test en verde; `./gradlew build` en verde.
