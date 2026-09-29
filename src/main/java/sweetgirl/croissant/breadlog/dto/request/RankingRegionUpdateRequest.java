package sweetgirl.croissant.breadlog.dto.request;

import jakarta.validation.constraints.NotBlank;

/** @param sido 짧은 시·도 표기("서울", "경기") */
public record RankingRegionUpdateRequest(@NotBlank String sido) {
}
