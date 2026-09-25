# feat/character-description-ad-purpose — 캐릭터 한 줄 소개 · 광고 이력에 용도

두 가지 스키마 변경. 둘 다 사용자 승인을 받은 ERD 그대로다.

## A. 캐릭터 한 줄 소개

- `V20260925100000__add_description_to_world_characters.sql`
  - `world_characters.description VARCHAR(100) NULL`
  - 19종 문구를 코드로 UPDATE. 소(cow)는 문구가 없어 NULL
  - `world_catalog.version + 1` — 안 올리면 앱이 카탈로그를 다시 안 받고 서버 캐시도 안 풀린다
- 카탈로그 `WorldCharacterResponse.description`, 어드민 목록 응답에도 싣는다
- 어드민 생성·수정이 받는다(`@Size(max = 100)`). 수정은 null = 그대로, `""` = 지우기
  (다른 칸은 "비우면 그대로"라 지울 길이 없어서 소개만 빈 문자열을 지우기로 읽는다)
- 앱은 description 이 null 이면 번들 문구(CHARACTER_DESCRIPTIONS)로 돌아간다

## B. 보상형 광고 이력에 purpose

- `V20260925110000__add_purpose_to_rewarded_ad_transaction.sql`
  - `purpose VARCHAR(20) NOT NULL DEFAULT 'CHARACTER'` — 기존 행은 전부 친구 광고
  - `character_code` NOT NULL 해제(FK 유지)
  - `CHECK (purpose <> 'CHARACTER' OR character_code IS NOT NULL)`
- 닉네임 광고(`custom_data=nickname:{memberId}`)도 `transaction_id` 로 먼저 남기고(ON CONFLICT DO NOTHING),
  0 이면 뽑지 않는다. 재시도가 나란히 와도 한 번만 뽑는다. 하루 한 번 제한은 그 뒤 NicknameDrawService 가 본다
- 친구 광고 흐름은 그대로(삽입에 `purpose = 'CHARACTER'` 만 명시)
- 닉네임 삽입은 별도 쿼리(`recordNicknameIfFirstTime`)로 뒀다 — 네이티브 쿼리에 null 을 바인딩하면
  드라이버가 타입을 못 정하는 경우가 있어, character_code 칸 자체를 안 쓰는 쪽이 안전하다

## 검증

- 로컬 Postgres 18 에 create_world → create_rewarded_ad_transaction → 두 마이그레이션 순서로 적용
  - 소개 19종 채워지고 cow NULL, catalog version 1 → 2
  - 기존 행 purpose=CHARACTER, NICKNAME 행 character_code NULL 삽입 OK, 같은 번호 재삽입 무시
  - CHARACTER + character_code NULL 은 CHECK 로, 없는 코드는 FK 로 막힘
- `./gradlew build` 통과
