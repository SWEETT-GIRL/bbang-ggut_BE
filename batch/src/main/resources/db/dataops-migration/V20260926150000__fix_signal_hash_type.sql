-- store_signal.signal_hash 타입 교정 (v3 P2-2)
--
-- V20260926130000 에서 CHAR(64) 로 만들었는데 엔티티 매핑은 varchar(64) 라 Hibernate 스키마 검증이
-- 기동을 거부했다 (found [bpchar], expecting [varchar(64)]). 이미 적용된 마이그레이션은 손대지 않고
-- 여기서 타입만 맞춘다 — 적용된 파일을 고치면 flyway 체크섬이 깨져 기존 환경이 기동하지 못한다.
--
-- CHAR 는 값을 64자로 공백 패딩하므로 비교에서 트림 규칙에 기대게 된다. 해시는 고정 길이 16진 문자열이라
-- 패딩이 필요 없고, varchar 가 매핑과도 일치한다.
ALTER TABLE store_signal ALTER COLUMN signal_hash TYPE VARCHAR(64);
