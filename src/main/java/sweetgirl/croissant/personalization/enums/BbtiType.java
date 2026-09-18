package sweetgirl.croissant.personalization.enums;

import java.util.Arrays;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.personalization.code.PersonalizationErrorCode;
import sweetgirl.croissant.personalization.exception.PersonalizationException;

/**
 * 빵BTI 16타입 — 4글자 코드(TasteAxis#typeCode 규칙)와 화면 이름.
 *
 * 이름은 빵BTI 매핑 초안(2026-09-04)의 16개를 임시로 가져왔다. 카피 확정은 FE 타입 카드(#44)와 같이 한다 — 지금은
 * 추천 카드의 typeLabel 과 검사 응답에만 쓴다. 코드 자리는 1 맛 S/P · 2 식감 F/C · 3 속 R/L · 4 신상 O/N 이고,
 * 매핑 초안의 번호는 (맛 8 · 식감 4 · 속 2 · 신상 1) 비트 합 + 1 이다.
 */
@Getter
@RequiredArgsConstructor
public enum BbtiType {

	SFRO("따뜻한 포옹"),
	SFRN("화려한 몽상가"),
	SFLO("조용한 감성파"),
	SFLN("가벼운 구름"),
	SCRO("완벽주의 미식가"),
	SCRN("트렌드세터"),
	SCLO("우아한 고집"),
	SCLN("실험적 예술가"),
	PFRO("든든한 현실주의자"),
	PFRN("경계를 허무는 자"),
	PFLO("수수한 일상"),
	PFLN("미니멀리스트"),
	PCRO("날카로운 감각"),
	PCRN("세련된 반전"),
	PCLO("고독한 철학자"),
	PCLN("뉴웨이브 미니멀");

	private final String label;

	/** enum 이름이 곧 4글자 코드다. */
	public String code() {
		return name();
	}

	/**
	 * 저장된 코드에서 타입을 찾는다. 코드 체계는 DB CHECK 가 지키므로 못 찾으면 서버 약속이 깨진 것이다.
	 *
	 * @throws PersonalizationException {@code TASTE_AXIS_INCOMPLETE} — 규칙에 없는 코드
	 */
	/**
	 * 화면 이름. 코드 체계 밖 값이면 코드를 그대로 돌려준다 — 표시용 경로가 알 수 없는 코드 하나 때문에 멈출 이유는 없다.
	 * 저장된 코드가 규칙을 지키는지 따져야 하는 자리에서는 {@link #fromCode} 를 쓴다.
	 */
	public static String labelOrCode(String code) {
		if (code == null) {
			return null;
		}
		return Arrays.stream(values())
			.filter(type -> type.name().equals(code))
			.map(BbtiType::getLabel)
			.findFirst()
			.orElse(code);
	}

	public static BbtiType fromCode(String code) {
		return Arrays.stream(values())
			.filter(type -> type.name().equals(code))
			.findFirst()
			.orElseThrow(() -> new PersonalizationException(PersonalizationErrorCode.TASTE_AXIS_INCOMPLETE));
	}
}
