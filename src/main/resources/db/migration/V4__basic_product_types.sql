-- Spec 008: basic groceries as product types, rules checked against live Mercadona and Dia names (2026-10-20, CP 28001).
-- Words are normalized (lowercase, no accents) and whole; "a|b" accepts either word (plurals, spellings); the most
-- specific type wins. Only kg and L: Alcampo's scraper gives no unit price for anything else, so ud/docena types
-- (eggs) would never compare. Exclusions keep apart products that are not equivalent (seeds, cooked pulses, flavours…).

insert into products (canonical_name, category, unit, match_all, match_none) values
    ('Pan de molde',                   'Pan',       'kg', '{pan,molde}',
        '{integral,sin,gluten,brioche,tostado,tostadas,hamburguesa,perritos,semillas,pipas,centeno,avena,espelta,cereales,multicereales,proteinas,rallado,artesano,rustico}'),
    ('Pan de molde integral',          'Pan',       'kg', '{pan,molde,integral}',
        '{sin,gluten,brioche,tostadas,semillas,pipas,centeno,avena,espelta,cereales,proteinas,artesano}'),
    ('Azúcar blanco',                  'Azúcar',    'kg', '{azucar,blanco}',  '{sobres,azucarillos,glas}'),
    ('Azúcar moreno',                  'Azúcar',    'kg', '{azucar,moreno}',  '{sobres,azucarillos,panela}'),
    ('Harina de trigo',                'Harina',    'kg', '{harina,trigo}',
        '{integral,espelta,fuerza,reposteria,fritos,frituras,semolosa,panecillo}'),
    ('Macarrones',                     'Pasta',     'kg', '{macarron|macarrones}',
        '{integral,integrales,gluten,sin,huevo,queso,cheese,salsa,gratinados,bolonesa,carbonara,tomate,frescos,lentejas,garbanzos,plato,preparados}'),
    ('Espaguetis',                     'Pasta',     'kg', '{spaghetti|espagueti|espaguetis}',
        '{integral,integrales,gluten,sin,huevo,queso,salsa,bolonesa,carbonara,tomate,frescos,lentejas,garbanzos,plato,preparados}'),
    ('Tomate frito',                   'Tomate',    'kg', '{tomate,frito}',
        '{receta,artesana,casero,tradicional,oliva,azucares,atun,sardinas,caballa,mejillones,macarrones,espaguetis,albondigas,pizza}'),
    ('Yogur natural',                  'Yogur',     'kg', '{yogur,natural}',
        '{azucar,azucarado,azucares,cana,edulcorado,griego,estilo,desnatado,mg,ligero,liquido,cremoso,cabra,oveja,bifidus,proteinas,soja,coco,avena,almendra,lactosa,fresa,limon,macedonia}'),
    ('Lentejas',                       'Legumbres', 'kg', '{lenteja|lentejas}',
        '{cocida,cocidas,guisadas,estofadas,riojana,jardinera,chorizo,verduras,verduritas,listo,plato,papilla,crema,snack,rojas,coral,beluga,pasta,harina,ensalada}'),
    ('Garbanzos',                      'Legumbres', 'kg', '{garbanzo|garbanzos}',
        '{cocido,cocidos,guisados,riojana,jardinera,plato,harina,hummus,pasta,espirales,espinacas,chorizo,ensalada,snack,tostados}'),
    ('Café molido natural',            'Café',      'kg', '{cafe,molido,natural}',
        '{descafeinado,capsulas,soluble,grano,mezcla,torrefacto,colombia,ecologico}'),
    ('Atún claro en aceite de oliva',  'Conservas', 'kg', '{atun,claro,aceite,oliva}',
        '{natural,escabeche,tomate,ensalada,pate,ventresca,aceitunas,jalapenos}'),
    ('Atún claro en aceite de girasol','Conservas', 'kg', '{atun,claro,aceite,girasol}',
        '{natural,escabeche,tomate,ensalada,pate,ventresca}'),
    ('Mantequilla',                    'Lácteos',   'kg', '{mantequilla}',
        '{light,ligera,untar,porciones,lactosa,galletas,cacahuete,croissant,croissants,rosquillas,palmeras,sabor,mantecados,pastas,bizcocho,ajo,hierbas}'),
    ('Agua mineral',                   'Agua',      'L',  '{agua,mineral}',   '{gas,sabor,limon}');
