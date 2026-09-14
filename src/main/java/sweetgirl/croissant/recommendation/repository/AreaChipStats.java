package sweetgirl.croissant.recommendation.repository;

/** 상권 칩 하나의 재료 — 후보와 같은 소속 규칙(반경 안 OR 시군구·동 일치)으로 센 ACTIVE 가게 수와, 내 저장 가게가 그 안에 있는지. */
public record AreaChipStats(Long areaId, int storeCount, boolean hasSavedStore) {
}
