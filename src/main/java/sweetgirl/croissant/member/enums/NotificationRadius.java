package sweetgirl.croissant.member.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum NotificationRadius {

	// 알림 반경 단계 정의 (단위: m)
	R100(100),
	R300(300),
	R500(500),
	R700(700),
	R1000(1000);

	// 각 단계의 실제 반경 값 (미터)
	private final int value;

	// 반복 탐색 시 매번 values() 호출을 피하기 위해 캐싱
	private static final NotificationRadius[] VALUES = values();

	/**
	 * 현재 단계에서 한 단계 위 반환
	 * 이미 최대 단계(R1000)이면 null 반환
	 */
	public NotificationRadius next() {
		int nextOrdinal = this.ordinal() + 1;
		return nextOrdinal < VALUES.length ? VALUES[nextOrdinal] : null;
	}

	/**
	 * 현재 단계에서 한 단계 아래 반환
	 * 이미 최소 단계(R100)이면 null 반환
	 */
	public NotificationRadius prev() {
		int prevOrdinal = this.ordinal() - 1;
		return prevOrdinal >= 0 ? VALUES[prevOrdinal] : null;
	}
}
