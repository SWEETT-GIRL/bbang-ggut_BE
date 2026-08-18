-- 사진을 원본 비율로 보여주려고 가로·세로 픽셀을 받는다. 구버전 앱은 안 보내므로 nullable —
-- 없는 사진은 지금처럼 정사각형으로 보인다.
ALTER TABLE bread_record_photo ADD COLUMN width INTEGER;
ALTER TABLE bread_record_photo ADD COLUMN height INTEGER;
