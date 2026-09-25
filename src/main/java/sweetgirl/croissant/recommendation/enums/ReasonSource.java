package sweetgirl.croissant.recommendation.enums;

/**
 * 이유 문장 한 줄이 어디서 왔는가 (#807 리뷰).
 *
 * <p><b>묶음 단위 {@code fallback} 으로는 줄별 출처를 말할 수 없다.</b> LLM 이 일부 가게만 답하거나(그 전부터
 * 가능했다) 일부 줄이 길이 상한을 넘으면 한 묶음에 룰과 LLM 이 섞인다. 그때 묶음 값은 "모든 줄이 룰인가" 만
 * 말할 수 있고, {@code false} 가 "모든 줄이 LLM" 이라는 뜻은 아니다.
 */
public enum ReasonSource {

	/** 서버 템플릿이 근거로 만든 문장 */
	RULE,
	/** LLM 이 만들어 실제로 적용된 문장 */
	LLM,
	/**
	 * 알 수 없다 — LLM 이 채운 옛 행인데 줄별 출처를 남기기 전에 저장된 경우.
	 *
	 * <p>이것을 {@link #LLM} 으로 뭉뚱그리지 않는다. 그 행에도 LLM 이 빠뜨려 룰로 남은 줄이 섞여 있을 수 있어,
	 * LLM 으로 세면 성공률이 부풀고 그 사실을 나중에 되짚을 방법이 없다.
	 */
	UNKNOWN
}
