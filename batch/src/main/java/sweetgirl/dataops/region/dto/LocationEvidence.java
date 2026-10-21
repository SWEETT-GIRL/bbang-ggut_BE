package sweetgirl.dataops.region.dto;

/**
 * 운영에 쓸 지역의 근거 — {@code region_resolution} 한 행에서 온다.
 * 어떤 근거로 지역을 썼는지 apply_log·review_note 에 남기려고 id·법정동 코드·기준표 버전을 함께 싣는다.
 */
public record LocationEvidence(Long resolutionId, String bjdCode, String region1st, String region2nd,
		String region3rd, String referenceVersion) {
}
