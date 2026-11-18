-- Recorded weights stay at most 2,000,000 g (2 t), above any rod-caught record, like the 1,000 cm length limit.
-- Keep existing rows intact. Stop with a safe diagnostic if historical weights need review.
-- Flyway runs this migration in one transaction; exclude writes between inspection and validation.
LOCK TABLE fishingstory.catch_report_species, fishingstory.user_fish_collection IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM fishingstory.catch_report_species WHERE max_weight_g > 2000000.0)
            OR EXISTS (SELECT 1 FROM fishingstory.user_fish_collection WHERE max_weight_g > 2000000.0) THEN
        RAISE EXCEPTION 'Weights above 2,000,000 g exist; review existing rows before retrying V13';
    END IF;
END;
$$;

ALTER TABLE fishingstory.catch_report_species
    DROP CONSTRAINT ck_catch_report_species_weight,
    ADD CONSTRAINT ck_catch_report_species_weight CHECK (
        max_weight_g IS NULL OR (max_weight_g > 0 AND max_weight_g <= 2000000.0)
    );
ALTER TABLE fishingstory.user_fish_collection
    DROP CONSTRAINT ck_user_fish_collection_weight,
    ADD CONSTRAINT ck_user_fish_collection_weight CHECK (
        max_weight_g IS NULL OR (max_weight_g > 0 AND max_weight_g <= 2000000.0)
    );
