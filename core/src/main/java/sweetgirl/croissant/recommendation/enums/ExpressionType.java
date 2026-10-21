package sweetgirl.croissant.recommendation.enums;

/**
 * 같은 근거를 어떻게 말하는가 (설계 §5.3).
 *
 * <p><b>표현을 바꿔도 근거 유형과 출처는 그대로다.</b> {@link #DESCRIBE} 를 골랐다고 개인화 근거가
 * {@link EvidenceType#STORE_KIND} 로 내려가지 않는다 — 코스 안에서 조건형이 반복될 때 같은 근거의 다른 말을 쓰는 것이지,
 * 근거를 깎는 것이 아니다.
 *
 * <p>모든 근거가 둘 다 갖지는 않는다. 태그 근거는 {@link #DESCRIBE} 하나로 충분하다.
 */
public enum ExpressionType {

	/** 회원과의 연결을 말한다 — "소금빵 좋아하시면 살펴보세요" */
	CONNECT,
	/** 가게의 특징을 말한다 — "대표 빵은 소금빵이에요" */
	DESCRIBE
}
