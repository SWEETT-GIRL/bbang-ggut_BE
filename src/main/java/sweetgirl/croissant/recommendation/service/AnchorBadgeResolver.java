package sweetgirl.croissant.recommendation.service;

import java.util.Map;
import java.util.Optional;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.personalization.enums.TasteTag;
import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.enums.AnchorReasonTag;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.TasteQuery;

/**
 * 앵커 카드 배지 — 빵 종류가 먼저다(#762). 종류 이름 하나에 맛 · 식감 · 속 · 스타일이 다 들어 있고 가게마다 달라, 회원의 극을
 * 말하는 축 라벨("담백")이나 인기와 같이 가는 태그("특별한 메뉴")보다 이유 구실을 한다(랩 09-20: 화면당 서로 다른 배지 2.85 → 3.86).
 * <ol>
 * <li>기록 종류(top_kinds) ∩ 가게 <b>대표 종류</b>(signature_kinds) → "{종류} 취향"</li>
 * <li>추정 종류(좋아한 가게들의 대표 종류, inferred_kinds) ∩ 가게 대표 종류 → "{종류} 취향"</li>
 * <li>회원 태그가 태그별 문턱({@code memberNotableTagAbove})을, 가게 태그가 {@code storeNotableTagAbove}(0.6)를 넘는 태그 중 곱이
 * 가장 큰 것 → 그 태그의 표시 문구. 강한 신호 가게가 {@code minStrongStoresForTagReason} 곳 미만인 회원은 건너뛴다</li>
 * <li>코사인이 임계를 넘으면 기여(u_i·s_i)가 가장 큰 축의 사용자 극 라벨</li>
 * <li>그 밖엔 "이 동네 시그니처"</li>
 * </ol>
 * 가게 쪽을 메뉴 전체(kinds)가 아니라 대표 종류로 맞추는 이유: 메뉴에 소금빵이 하나 있다고 "소금빵 취향" 을 붙이면 사실보다 넓다.
 * 대표 종류면 "이 가게는 소금빵으로 알려졌다" 가 된다.
 * SQL 이 아니라 자바에서 정한다 — 행에 축·태그·종류가 실려 오고, 규칙이 바뀌어도 SQL 을 안 건드린다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AnchorBadgeResolver {

	public static String resolve(TasteQuery taste, CandidateStoreRow row, RecommendationProperties.Score score) {
		for (String kind : taste.topKinds()) {
			if (row.signatureKinds().contains(kind)) {
				return AnchorReasonTag.KIND_MATCH.label(kind);
			}
		}
		for (String kind : taste.inferredKinds()) {
			if (row.signatureKinds().contains(kind)) {
				return AnchorReasonTag.KIND_MATCH.label(kind);
			}
		}
		TasteTag bestTag = null;
		double bestProduct = 0;
		for (Map.Entry<String, Double> userTag : taste.reasonTags(score.getMinStrongStoresForTagReason()).entrySet()) {
			Optional<TasteTag> tag = TasteTag.fromKey(userTag.getKey());
			Double storeValue = row.tags().get(userTag.getKey());
			if (tag.isPresent() && userTag.getValue() > score.memberNotableTagAbove(userTag.getKey())
				&& storeValue != null && storeValue > score.getStoreNotableTagAbove()
				&& userTag.getValue() * storeValue > bestProduct) {
				bestTag = tag.get();
				bestProduct = userTag.getValue() * storeValue;
			}
		}
		if (bestTag != null) {
			return AnchorReasonTag.TAG_MATCH.label(bestTag.getLabel());
		}
		if (taste.hasAxis() && row.hasAxis() && row.tasteFit() > score.getTasteCosineThreshold()) {
			return AnchorReasonTag.AXIS_POLE.label(dominantPole(taste, row, score));
		}
		return AnchorReasonTag.SIGNATURE.label();
	}

	/**
	 * 코사인 기여가 가장 큰 축의 사용자 극 라벨 — 앱 빵BTI 검사의 선택지와 같은 말이다(FE {@code bbtiQuiz.ts}): 회원이 답한 말이
	 * 그대로 칩이 된다. 넷째 축은 "클래식 / 트렌디" 다 — 요즘 유행하는 종류(소금빵·크로플)지 가게의 신상품이 아니라서, 서버 안
	 * 이름(코드 글자 O / N · "신상")을 칩에 쓰지 않는다. 기여는 점수 SQL 과 같은 좌표로 잰다:
	 * 중심화가 켜져 있으면 (사용자 축 − 평균)·(가게 축 − 평균). 원값 곱으로 재면 코사인을 깎은 축을 이유로 보여줄 수 있다(PR #739 리뷰).
	 * 극 라벨도 같은 좌표의 부호다 — "평균보다 담백한 쪽"이 담백이다.
	 */
	static String dominantPole(TasteQuery taste, CandidateStoreRow row, RecommendationProperties.Score score) {
		boolean center = score.isCenterAxes();
		double[] user = {
			taste.taste() - (center ? score.getCenterTaste() : 0),
			taste.texture() - (center ? score.getCenterTexture() : 0),
			taste.filling() - (center ? score.getCenterFilling() : 0),
			taste.vibe() - (center ? score.getCenterVibe() : 0)};
		double[] store = {
			row.axisTaste() - (center ? score.getCenterTaste() : 0),
			row.axisTexture() - (center ? score.getCenterTexture() : 0),
			row.axisFilling() - (center ? score.getCenterFilling() : 0),
			row.axisVibe() - (center ? score.getCenterVibe() : 0)};
		int dominant = 0;
		for (int i = 1; i < user.length; i++) {
			if (user[i] * store[i] > user[dominant] * store[dominant]) {
				dominant = i;
			}
		}
		return switch (dominant) {
			case 0 -> user[0] < 0 ? "달콤" : "담백";
			case 1 -> user[1] < 0 ? "폭신" : "바삭";
			case 2 -> user[2] < 0 ? "꽉 찬" : "심플";
			default -> user[3] < 0 ? "클래식" : "트렌디";
		};
	}
}
