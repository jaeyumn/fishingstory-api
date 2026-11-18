-- No species, taxonomy, regulation values or historical records are populated here.
ALTER TABLE fishingstory.fish_species
    DROP CONSTRAINT uq_fish_species_scientific_name,
    ADD COLUMN standard_name VARCHAR(100),
    ADD COLUMN family_name VARCHAR(100),
    ADD COLUMN family_scientific_name VARCHAR(200),
    ADD COLUMN genus_name VARCHAR(100),
    ADD COLUMN genus_scientific_name VARCHAR(200),
    ADD COLUMN entry_kind VARCHAR(20),
    ADD COLUMN parent_species_id BIGINT,
    ADD COLUMN identification TEXT,
    ADD COLUMN habitat TEXT,
    ADD COLUMN distribution TEXT,
    ADD COLUMN fishing_notes TEXT,
    ADD COLUMN similar_species TEXT,
    ADD CONSTRAINT ck_fish_species_kind CHECK (entry_kind IN ('SPECIES', 'CULTIVAR')),
    ADD CONSTRAINT ck_fish_species_parent CHECK (
        (entry_kind IS NOT NULL AND entry_kind = 'CULTIVAR' AND parent_species_id IS NOT NULL AND parent_species_id <> species_id)
        OR (parent_species_id IS NULL AND (entry_kind IS NULL OR entry_kind = 'SPECIES'))
    );

ALTER TABLE fishingstory.fish_species_alias DROP CONSTRAINT uq_fish_species_alias_key;

ALTER TABLE fishingstory.catch_report_species
    ADD COLUMN max_weight_g NUMERIC(12,1),
    ADD CONSTRAINT ck_catch_report_species_weight CHECK (max_weight_g IS NULL OR max_weight_g > 0);
ALTER TABLE fishingstory.user_fish_collection
    ADD COLUMN max_weight_g NUMERIC(12,1),
    ADD CONSTRAINT ck_user_fish_collection_weight CHECK (max_weight_g IS NULL OR max_weight_g > 0);

CREATE TABLE fishingstory.fish_species_regulation (
    regulation_id BIGINT PRIMARY KEY,
    species_id BIGINT NOT NULL,
    regulation_type VARCHAR(30) NOT NULL,
    verification_status VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN',
    summary TEXT,
    region TEXT,
    period_description TEXT,
    minimum_value NUMERIC(12,1),
    unit VARCHAR(30),
    exceptions TEXT,
    legal_basis TEXT,
    source_url TEXT,
    checked_on DATE,
    effective_from DATE,
    effective_until DATE,
    CONSTRAINT ck_species_regulation_type CHECK (regulation_type IN
        ('CLOSED_SEASON', 'MINIMUM_SIZE', 'PROTECTION', 'CAPTURE_BAN', 'OTHER')),
    CONSTRAINT ck_species_regulation_status CHECK (verification_status IN
        ('UNKNOWN', 'VERIFIED', 'NO_RULE', 'NOT_APPLICABLE')),
    CONSTRAINT ck_species_regulation_evidence CHECK (verification_status = 'UNKNOWN' OR
        (legal_basis IS NOT NULL AND btrim(legal_basis) <> '' AND source_url IS NOT NULL
         AND source_url ~ '^https://[^[:space:]]+$' AND checked_on IS NOT NULL
         AND region IS NOT NULL AND btrim(region) <> ''
         AND (verification_status <> 'VERIFIED' OR (summary IS NOT NULL AND btrim(summary) <> '')))),
    CONSTRAINT ck_species_regulation_dates CHECK
        (effective_from IS NULL OR effective_until IS NULL OR effective_from <= effective_until),
    CONSTRAINT ck_species_regulation_value CHECK (minimum_value IS NULL OR (minimum_value > 0
        AND unit IS NOT NULL AND btrim(unit) <> ''))
);
CREATE INDEX idx_species_regulation_species ON fishingstory.fish_species_regulation (species_id);
