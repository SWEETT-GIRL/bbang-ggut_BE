package sweetgirl.croissant.breadranking.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import sweetgirl.croissant.breadranking.enums.RankMode;

/**
 * 등수 정하기.
 *
 * @param rank 1부터 "지금 등수 있는 줄의 마지막 등수 + 1"까지
 * @param mode 그 등수에 이미 누가 있으면 — TIE 같이 선다 / INSERT 끼우고 뒤를 민다. 비어 있는 등수면 어느 쪽이든 그냥 들어간다
 */
public record BreadRankPlaceRequest(@NotNull @Min(1) Integer rank, @NotNull RankMode mode) {
}
