CREATE TABLE fishingstory.catch_reports (
    report_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    catch_status VARCHAR(20) NOT NULL,
    water_type VARCHAR(20) NOT NULL,
    fishing_date DATE NOT NULL,
    fishing_time TIME,
    memo VARCHAR(2000),
    visibility VARCHAR(20) NOT NULL DEFAULT 'PRIVATE',
    location_visibility VARCHAR(20) NOT NULL DEFAULT 'PRIVATE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_catch_reports_status CHECK (catch_status IN ('CAUGHT', 'NO_CATCH')),
    CONSTRAINT ck_catch_reports_water_type CHECK (water_type IN ('SEA', 'FRESHWATER')),
    CONSTRAINT ck_catch_reports_visibility CHECK (visibility IN ('PRIVATE', 'PUBLIC')),
    CONSTRAINT ck_catch_reports_location_visibility CHECK (
        location_visibility IN ('PRIVATE', 'REGION', 'PUBLIC')
    ),
    CONSTRAINT ck_catch_reports_memo CHECK (
        memo IS NULL OR char_length(memo) <= 2000
    )
);

CREATE INDEX idx_catch_reports_user_fishing_recent
    ON fishingstory.catch_reports (user_id, fishing_date DESC, created_at DESC, report_id DESC);
