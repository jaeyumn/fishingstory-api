-- Test only: the original species 9999 does not exist.
INSERT INTO fishingstory.fish_species
    (species_id, name, name_key, scientific_name, water_type, display_order, entry_kind, parent_species_id)
VALUES (9001, '시험 품종', '시험 품종', 'Test cultivar', 'SEA', 9001, 'CULTIVAR', 9999);
