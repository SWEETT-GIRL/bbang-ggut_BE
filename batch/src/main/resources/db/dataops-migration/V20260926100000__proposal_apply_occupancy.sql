-- 승인 전 점유 (v3 P1-1) — 운영 DML 전에 PENDING 을 APPLYING 으로 점유하고, 결과를 APPROVED/FAILED/UNKNOWN 으로 확정한다.
-- additive: 기존 행의 status 는 건드리지 않는다. 미확정(UNKNOWN)은 PENDING 으로 되돌리지 않는다 —
-- 되돌리면 운영에 이미 반영됐을 수 있는 대상을 다시 쓰게 된다. 재실행 대신 사람의 대사를 기다린다.

ALTER TABLE change_proposal ADD COLUMN IF NOT EXISTS operation_id UUID;
ALTER TABLE change_proposal ADD COLUMN IF NOT EXISTS apply_started_at TIMESTAMP;
-- 기계가 읽는 결과 사유 — CAS 충돌(운영 값이 바뀜)과 제안 만료·미지원 target 을 구분한다
ALTER TABLE change_proposal ADD COLUMN IF NOT EXISTS apply_outcome_reason VARCHAR(40);

-- 진행 중(PENDING·APPLYING)인 건은 store×target 당 1건. APPLYING 이 부분 유니크 밖으로 새면
-- 반영 중인 대상에 새 PENDING 이 생겨 같은 운영 필드를 두 번 쓰게 된다.
DROP INDEX IF EXISTS uq_proposal_pending;
CREATE UNIQUE INDEX IF NOT EXISTS uq_proposal_in_flight ON change_proposal (store_id, target)
    WHERE status IN ('PENDING', 'APPLYING');

-- 중단된 점유(프로세스 중단·응답 유실) 조회용 — P3 자동 대사의 입력
CREATE INDEX IF NOT EXISTS idx_proposal_applying ON change_proposal (apply_started_at)
    WHERE status = 'APPLYING';

-- 호환성 게이트 — 점유 단계를 모르는 구버전 서버/스크립트가 PENDING 을 바로 APPROVED 로 넘기는 것을 막는다.
-- 정상 경로는 PENDING → APPLYING → APPROVED 이고, 이 트리거는 중간 단계를 건너뛴 쓰기만 거부한다.
CREATE OR REPLACE FUNCTION change_proposal_require_apply_claim() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'PENDING' AND NEW.status = 'APPROVED' THEN
        RAISE EXCEPTION 'change_proposal %: PENDING -> APPROVED 직접 전이 금지 — APPLYING 점유를 거쳐야 한다', OLD.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_change_proposal_require_apply_claim ON change_proposal;
CREATE TRIGGER trg_change_proposal_require_apply_claim
    BEFORE UPDATE ON change_proposal
    FOR EACH ROW EXECUTE FUNCTION change_proposal_require_apply_claim();
