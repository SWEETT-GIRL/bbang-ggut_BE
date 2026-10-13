-- 가게의 "평소 수준" — 급부상 판정(최근 활동이 평소의 몇 배인가)과 자동완성 스테디 정렬에 쓴다.
-- 최근 7일 바로 앞 28일 동안의 신호(조회·찜·가고싶은 가게·코스담기·빵로그) 가중합을 일평균한 값이다.
-- 매일 배치(StoreTrendingBaselineCommandService)가 user_store_interaction 에서 다시 계산한다.
ALTER TABLE store ADD COLUMN trending_baseline DOUBLE PRECISION NOT NULL DEFAULT 0;
