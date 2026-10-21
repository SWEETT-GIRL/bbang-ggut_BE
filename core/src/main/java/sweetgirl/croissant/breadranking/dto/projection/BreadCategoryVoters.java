package sweetgirl.croissant.breadranking.dto.projection;

import sweetgirl.croissant.breadranking.entity.BreadCategory;

/** 공용 목록 한 줄 — 카테고리와 그 안에서 1등을 매긴 서로 다른 회원 수(탈퇴 제외). */
public record BreadCategoryVoters(BreadCategory category, long voters) {
}
