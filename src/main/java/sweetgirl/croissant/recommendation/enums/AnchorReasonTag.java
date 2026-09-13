package sweetgirl.croissant.recommendation.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 앵커 카드의 배지 — 이유 문장 대신 SQL 로 정해지는 짧은 태그 하나 (09-13 · #661).
 *
 * enum 순서가 우선순위다: 종류 겹침 > 태그 일치 > 축 극 라벨 > 시그니처. 앞의 셋은 주어(종류 · 태그 · 극 라벨)를
 * 받아 문구를 만들고, SIGNATURE 는 취향 근거 없이 인기순으로 들어온 가게의 배지다. 응답에는 label 문자열이 나간다.
 * #660 에는 축·종류 데이터가 없어 전부 SIGNATURE 이고, 나머지 셋은 #661 이 채운다.
 */
@Getter
@RequiredArgsConstructor
public enum AnchorReasonTag {

	KIND_MATCH("%s 취향"),
	TAG_MATCH("%s"),
	AXIS_POLE("%s"),
	SIGNATURE("이 동네 시그니처");

	private final String template;

	public String label(String subject) {
		return String.format(template, subject);
	}

	/** 주어가 없는 배지(SIGNATURE)용. */
	public String label() {
		return label("");
	}
}
