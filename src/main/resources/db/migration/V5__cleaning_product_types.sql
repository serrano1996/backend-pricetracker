-- Spec 009: cleaning products as product types, rules checked against live names of the three stores (2026-10-20,
-- CP 28001). Same rules as V4: normalized whole words, "a|b" alternatives, the most specific type wins.
-- Laundry detergent and softener are left out: Mercadona prices them per "lv", Dia per "lavado" and Alcampo per L,
-- and Mercadona's "lv" values match other stores' per-litre prices, so they would compare wrongly.

insert into products (canonical_name, category, unit, match_all, match_none) values
    ('Lejía',                        'Limpieza', 'L',  '{lejia}',
        '{detergente,deterlejia,perfumada,densa,denso,multiusos,gel,wc,limpiahogar,limpiador,lavadora,ropa,sin}'),
    -- Alcampo writes "Deterlejía": one word that stands for both required words
    ('Lejía con detergente',         'Limpieza', 'L',  '{lejia|deterlejia,detergente|deterlejia}',
        '{gel,wc,densa,denso,ropa,lavadora,limpiador,limpiahogar}'),
    ('Friegasuelos',                 'Limpieza', 'L',  '{friegasuelos|fregasuelos}',
        '{concentrado,insecticida,toallitas,abrillantador,delicadas,mascotas,madera,parquet}'),
    ('Lavavajillas a mano',          'Limpieza', 'L',  '{lavavajillas}',
        '{pastillas,capsulas,gel,maquina,maquinas,todo,sal,abrillantador,limpiamaquinas,ultra,concentrado,aloe}'),
    ('Limpiacristales',              'Limpieza', 'L',  '{limpiacristales}',
        '{mango,bayeta,toallitas,gamuza,multiusos}'),
    ('Limpiador multiusos',          'Limpieza', 'L',  '{multiusos}',
        '{desinfectante,antibacterias,lejia,cocina,limpiacristales,limpiahogar,concentrado,ph,toallitas}'),
    -- Per roll: only Mercadona and Dia give it, Alcampo's scraper has no unit price per unit
    ('Papel higiénico doble rollo',  'Limpieza', 'ud', '{papel,higienico,doble,rollo}',
        '{humedo,perfumado,capas,suave}');
