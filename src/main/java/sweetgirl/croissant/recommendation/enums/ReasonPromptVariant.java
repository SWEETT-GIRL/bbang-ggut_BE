package sweetgirl.croissant.recommendation.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 이유 문장 프롬프트 변형 (#662 실험). 무엇을 넣으면 문장이 나아지는지를 변형 하나당 한 가지씩만 바꿔 본다.
 *
 * <p>1차는 A·D 둘로 시작한다 — 최소 재료로 되는지와, 룰의 선정 근거를 주면 지어내지 않고 사실 위에서 쓰는지.
 * B(시그니처 메뉴·소개글)·C(나머지 후보 비교)는 1차 결과를 보고 더한다. 더하는 값은 여기 한 줄과 같은 이름의
 * 프롬프트 파일 하나다.
 */
@Getter
@RequiredArgsConstructor
public enum ReasonPromptVariant {

	/** 최소 재료 — 4곳의 축·태그·종류 + 회원 축·유형 */
	A("recommendation-reason-a-prompt.txt", false),

	/** A + 룰의 선정 근거(취향 코사인 · 겹친 태그 · 겹친 종류 · 인기도) */
	D("recommendation-reason-d-prompt.txt", true);

	private final String promptFile;

	/** 가게마다 "선정 근거" 줄을 붙일지 */
	private final boolean includesRuleEvidence;
}
