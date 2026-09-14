package sweetgirl.croissant.recommendation.service;

import java.util.Map;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
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

	public static String resolve(TasteQuery taste, CandidateStoreRow row, double tasteThreshold) {
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
		if (taste.hasAxis() && row.hasAxis() && row.tasteFit() > tasteThreshold) {
			return AnchorReasonTag.AXIS_POLE.label(dominantPole(taste, row));
		}
		return AnchorReasonTag.SIGNATURE.label();
	}

	/** 코사인 기여가 가장 큰 축의 사용자 극 라벨 — TasteAxis 의 4글자 코드와 같은 어휘. */
	static String dominantPole(TasteQuery taste, CandidateStoreRow row) {
		double[] contributions = {
			taste.taste() * row.axisTaste(),
			taste.texture() * row.axisTexture(),
			taste.filling() * row.axisFilling(),
			taste.vibe() * row.axisVibe()};
		int dominant = 0;
		for (int i = 1; i < contributions.length; i++) {
			if (contributions[i] > contributions[dominant]) {
				dominant = i;
			}
		}
		return switch (dominant) {
			case 0 -> taste.taste() < 0 ? "달콤" : "담백";
			case 1 -> taste.texture() < 0 ? "폭신" : "바삭";
			case 2 -> taste.filling() < 0 ? "꽉 찬" : "심플";
			default -> taste.vibe() < 0 ? "고전" : "신상";
		};
	}
}
