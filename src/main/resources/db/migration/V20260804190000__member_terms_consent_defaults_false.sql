-- 계정 생성과 약관 동의는 다른 사실이다.
-- member.agree_to_terms 의 DB 기본값이 TRUE라, JPA를 거치지 않고 행이 들어오면
-- 동의한 적 없는 계정이 '동의함'으로 기록된다. 나머지 동의 컬럼과 같게 FALSE로 맞춘다.
-- 동의는 POST /users/me/consent (Member.applyInitialConsent) 가 받은 뒤에만 채운다.
ALTER TABLE member ALTER COLUMN agree_to_terms SET DEFAULT FALSE;

-- 주의: 이 마이그레이션은 기존 행을 건드리지 않는다.
-- 2026-07-27 이전에 만들어진 게스트와, 지금까지 소셜로 가입한 회원은
-- agree_to_terms = TRUE, terms_agreed_at = <가입시각> 으로 남아 있다.
-- 그들을 사실대로 되돌리면 앱에서 약관 동의 화면을 다시 만나게 되므로,
-- 백필은 제품 결정이 필요하다고 보고 여기 넣지 않았다. (PR 설명에 SQL 첨부)
