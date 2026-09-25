package sweetgirl.croissant.recommendation.enums;

/**
 * 이유 문장이 기댈 수 있는 근거 — 무엇을 **확인했는가** 로 가른다.
 *
 * <p><b>선언 순서가 우선순위다.</b> 앞에 있을수록 더 직접적인 근거다: 회원이 실제로 기록한 것 → 회원이 좋아한 가게에서
 * 추론한 것 → 회원과 가게 양쪽이 도드라진 태그 → 가게만 도드라진 태그 → 가게 대표 종류 → 없음.
 * {@code ordinal()} 에 기대지 않고 목록 정렬에서 이 순서를 쓴다.
 *
 * <p>유형마다 <b>말할 수 있는 것이 다르다</b>(설계 §5.1). {@link #RECORDED_KIND_MATCH} 도 "자주 드신다" 는 못 쓴다 —
 * {@code top_kinds} 는 기록 종류별 시간 감쇠 점수를 정렬한 것이라 기록 한 건으로도 들어가고, 빈도는 전달되지 않는다.
 * {@link #INFERRED_KIND_MATCH} 는 담은 가게에서 추론한 관심 신호라 조건형("~좋아하시면")까지만 간다.
 *
 * <p><b>{@link #MEMBER_TAG_MATCH} 와 {@link #STORE_TAG} 를 나누는 이유:</b> 강한 신호 4곳 문턱을 못 넘는 회원이
 * 운영 743명 중 541명(73%)이다. 회원 조건을 가게 태그에도 걸면 이들이 가게 특징으로 설명받을 기회를 잃는다.
 * 두 유형은 <b>같은 문장</b>을 쓸 수 있지만(설계 §5.4), 개인화 근거를 썼는지는 유형으로 구분된다.
 */
public enum EvidenceType {

	/** 회원이 기록한 종류가 가게 대표 종류에 있다 */
	RECORDED_KIND_MATCH,
	/** 회원이 좋아한 가게들에서 추론한 종류가 가게 대표 종류에 있다 */
	INFERRED_KIND_MATCH,
	/** 회원 태그와 가게 태그가 둘 다 문턱을 넘는다 — 강한 신호 가게 수 조건도 통과 */
	MEMBER_TAG_MATCH,
	/** 가게 태그만 문턱을 넘는다. 회원 조건이 필요 없다 */
	STORE_TAG,
	/** 가게 대표 종류가 있다. 회원과의 연결은 없다 */
	STORE_KIND,
	/** 아무 근거도 없다 — 코스에 함께 담았다는 사실만 말한다 */
	NONE;

	/** 회원과의 연결을 말하는 근거인가 — 개인화 근거 사용률의 분자다 */
	public boolean isPersonalized() {
		return this == RECORDED_KIND_MATCH || this == INFERRED_KIND_MATCH || this == MEMBER_TAG_MATCH;
	}
}
