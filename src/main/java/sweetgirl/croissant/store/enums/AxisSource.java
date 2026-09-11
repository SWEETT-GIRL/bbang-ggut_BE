package sweetgirl.croissant.store.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 가게 축이 어느 재료에서 왔는지. dataops 프로필 배치가 채운다 (설계 §5.1). */
@Getter
@RequiredArgsConstructor
public enum AxisSource {

	MENU("운영 분류 메뉴 3개 이상"),
	REVIEW("리뷰 언급 메뉴만 (라벨 3개 이상 또는 언급 20회 이상)"),
	BOTH("운영 메뉴 0.6 + 리뷰 언급 메뉴 0.4");

	private final String description;
}
