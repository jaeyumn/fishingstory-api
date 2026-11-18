CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;

CREATE TABLE fishingstory.users (
    user_id BIGINT PRIMARY KEY,
    nickname VARCHAR(20) NOT NULL,
    nickname_key VARCHAR(20) NOT NULL,
    nickname_changed_at TIMESTAMPTZ,
    age_confirmed_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    withdrawal_requested_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_users_nickname_key UNIQUE (nickname_key),
    CONSTRAINT ck_users_nickname CHECK (
        char_length(nickname) BETWEEN 2 AND 20
        AND nickname COLLATE "C" !~ '[^가-힣A-Za-z0-9 ]'
        AND nickname = btrim(nickname, ' ')
        AND position('  ' IN nickname) = 0
    ),
    CONSTRAINT ck_users_nickname_key CHECK (
        nickname_key = translate(nickname,
            'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')
    ),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'WITHDRAW_PENDING')),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT ck_users_withdrawal CHECK (
        (status = 'ACTIVE' AND withdrawal_requested_at IS NULL)
        OR (status = 'WITHDRAW_PENDING' AND withdrawal_requested_at IS NOT NULL)
    )
);

CREATE TABLE fishingstory.user_auth (
    user_auth_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL,
    email VARCHAR(320),
    provider_user_id VARCHAR(255),
    password_hash TEXT,
    email_verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_user_auth_user_id UNIQUE (user_id),
    CONSTRAINT ck_user_auth_provider CHECK (provider IN ('EMAIL', 'KAKAO', 'NAVER', 'GOOGLE')),
    CONSTRAINT ck_user_auth_email CHECK (
        email IS NULL OR (
            email <> ''
            AND email !~ '(^[[:space:]]|[[:space:]]$)'
            AND email = translate(email,
                'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')
        )
    ),
    CONSTRAINT ck_user_auth_credentials CHECK (
        (provider = 'EMAIL'
            AND email IS NOT NULL
            AND password_hash IS NOT NULL
            AND password_hash ~ '[^[:space:]]'
            AND email_verified_at IS NOT NULL
            AND provider_user_id IS NULL)
        OR
        (provider IN ('KAKAO', 'NAVER', 'GOOGLE')
            AND provider_user_id IS NOT NULL
            AND provider_user_id ~ '[^[:space:]]'
            AND password_hash IS NULL
            AND email_verified_at IS NULL)
    )
);

CREATE UNIQUE INDEX uq_user_auth_email
    ON fishingstory.user_auth (email)
    WHERE provider = 'EMAIL';

CREATE UNIQUE INDEX uq_user_auth_social_identity
    ON fishingstory.user_auth (provider, provider_user_id)
    WHERE provider IN ('KAKAO', 'NAVER', 'GOOGLE');

CREATE TABLE fishingstory.terms (
    terms_id BIGINT PRIMARY KEY,
    policy_type VARCHAR(30) NOT NULL,
    version VARCHAR(30) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    requires_consent BOOLEAN NOT NULL,
    is_required BOOLEAN NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_terms_policy_version UNIQUE (policy_type, version),
    CONSTRAINT uq_terms_policy_effective_at UNIQUE (policy_type, effective_at),
    CONSTRAINT ck_terms_policy_type CHECK (policy_type IN ('TERMS_OF_SERVICE', 'PRIVACY_POLICY')),
    CONSTRAINT ck_terms_version CHECK (version ~ '[^[:space:]]'),
    CONSTRAINT ck_terms_title CHECK (title ~ '[^[:space:]]'),
    CONSTRAINT ck_terms_content CHECK (content ~ '[^[:space:]]'),
    CONSTRAINT ck_terms_consent CHECK (
        (policy_type = 'TERMS_OF_SERVICE' AND requires_consent AND is_required)
        OR (policy_type = 'PRIVACY_POLICY' AND NOT requires_consent AND NOT is_required)
    ),
    CONSTRAINT ck_terms_publication CHECK (published_at IS NULL OR published_at <= effective_at)
);

CREATE TABLE fishingstory.user_terms_agreement_history (
    agreement_history_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    terms_id BIGINT NOT NULL,
    action VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_agreement_history_action CHECK (action IN ('AGREED', 'WITHDRAWN'))
);

CREATE INDEX idx_agreement_history_user_terms_recent
    ON fishingstory.user_terms_agreement_history
        (user_id, terms_id, created_at DESC, agreement_history_id DESC);
