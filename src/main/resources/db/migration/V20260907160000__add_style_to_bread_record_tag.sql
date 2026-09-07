-- 사진 위 빵 이름표의 꾸밈("모양.종이[.up]" 문자열). 앱이 정한 이름을 그대로 들고만 있는다.
-- 구버전 앱은 안 보내므로 nullable — 없는 태그는 기본 모양으로 보인다.
ALTER TABLE bread_record_tag ADD COLUMN style VARCHAR(50);
