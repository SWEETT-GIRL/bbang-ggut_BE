package sweetgirl.croissant.recommendation.code;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 룰이 만드는 추천 문구 — LLM 없이 내려갈 때(#660 임시 구현 · #662 의 폴백)의 제목과 이유.
 *
 * <p>제목 = {@code "{상권} {타입 이름 또는 '빵집'} 코스"}. 이유는 인기순으로 골랐다는 사실을 그대로 말한다 —
 * 취향 근거 없이 "취향에 맞는다"고 쓰지 않는다. 취향 이유는 #661 규칙 · #662 프롬프트가 쓴다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class RecommendationMessage {

	public static final String RULE_REASON = "이 동네에서 자주 담기는 빵집이에요";
	/** 취향 후보(코사인 > 임계) — #661 점수식으로 골랐을 때 */
	public static final String TASTE_REASON = "취향에 가까운 빵집이에요";
	/** 취향 근거가 약한 가게 — 취향 폴백(취향 후보 3곳 미만)이거나 tasteFit 이 낮을 때(설계 §7.1) */
	public static final String SIGNATURE_REASON = "이 동네 시그니처 빵집이에요";
	public static final String TITLE_WITHOUT_TYPE = "빵집";
	private static final String TITLE_FORMAT = "%s %s 코스";

	public static String courseTitle(String areaName, String typeLabel) {
		return String.format(TITLE_FORMAT, areaName, typeLabel != null ? typeLabel : TITLE_WITHOUT_TYPE);
	}
}
