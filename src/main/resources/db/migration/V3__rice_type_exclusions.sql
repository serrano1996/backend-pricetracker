-- Spec 004: false positives found with real data after V2 ("Vasos de arroz redondo…", "Arroz cocido redondo…").
-- Matching is by whole word, so plurals and cooked variants need their own exclusions.
update products
set match_none = match_none || array['vasos', 'vasitos', 'cocido', 'precocido']
where category = 'Arroz';
