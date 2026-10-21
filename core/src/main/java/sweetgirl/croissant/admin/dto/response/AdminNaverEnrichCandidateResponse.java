package sweetgirl.croissant.admin.dto.response;

import sweetgirl.croissant.store.entity.NaverEnrichCandidate;

public record AdminNaverEnrichCandidateResponse(
		Long id,
		String naverPlaceId,
		String name,
		String address,
		Double lat,
		Double lng,
		int displayOrder) {

	public static AdminNaverEnrichCandidateResponse from(NaverEnrichCandidate candidate) {
		return new AdminNaverEnrichCandidateResponse(
			candidate.getId(),
			candidate.getNaverPlaceId(),
			candidate.getName(),
			candidate.getAddress(),
			candidate.getLat(),
			candidate.getLng(),
			candidate.getDisplayOrder());
	}
}
