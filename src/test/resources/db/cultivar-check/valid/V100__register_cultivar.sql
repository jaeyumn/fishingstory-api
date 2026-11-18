-- Test only: the original is reviewed as a SPECIES in the same migration as its cultivar.
UPDATE fishingstory.fish_species SET entry_kind = 'SPECIES' WHERE species_id = 1003;
INSERT INTO fishingstory.fish_species
    (species_id, name, name_key, scientific_name, water_type, display_order, entry_kind, parent_species_id)
VALUES (9001, '시험 품종', '시험 품종', 'Sebastes schlegelii', 'SEA', 9001, 'CULTIVAR', 1003);
