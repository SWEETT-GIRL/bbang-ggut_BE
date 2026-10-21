-- 빵집다움 점수 — 후보 풀에서 분식·치킨·젤라또처럼 빵 메뉴를 조금 파는 가게를 삭제 대신 점수로 내리기 위한 컬럼 (설계 §5.3 수정안 ①, 2026-09-04 페르소나 실측).
-- 0~1: category(빵집형 카테고리) + 빵 종류 메뉴 비중 + votedKeyword bread_good 비율. 계산은 scripts/store_profile_batch.py.
ALTER TABLE store_profile_draft ADD COLUMN bakery_score NUMERIC(4,3);
ALTER TABLE store_profile_draft ADD COLUMN store_status VARCHAR(20);
