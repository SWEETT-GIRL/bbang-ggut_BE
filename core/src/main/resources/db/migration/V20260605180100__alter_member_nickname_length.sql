-- nickname 컬럼 길이 축소: VARCHAR(255) → VARCHAR(100)
-- 선행 조건: NicknameMigrationRunner 실행 완료 (암호화 값 → 평문 닉네임 변환 후 적용)
ALTER TABLE member ALTER COLUMN nickname TYPE VARCHAR(100);
