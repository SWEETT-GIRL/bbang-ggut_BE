package sweetgirl.croissant.personalization.enums;

import java.util.Arrays;
import java.util.Optional;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 취향 태그 8개 — dataops #11 v5 사전(2026-09-19 확정). store_profile.tags · user_taste_profile.tags 의 jsonb 키가
 * {@link #key} 다. 프로필 계산은 이 8개만 읽고 쓴다(모르는 키는 버린다).
 *
 * <p>키와 표시 문구를 나눈다. 키는 데이터 이름이라 문장부호 없이 두고, 앱 배지 칩에 보이는 글자는 {@link #label} 이다 — 사전을
 * 고를 때 "칩으로 어색하지 않은가" 가 끼어들지 않게. 칩은 담기 버튼과 한 줄을 나눠 쓰고 길면 잘리므로 문구는 짧게 둔다.
 *
 * <p>값의 뜻: 방문자 투표 태그 여섯(머물기 · 공간사진 · 가성비 · 건강한맛 · 특별한메뉴 · 디저트)은 0.5 가 보통 빵집이고 표가
 * 없는 가게도 정확히 0.5 다. 웨이팅 · 대형은 규칙으로 계산한 0~1 이라 척도가 다르다. 서로 반대인 취향은 키 둘로 나눈다(가성비 ↔
 * 공간사진) — 태그 일치는 가게 값에 대한 증가 함수라 "이 값이 낮은 가게를 좋아함" 을 한 키로 담지 못한다.
 */
@Getter
@RequiredArgsConstructor
public enum TasteTag {
	WAITING("웨이팅", "웨이팅", Neutral.RULE),
	STAY("머물기", "머물기 좋은", Neutral.VOTE),
	SPACE_PHOTO("공간사진", "사진", Neutral.VOTE),
	VALUE("가성비", "가성비", Neutral.VOTE),
	HEALTHY("건강한맛", "건강·신선", Neutral.VOTE),
	SPECIAL_MENU("특별한메뉴", "특별한 메뉴", Neutral.VOTE),
	LARGE("대형", "대형 베이커리", Neutral.RULE),
	DESSERT("디저트", "디저트", Neutral.VOTE);

	private final String key;
	private final String label;
	/**
	 * 값이 빠졌을 때 읽을 값 — 투표 태그는 보통 빵집(0.5), 규칙 태그는 0. dataops 는 모든 가게에 8키를 다 넣지만, 적재가
	 * 끊기거나 사전이 바뀌는 사이 계약이 잠깐 어긋나도 그 가게를 "한참 약함" 으로 깎지 않고 "효과 없음" 으로 떨어지게 한다
	 * (#759 리뷰). 후보 SQL 과 프로필 계산기가 같이 쓴다.
	 */
	private final double neutral;

	/** 척도별 중립값 — enum 상수 선언보다 앞에 상수를 둘 수 없어 중첩 클래스에 둔다 */
	private static final class Neutral {
		/** 투표 태그: lift 1(보통 빵집)이 정확히 0.5 다 */
		static final double VOTE = 0.5;
		/** 규칙 태그(웨이팅 · 대형): 옛 규칙의 0~1 이라 "해당 없음" 이 0 이다 */
		static final double RULE = 0.0;
	}

	public static Optional<TasteTag> fromKey(String key) {
		return Arrays.stream(values()).filter(tag -> tag.key.equals(key)).findFirst();
	}
}
