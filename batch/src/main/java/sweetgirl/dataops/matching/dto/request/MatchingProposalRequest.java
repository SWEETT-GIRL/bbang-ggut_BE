package sweetgirl.dataops.matching.dto.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 매칭 반영 후보 일괄 제안 — 눈검사 게이트를 통과한 (storeId, naverPlaceId) 목록 */
public record MatchingProposalRequest(@NotEmpty @Valid List<Item> candidates) {

	public record Item(@NotNull Long storeId, @NotBlank String naverPlaceId) {
	}
}
