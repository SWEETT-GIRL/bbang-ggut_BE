package sweetgirl.croissant.recommendation.service;

import java.util.Map;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.enums.AnchorReasonTag;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.TasteQuery;

/**
 * 앵커 카드 배지(#661 · 09-13 확정 우선순위): ① 사용자 top_kinds ∩ 가게 kinds → "{종류} 취향" ② 사용자·가게 태그 둘 다 0.5 이상인
 * 태그 중 곱이 가장 큰 것 → 태그 이름 ③ 코사인이 임계를 넘으면 기여(u_i·s_i)가 가장 큰 축의 사용자 극 라벨 ④ 그 밖엔 "이 동네 시그니처".
 * SQL 이 아니라 자바에서 정한다 — 행에 축·태그·종류가 실려 오고, 규칙이 바뀌어도 SQL 을 안 건드린다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AnchorBadgeResolver {

	static final double TAG_MATCH_MIN = 0.5;

	public static String resolve(TasteQuery taste, CandidateStoreRow row, RecommendationProperties.Score score) {
		for (String kind : taste.topKinds()) {
			if (row.kinds().contains(kind)) {
				return AnchorReasonTag.KIND_MATCH.label(kind);
			}
		}
		String bestTag = null;
		double bestProduct = 0;
		for (Map.Entry<String, Double> userTag : taste.tags().entrySet()) {
			Double storeValue = row.tags().get(userTag.getKey());
			if (userTag.getValue() >= TAG_MATCH_MIN && storeValue != null && storeValue >= TAG_MATCH_MIN
				&& userTag.getValue() * storeValue > bestProduct) {
				bestTag = userTag.getKey();
				bestProduct = userTag.getValue() * storeValue;
			}
		}
		if (bestTag != null) {
			return AnchorReasonTag.TAG_MATCH.label(bestTag);
		}
		if (taste.hasAxis() && row.hasAxis() && row.tasteFit() > score.getTasteCosineThreshold()) {
			return AnchorReasonTag.AXIS_POLE.label(dominantPole(taste, row, score));
		}
		return AnchorReasonTag.SIGNATURE.label();
	}

	/**
	 * 코사인 기여가 가장 큰 축의 사용자 극 라벨 — TasteAxis 의 4글자 코드와 같은 어휘. 기여는 점수 SQL 과 같은 좌표로 잰다:
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
			default -> user[3] < 0 ? "고전" : "신상";
		};
	}
}
