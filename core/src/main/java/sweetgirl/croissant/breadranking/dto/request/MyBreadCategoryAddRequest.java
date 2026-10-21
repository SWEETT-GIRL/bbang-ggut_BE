package sweetgirl.croissant.breadranking.dto.request;

import jakarta.validation.constraints.NotNull;

public record MyBreadCategoryAddRequest(@NotNull Long categoryId) {
}
