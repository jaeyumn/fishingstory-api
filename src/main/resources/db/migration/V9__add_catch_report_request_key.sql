-- 결과가 불확실한 작성 재시도가 같은 회원의 조황을 중복 생성하지 않도록 기기 생성 키를 보관한다.
-- 기존 행과 키 없이 보낸 요청은 NULL로 남으며 멱등성을 적용하지 않는다.
ALTER TABLE fishingstory.catch_reports
    ADD COLUMN request_key VARCHAR(32),
    ADD CONSTRAINT ck_catch_reports_request_key CHECK (
        request_key IS NULL OR request_key ~ '^[0-9a-f]{32}$'
    );

CREATE UNIQUE INDEX uq_catch_reports_request
    ON fishingstory.catch_reports (user_id, request_key)
    WHERE request_key IS NOT NULL;
