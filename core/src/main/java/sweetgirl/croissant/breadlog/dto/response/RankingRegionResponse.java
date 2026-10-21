package sweetgirl.croissant.breadlog.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.breadlog.entity.MemberRankingRegion;

/**
 * 랭킹 "내 지역".
 *
 * @param sido       인증한 시·도(짧은 표기). 아직 안 했으면 null
 * @param sigungu    인증한 시·군·구("동대문구"·"수원시"). 시·도만 고르던 때의 값이면 null — 앱은 다시 인증하게 한다
 * @param verifiedAt 마지막으로 인증한 시각(Asia/Seoul). 아직 안 했으면 null
 */
public record RankingRegionResponse(String sido, String sigungu, LocalDateTime verifiedAt) {

	public static RankingRegionResponse from(MemberRankingRegion region) {
		return new RankingRegionResponse(region.getSido(), region.getSigungu(), region.getVerifiedAt());
	}

	public static RankingRegionResponse empty() {
		return new RankingRegionResponse(null, null, null);
	}
}
