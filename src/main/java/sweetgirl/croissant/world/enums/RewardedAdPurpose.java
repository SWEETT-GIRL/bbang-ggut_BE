package sweetgirl.croissant.world.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 보상형 광고를 무엇에 썼는가. 같은 SSV 콜백으로 오지만 적립하는 곳이 다르다.
 */
@Getter
@RequiredArgsConstructor
public enum RewardedAdPurpose {

	CHARACTER("친구 깨우기"),
	NICKNAME("닉네임 뽑기"),
	CRUMBS("부스러기 받기"),
	FEED("밥 룰렛 한 판 더");

	private final String description;
}
