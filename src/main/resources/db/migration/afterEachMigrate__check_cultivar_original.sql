-- Species rows are registered only through migrations and have no physical FK, so Flyway runs this
-- after every migration. On PostgreSQL it shares the migration's transaction: a migration that leaves
-- a cultivar without an existing reviewed SPECIES original (active while the cultivar is active)
-- rolls back with a diagnostic instead of being recorded.
-- Early migrations run before fish_species gained its V12 columns, so the check waits for them.
DO $$
DECLARE
    invalid_id BIGINT;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'fishingstory' AND table_name = 'fish_species'
            AND column_name = 'parent_species_id'
    ) THEN
        RETURN;
    END IF;
    EXECUTE '
        SELECT cultivar.species_id
        FROM fishingstory.fish_species cultivar
        LEFT JOIN fishingstory.fish_species original ON original.species_id = cultivar.parent_species_id
        WHERE cultivar.entry_kind = ''CULTIVAR''
            AND (original.species_id IS NULL
                OR original.entry_kind IS DISTINCT FROM ''SPECIES''
                OR (cultivar.active AND NOT original.active))
        ORDER BY cultivar.species_id
        LIMIT 1'
    INTO invalid_id;
    IF invalid_id IS NOT NULL THEN
        RAISE EXCEPTION 'Cultivar % needs an existing SPECIES original that is active while the cultivar is active; review the migration before retrying', invalid_id;
    END IF;
END;
$$;
