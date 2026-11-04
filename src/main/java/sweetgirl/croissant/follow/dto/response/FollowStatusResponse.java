package sweetgirl.croissant.follow.dto.response;

import sweetgirl.croissant.follow.service.FollowResult;

/**
 * 팔로우를 누른 결과(2026-11-04). 구버전 앱은 data 를 읽지 않는다(그전엔 null 이었다).
 * {@code status} 는 FOLLOWING(바로 팔로우) | REQUESTED(비공개 계정 — 요청만 남음).
 */
public record FollowStatusResponse(String status, boolean following, boolean requested) {

	public static FollowStatusResponse from(FollowResult result) {
		return new FollowStatusResponse(result.name(), result == FollowResult.FOLLOWING,
			result == FollowResult.REQUESTED);
	}
}
