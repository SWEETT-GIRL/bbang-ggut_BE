package sweetgirl.croissant.breadranking.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 직접 만들기 — 같은 이름(정규화 키)이 있으면 그걸 돌려준다. */
public record BreadCategoryCreateRequest(@NotBlank @Size(max = 30) String name) {
}
