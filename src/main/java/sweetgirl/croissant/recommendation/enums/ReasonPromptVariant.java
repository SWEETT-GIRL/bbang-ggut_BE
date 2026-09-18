package sweetgirl.croissant.recommendation.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 이유 문장 프롬프트 변형 (#662 실험). 무엇을 넣으면 문장이 나아지는지를 변형 하나당 한 가지씩만 바꿔 본다.
 *
 * <p>변형을 더하는 값은 여기 한 줄과 같은 이름의 프롬프트 파일 하나다. C(나머지 후보 비교)는 1차 결과를 보고
 * 더한다 — 토큰이 가장 크게 늘어나는 변형이라 앞의 셋으로 답이 나면 볼 필요가 없다.
 */
@Getter
@RequiredArgsConstructor
public enum ReasonPromptVariant {

	/** 최소 재료 — 가게의 빵 종류·태그 + 회원 유형·태그·자주 먹는 종류 */
	A("recommendation-reason-a-prompt.txt", false, false),

	/**
	 * A + 시그니처 메뉴. 그 가게가 무엇으로 알려졌는지가 거기 있다 — "플레인 포카치아 · 뺑 스위스 · 잠봉뵈르" 는
	 * 종류 목록("페이스트리 · 바게트")보다 그 가게에 가까운 말이다. 랩 기준 86.6% 의 가게가 갖고 있고, 중앙값이
	 * 5개 26자라 토큰도 얼마 안 든다. 소개글은 여기 넣지 않는다 — 점주 마케팅 문구라 검증이 안 되고, p90 이
	 * 616자여서 한 가게 몫이 프롬프트를 통째로 밀어낸다. 그건 가게당 한 번 읽어 태그로 남길 일이다.
	 */
	B("recommendation-reason-b-prompt.txt", false, true),

	/** A + 룰의 선정 근거(취향 코사인 · 겹친 태그 · 겹친 종류 · 인기도) */
	D("recommendation-reason-d-prompt.txt", true, false);

	private final String promptFile;

	/** 가게마다 "선정 근거" 줄을 붙일지 */
	private final boolean includesRuleEvidence;

	/** 가게마다 "시그니처 메뉴" 줄을 붙일지 */
	private final boolean includesSignatureMenus;
}
