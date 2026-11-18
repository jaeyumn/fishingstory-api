ALTER TABLE fishingstory.catch_report_species
    ALTER COLUMN species_id DROP NOT NULL,
    ADD COLUMN custom_name VARCHAR(100),
    ADD CONSTRAINT ck_catch_report_species_source CHECK (
        (species_id IS NOT NULL AND custom_name IS NULL)
        OR (species_id IS NULL AND custom_name IS NOT NULL
            AND char_length(btrim(custom_name)) BETWEEN 1 AND 100)
    ),
    ADD CONSTRAINT uq_catch_report_custom_species UNIQUE (report_id, custom_name);
