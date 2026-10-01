package sweetgirl.croissant.breadranking.dto.request;

import jakarta.validation.constraints.NotNull;

/** 담기 — 빵로그를 남긴 가게만. 등수는 비워 둔다. */
public record BreadRankStoreAddRequest(@NotNull Long storeId) {
}
