-- Keep existing rows intact. Stop with a safe diagnostic if historical invalid data needs review.
-- Flyway runs this migration in one transaction; exclude writes between inspection and validation.
LOCK TABLE fishingstory.catch_report_images IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM fishingstory.catch_report_images
        WHERE status = 'READY' AND display_order IS NULL
    ) THEN
        RAISE EXCEPTION 'READY image rows have no display_order; review existing rows before retrying V11';
    END IF;
END;
$$;

ALTER TABLE fishingstory.catch_report_images
    DROP CONSTRAINT ck_catch_report_images_order,
    ADD CONSTRAINT ck_catch_report_images_order CHECK (
        (status = 'READY' AND display_order IS NOT NULL AND display_order BETWEEN 1 AND 10)
        OR (status <> 'READY' AND display_order IS NULL)
    );
