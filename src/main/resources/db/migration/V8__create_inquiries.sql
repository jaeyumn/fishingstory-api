CREATE TABLE fishingstory.inquiries (
    inquiry_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    request_key VARCHAR(32) NOT NULL,
    title VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_inquiries_request UNIQUE (user_id, request_key),
    CONSTRAINT ck_inquiries_title CHECK (char_length(btrim(title)) BETWEEN 1 AND 100),
    CONSTRAINT ck_inquiries_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_inquiries_request CHECK (request_key ~ '^[0-9a-f]{32}$')
);

CREATE TABLE fishingstory.inquiry_messages (
    message_id BIGINT PRIMARY KEY,
    inquiry_id BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    sender_role VARCHAR(20) NOT NULL,
    request_key VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_inquiry_messages_request UNIQUE (inquiry_id, sender_id, request_key),
    CONSTRAINT ck_inquiry_messages_role CHECK (sender_role IN ('USER', 'ADMIN')),
    CONSTRAINT ck_inquiry_messages_content CHECK (char_length(btrim(content)) BETWEEN 1 AND 2000),
    CONSTRAINT ck_inquiry_messages_request CHECK (request_key ~ '^[0-9a-f]{32}$')
);

CREATE INDEX idx_inquiries_owner_recent ON fishingstory.inquiries (user_id, updated_at DESC, inquiry_id DESC);
CREATE INDEX idx_inquiries_recent ON fishingstory.inquiries (updated_at DESC, inquiry_id DESC);
CREATE INDEX idx_inquiry_messages_recent ON fishingstory.inquiry_messages (inquiry_id, message_id DESC);
