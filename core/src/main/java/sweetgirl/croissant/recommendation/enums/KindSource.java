package sweetgirl.croissant.recommendation.enums;

/**
 * 종류 근거가 어디서 왔는가 — 같은 "소금빵" 이라도 출처가 허용하는 말이 다르다 (설계 §5.1).
 *
 * <p>근거 유형({@link EvidenceType})과 따로 두는 이유는 추적이다. 최종 출력에서 어떤 출처가 실제로 쓰였는지를
 * 세야 "개인화 근거 사용률" 과 "취향을 직접 언급한 비율" 을 나눠 볼 수 있다.
 */
public enum KindSource {

	/** user_taste_profile.top_kinds — 회원이 기록한 빵 종류 */
	RECORDED,
	/** user_taste_profile.inferred_kinds — 좋아한 가게의 대표 종류에서 추론 */
	INFERRED,
	/** store_profile.signature_kinds — 가게 대표 종류. 회원과의 연결 없음 */
	STORE
}
