-- 체험 온보딩은 이제 빵로그를 만들지 않고 "마쳤다"만 남긴다. 그 뒤의 완료 행은 가리킬 기록이 없다.
-- 이 변경 전에 만든 체험 기록과 그 완료 행은 그대로 둔다.
ALTER TABLE member_tutorial_completion ALTER COLUMN record_id DROP NOT NULL;
