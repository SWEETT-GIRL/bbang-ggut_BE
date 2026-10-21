package sweetgirl.croissant.breadranking.dto.response;

/** 만들기 결과 — created 가 false 면 같은 이름이 이미 있어 그걸 돌려준 것(200). */
public record BreadCategoryCreateResult(BreadCategoryResponse category, boolean created) {
}
