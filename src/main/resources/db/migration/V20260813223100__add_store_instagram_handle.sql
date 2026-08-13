-- 인스타 핸들 — dataops 확보분 4,468곳 적재용 (협의 안건 ⑤-2)
-- 갱신은 dataops_writer 의 기존 store UPDATE 권한으로 충분 (컬럼 단위 GRANT 불요)
ALTER TABLE store ADD COLUMN instagram_handle VARCHAR(100);
