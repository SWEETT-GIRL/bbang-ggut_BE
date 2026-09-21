package sweetgirl.croissant.personalization.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 검사 문항의 선택지 — 축마다 갈래가 다르다. 맛 4 · 식감 3 · 속 2 · 스타일 2 로 모두 11개다.
 *
 * <p><b>자리 문자(A/B)가 아니라 갈래 이름인 이유.</b> 예전에는 {@code A}(−1 극) · {@code B}(+1 극) 둘뿐이었고
 * 뜻은 선택지의 <i>순서</i>가 정했다. 그러면 앱이 선택지 순서를 바꾸거나 갈래를 더하는 순간 서버가 같은 글자를
 * 다른 취향으로 저장한다 — 아무 데서도 오류가 나지 않아 조용히 틀린다. 이름 코드는 그 사고가 구조적으로 안 난다.
 *
 * <p>값 셋을 각자 갖는다.
 * <ul>
 *   <li>{@code label} — 라벨 키. dataops 사전의 열 이름과 같은 말이다(풍미는 {@code rich} 가 아니라
 *       {@code aroma} 다 — 리뷰 태그의 {@code rich} 가 초코·치즈의 '진한 맛' 을 가리켜 이미 혼동을 봤다).</li>
 *   <li>{@code typeLetter} — 타입 코드 글자. 자리마다 글자 집합이 겹치지 않아 글자만 보고 축을 안다.</li>
 *   <li>{@code pole} — 기존 4축 요약값. 아래 설명대로 <b>정보가 줄어드는 값</b>이다.</li>
 * </ul>
 *
 * <p><b>{@code pole} 이 왜 정보를 잃나.</b> 기존 축은 −1~+1 숫자 하나라 두 갈래까지만 담는다. 맛이 넷이 되면
 * 짭짤과 담백이 똑같이 {@code +1} 이 되고, 풍미와 쫄깃은 어느 극도 아니라 {@code 0} 이다. 이 축은 가게 라벨이
 * 올 때까지 추천이 쓰는 값이라 채우기는 하되, <b>정확한 취향은 저장된 응답에만 남는다</b> —
 * {@code user_taste_profile.quiz_answers} 가 원천이고 라벨·타입 코드는 모두 거기서 파생된다.
 */
@Getter
@RequiredArgsConstructor
public enum QuizChoice {

	/** 맛 — 달콤 · 담백 · 짭짤 · 풍미. 짭짤은 요약에서 담백과 같은 +1 이고, 풍미는 어느 극도 아니라 0 이다. */
	SWEET(QuizAxis.TASTE, "sweet", 'S', -1),
	PLAIN(QuizAxis.TASTE, "plain", 'P', 1),
	SALTY(QuizAxis.TASTE, "salty", 'T', 1),
	AROMA(QuizAxis.TASTE, "aroma", 'M', 0),

	/** 식감 — 부드 · 바삭 · 쫄깃. 쫄깃은 부드·바삭 어느 쪽도 아니라 0 이다. */
	SOFT(QuizAxis.TEXTURE, "soft", 'F', -1),
	CRISP(QuizAxis.TEXTURE, "crisp", 'C', 1),
	CHEWY(QuizAxis.TEXTURE, "chewy", 'Y', 0),

	/** 속 — 갈래가 둘뿐이라 요약이 정보를 잃지 않는다. */
	FULL(QuizAxis.FILLING, "filling", 'R', -1),
	SIMPLE(QuizAxis.FILLING, "filling", 'L', 1),

	/** 스타일 — 빵의 성질이지 가게 분위기가 아니다(TasteAxis 주석 참고). 여기도 갈래가 둘이다. */
	CLASSIC(QuizAxis.VIBE, "vibe", 'O', -1),
	TRENDY(QuizAxis.VIBE, "vibe", 'N', 1);

	private final QuizAxis axis;
	private final String label;
	private final char typeLetter;
	private final int pole;

	/** 이 선택지가 저 축의 문항에 올 수 있나 — 맛 문항에 {@code CHEWY} 가 오면 입력 오류다. */
	public boolean belongsTo(QuizAxis quizAxis) {
		return this.axis == quizAxis;
	}

	/**
	 * 라벨 값이 1 인가 — 속·스타일은 두 갈래가 라벨 키 하나({@code filling} · {@code vibe})를 나눠 쓴다.
	 * 그 키의 값은 "꽉 찬 정도" · "트렌디한 정도" 라서, <b>그 정도가 낮은 쪽</b>({@code SIMPLE} · {@code CLASSIC})을
	 * 고르면 0 이다. 맛·식감은 갈래마다 키가 따로 있어 고른 것이 1, 나머지가 0 이다.
	 *
	 * <p>기존 축과 부호가 반대라는 점에 주의한다 — {@code axis_filling} 은 꽉 참이 −1 · 심플이 +1 이지만,
	 * 라벨은 "꽉 찬 정도" 라서 꽉 참이 1 이다. 축은 두 극 사이의 위치이고 라벨은 한 성질의 세기라 방향이 다르다.
	 */
	public double labelValue() {
		return switch (this) {
			case SIMPLE, CLASSIC -> 0d;
			default -> 1d;
		};
	}
}
