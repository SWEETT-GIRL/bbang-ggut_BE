package sweetgirl.croissant.external.llm.client;

import sweetgirl.croissant.external.llm.LlmProvider;
import sweetgirl.croissant.external.llm.dto.LlmRequest;
import sweetgirl.croissant.external.llm.dto.LlmResult;

/**
 * 벤더 하나에 붙는 어댑터 (#662). 셋이 다른 것은 세 가지뿐이라 여기서 흡수한다 — 구조화 출력을 지정하는 방식, usage 필드
 * 이름, 오류가 오는 모양(Anthropic 은 거부를 HTTP 200 에 담아 보낸다).
 *
 * <p>공식 SDK 대신 {@code RestClient} 로 직접 부른다. 레포의 외부 API 다섯(kakao · naver · google · slack · gemini)이 전부
 * 이 방식이고, 무엇보다 <b>세 모델의 지연을 비교하려면 클라이언트 계층이 같아야 공정하다</b>(SDK 는 자체 재시도·타임아웃 규칙을
 * 갖는다). 스트리밍 · 툴 · 캐싱이 필요해지면 그때 어댑터만 바꾼다.
 */
public interface LlmClient {

	LlmProvider provider();

	/** 키가 설정돼 있나 — 실험이 키 없는 벤더를 건너뛸 수 있게. */
	boolean isConfigured();

	/** 이 벤더에서 키를 안 줬을 때 쓸 기본 모델. */
	String defaultModel();

	<T> LlmResult<T> generateStructured(LlmRequest request, Class<T> responseType);
}
