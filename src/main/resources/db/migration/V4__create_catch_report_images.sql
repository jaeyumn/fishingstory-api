CREATE TABLE fishingstory.catch_report_images (
    image_id BIGINT PRIMARY KEY,
    report_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    master_object_key VARCHAR(300) NOT NULL,
    thumbnail_object_key VARCHAR(300) NOT NULL,
    status VARCHAR(20) NOT NULL,
    temporary_cleaned BOOLEAN NOT NULL DEFAULT FALSE,
    display_order INTEGER,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_catch_report_images_master_key UNIQUE (master_object_key),
    CONSTRAINT uq_catch_report_images_thumbnail_key UNIQUE (thumbnail_object_key),
    CONSTRAINT ck_catch_report_images_status CHECK (status IN ('PENDING', 'READY', 'DELETE_PENDING')),
    CONSTRAINT ck_catch_report_images_order CHECK (
        (status = 'READY' AND display_order BETWEEN 1 AND 10)
        OR (status <> 'READY' AND display_order IS NULL)
    )
);

CREATE INDEX idx_catch_report_images_report
    ON fishingstory.catch_report_images (report_id, status, display_order);
CREATE INDEX idx_catch_report_images_cleanup
    ON fishingstory.catch_report_images (status, expires_at);
