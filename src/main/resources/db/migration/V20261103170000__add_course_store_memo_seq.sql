-- 메모의 순서 기준(seq). 앱이 (정류장 × 회원) 필드마다 만드는 하이브리드 논리 시계로,
-- 초안 버퍼(Redis)에서 오는 글과 PATCH 로 바로 오는 글을 같은 자로 비교해 "더 새로운 글"만 남긴다.
-- 기존 행은 0 — 어떤 새 seq 보다 작아서 첫 초안·첫 PATCH 가 그대로 반영된다.
ALTER TABLE course_store_memos ADD COLUMN seq BIGINT NOT NULL DEFAULT 0;
