ALTER TABLE fishingstory.catch_report_images
    ADD COLUMN request_key VARCHAR(32)
    CONSTRAINT ck_catch_report_images_request_key CHECK (request_key ~ '^[0-9a-f]{32}$');

CREATE UNIQUE INDEX uq_catch_report_images_request
    ON fishingstory.catch_report_images (user_id, report_id, request_key)
    WHERE request_key IS NOT NULL;
