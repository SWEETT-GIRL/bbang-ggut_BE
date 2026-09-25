package sweetgirl.croissant.recommendation.code;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 룰이 만드는 추천 문구 — 지금은 코스 제목뿐이다.
 *
 * <p>제목 = {@code "{상권} 빵집 코스"} — 유형 이름은 넣지 않는다(#760).
 *
 * <p><b>이유 문장 상수 셋은 뺐다.</b> 근거가 없는 주장이었기 때문이다 — {@code "이 동네에서 자주 담기는 빵집이에요"} 는
 * 저장 · 담기 빈도를 세지 않고, {@code "이 동네 시그니처 빵집이에요"} 는 그 가게가 동네를 대표한다는 데이터가 없다
 * (붙는 조건은 "취향이 안 맞는다" 였다). {@code "취향에 가까운 빵집이에요"} 는 네 줄이 같아지는 원인이었다.
 * 이제 {@link sweetgirl.croissant.recommendation.service.ReasonTemplates} 가 확인된 근거로 문장을 만들고,
 * 근거가 하나도 없는 자리에는 {@code "이번 코스에 함께 담아본 빵집이에요"} 가 선다 — 실제로 확인되는 사실이다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class RecommendationMessage {

	private static final String TITLE_FORMAT = "%s 빵집 코스";

	public static String courseTitle(String areaName) {
		return String.format(TITLE_FORMAT, areaName);
	}
}
