-- 캐릭터 한 줄 소개. 프로필 이름표에 이름과 함께 나온다.
--
-- 이름처럼 정본을 DB 에 둔다 — 어드민에서 고치면 앱 배포 없이 바뀐다.
-- 비어 있으면(NULL) 앱이 번들 문구를 쓴다. 소(cow)는 아직 문구가 정해지지 않아 비워 둔다.
ALTER TABLE world_characters ADD COLUMN description VARCHAR(100);

UPDATE world_characters SET description = v.description
FROM (VALUES
 ('cat',         '무해하고 호기심 많은 조금 꼬질한 고양이'),
 ('dog',         '항상 신나지만 어딘가 허술한 강아지'),
 ('rabbit',      '통통하고 덜렁거리는 하찮은 토끼'),
 ('penguin',     '어설프지만 꾸준히 도전하는 펭귄'),
 ('chick',       '작지만 열정 가득한 호기심 많은 병아리'),
 ('hamster',     '작지만 열정 가득한 에너지 햄스터'),
 ('quoka',       '항상 웃는 행복 전도사 쿼카'),
 ('sloth',       '느리지만 꾸준한 힐링 요정 나무늘보'),
 ('otter',       '신나고 호기심 많은 장난꾸러기 수달'),
 ('oompaloompa', '항상 웃는 천진난만한 우파루파'),
 ('racerpanda',  '소심하지만 노력파 래서판다'),
 ('frog',        '아무 생각 없는 평화주의 개구리'),
 ('ber',         '느긋하고 묵묵하게 올라가는 곰'),
 ('racoon',      '호기심 많고 뭐든 주워오는 탐험가'),
 ('coala',       '느긋하고 푹신한 힐링 요정'),
 ('duck',        '엉뚱하고 자유로운 발랄한 오리'),
 ('dino',        '서툴지만 꾸준히 노력하는 아기 공룡'),
 ('alpaca',      '조용하지만 은근 엉뚱한 친구'),
 ('seal',        '말랑하고 순한 댕댕한 귀요미 물범')
) AS v (code, description)
WHERE world_characters.code = v.code;

-- 카탈로그 번호를 올린다. 안 올리면 앱은 옛 카탈로그를 계속 쓰고 서버 캐시도 안 풀려 소개가 안 나온다.
UPDATE world_catalog SET version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = 1;
