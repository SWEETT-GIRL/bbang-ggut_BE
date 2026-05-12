package sweetgirl.croissant.common.enums;

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
	 * DB에 저장된 float 반경 값을 enum으로 변환
	 * DB에는 항상 enum 단계 값만 저장되므로 정확히 일치하는 값이 없으면 예외
	 */
	public static NotificationRadius from(float value) {
		int intValue = Math.round(value); // float → int 변환
		for (NotificationRadius r : VALUES) {
			if (r.value == intValue) { // 정확히 일치하는 단계 반환
				return r;
			}
		}
		throw new IllegalStateException("유효하지 않은 알림 반경 값: " + value);
	}

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
