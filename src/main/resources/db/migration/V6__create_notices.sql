CREATE TABLE fishingstory.notices (
    notice_id BIGINT PRIMARY KEY,
    title VARCHAR(100) NOT NULL,
    content TEXT NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_notices_title CHECK (char_length(btrim(title)) BETWEEN 1 AND 100),
    CONSTRAINT ck_notices_content CHECK (char_length(btrim(content)) BETWEEN 1 AND 20000)
);

CREATE INDEX idx_notices_published_order
    ON fishingstory.notices (pinned DESC, notice_id DESC) WHERE published;
