package sweetgirl.croissant.personalization.enums;

import java.util.Arrays;

import sweetgirl.croissant.personalization.code.PersonalizationErrorCode;
import sweetgirl.croissant.personalization.exception.PersonalizationException;

/**
 * 빵BTI 16타입 — 4글자 코드(TasteAxis#typeCode 규칙). 코드 자리는 1 맛 S/P · 2 식감 F/C · 3 속 R/L · 4 빵 스타일 O/N
 * (클래식 / 트렌디)이다.
 *
 * <p>화면 이름은 두지 않는다. 09-04 매핑 초안의 이름 16개("완벽주의 미식가" 등)를 임시로 달았다가 앱에서 없애기로 했다
 * (#760) — 카드 제목은 이름 없이 "{지역}에서 시작해볼까요?", 추천 코스 이름은 "{지역} 빵집 코스" 다. 코드는 검사 응답과
 * 저장된 값의 검증에 쓴다.
 */
public enum BbtiType {

	SFRO,
	SFRN,
	SFLO,
	SFLN,
	SCRO,
	SCRN,
	SCLO,
	SCLN,
	PFRO,
	PFRN,
	PFLO,
	PFLN,
	PCRO,
	PCRN,
	PCLO,
	PCLN;

	/** enum 이름이 곧 4글자 코드다. */
	public String code() {
		return name();
	}

	/**
	 * 저장된 코드에서 타입을 찾는다. 코드 체계는 DB CHECK 가 지키므로 못 찾으면 서버 약속이 깨진 것이다.
	 *
	 * @throws PersonalizationException {@code TASTE_AXIS_INCOMPLETE} — 규칙에 없는 코드
	 */
	public static BbtiType fromCode(String code) {
		return Arrays.stream(values())
			.filter(type -> type.name().equals(code))
			.findFirst()
			.orElseThrow(() -> new PersonalizationException(PersonalizationErrorCode.TASTE_AXIS_INCOMPLETE));
	}
}
