package sweetgirl.croissant.recommendation.service;

import sweetgirl.croissant.recommendation.enums.EvidenceType;
import sweetgirl.croissant.recommendation.enums.KindSource;

/**
 * 이유 문장 하나가 기댄 근거 (설계 §11).
 *
 * <p><b>정렬용 숫자를 두지 않는다.</b> 근거 유형마다 세기의 뜻이 달라서다 — 종류는 프로필 안 순위, 태그는 회원값×가게값,
 * {@code STORE_TAG} 는 회원값이 없어 그 식 자체가 성립하지 않는다. 하나로 합치면 {@code 0.8} 이 더 정확한 근거라는
 * 오해가 생긴다. 순서는 {@link ReasonEvidenceResolver} 가 정해서 <b>정렬된 목록</b>으로 주고, 여기는 무엇을 확인했는지만 담는다.
 *
 * @param type 근거 유형. 선언 순서가 우선순위다
 * @param subject 종류 이름(소금빵) 또는 태그 키(가성비). {@link EvidenceType#NONE} 이면 null
 * @param source 종류 근거의 출처. 태그 근거와 NONE 이면 null
 */
public record ReasonEvidence(EvidenceType type, String subject, KindSource source) {

	private static final ReasonEvidence NONE = new ReasonEvidence(EvidenceType.NONE, null, null);

	public static ReasonEvidence none() {
		return NONE;
	}

	public static ReasonEvidence ofKind(EvidenceType type, String kind, KindSource source) {
		return new ReasonEvidence(type, kind, source);
	}

	public static ReasonEvidence ofTag(EvidenceType type, String tagKey) {
		return new ReasonEvidence(type, tagKey, null);
	}
}
