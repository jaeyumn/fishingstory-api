-- Test only: 1003 exists but has not been reviewed as a SPECIES (entry_kind is NULL).
INSERT INTO fishingstory.fish_species
    (species_id, name, name_key, scientific_name, water_type, display_order, entry_kind, parent_species_id)
VALUES (9001, '시험 품종', '시험 품종', 'Test cultivar', 'SEA', 9001, 'CULTIVAR', 1003);
