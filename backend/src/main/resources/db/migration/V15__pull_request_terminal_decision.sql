-- 保留未核验的历史状态为 NULL，不联网补齐，也不改变旧审查决定。
ALTER TABLE pull_request ADD COLUMN remote_state VARCHAR(16);
ALTER TABLE pull_request ADD CONSTRAINT ck_pull_request_remote_state
    CHECK (remote_state IS NULL OR remote_state IN ('OPEN', 'CLOSED', 'MERGED'));

ALTER TABLE review DROP CONSTRAINT ck_review_decision;
ALTER TABLE review ADD CONSTRAINT ck_review_decision
    CHECK (decision IN ('PENDING', 'APPROVE', 'REQUEST_CHANGES', 'REJECT'));
ALTER TABLE review DROP CONSTRAINT ck_review_decision_fields;
ALTER TABLE review ADD CONSTRAINT ck_review_decision_fields CHECK (
    (decision = 'PENDING' AND decision_by IS NULL AND decision_at IS NULL AND decision_comment IS NULL)
    OR (decision IN ('APPROVE', 'REQUEST_CHANGES', 'REJECT')
        AND decision_by IS NOT NULL AND decision_at IS NOT NULL));
