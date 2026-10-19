-- Spec 004: products holds product types ("Leche entera" per L) matched by keyword rules.
-- Words are normalized: lowercase, no accents (see ProductTypeClassifier.normalize).
-- products is empty at this point everywhere, so NOT NULL needs no default.

alter table products
    add column unit       text   not null,
    add column match_all  text[] not null check (cardinality(match_all) > 0),
    add column match_none text[] not null default '{}',
    add constraint products_canonical_name_key unique (canonical_name);

insert into products (canonical_name, category, unit, match_all, match_none) values
    ('Leche entera',                 'Leche',  'L',  '{leche,entera}',               '{lactosa,chocolate,batido,condensada,evaporada,polvo,cabra,oveja,galletas,proteinas}'),
    ('Leche semidesnatada',          'Leche',  'L',  '{leche,semidesnatada}',        '{lactosa,chocolate,batido,condensada,evaporada,polvo,cabra,oveja,galletas,proteinas}'),
    ('Leche desnatada',              'Leche',  'L',  '{leche,desnatada}',            '{lactosa,chocolate,batido,condensada,evaporada,polvo,cabra,oveja,galletas,proteinas}'),
    ('Aceite de oliva virgen extra', 'Aceite', 'L',  '{aceite,oliva,virgen,extra}',  '{spray}'),
    ('Aceite de oliva',              'Aceite', 'L',  '{aceite,oliva}',               '{virgen,orujo,spray}'),
    ('Aceite de girasol',            'Aceite', 'L',  '{aceite,girasol}',             '{spray}'),
    ('Arroz redondo',                'Arroz',  'kg', '{arroz,redondo}',              '{tarrina,vasito,vaso,microondas,integral}'),
    ('Arroz largo',                  'Arroz',  'kg', '{arroz,largo}',                '{tarrina,vasito,vaso,microondas,integral,basmati}'),
    ('Arroz basmati',                'Arroz',  'kg', '{arroz,basmati}',              '{tarrina,vasito,vaso,microondas,integral}'),
    ('Arroz integral',               'Arroz',  'kg', '{arroz,integral}',             '{tarrina,vasito,vaso,microondas,basmati}'),
    ('Arroz bomba',                  'Arroz',  'kg', '{arroz,bomba}',                '{tarrina,vasito,vaso,microondas}');
