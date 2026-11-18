ALTER TABLE fishingstory.users
    ADD COLUMN access_token_generation BIGINT NOT NULL DEFAULT 0
    CONSTRAINT ck_users_access_token_generation CHECK (access_token_generation >= 0);
